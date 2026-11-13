package io.openfednow.shadowledger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.openfednow.shadowledger.ReconciliationService.Outcome;
import io.openfednow.shadowledger.ReconciliationService.ReconciliationAttempt;
import io.openfednow.shadowledger.ReconciliationService.ReconciliationReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Decision logic of {@link AutomaticReconciliationTrigger} — issue #68, PR 3.
 *
 * <p>The pending marker may be cleared only by a cycle that settled everything;
 * anything less keeps it. Cycles that ran but did not settle back off
 * exponentially; cycles skipped because another instance runs one do not.
 */
class AutomaticReconciliationTriggerTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private final ReconciliationService service = mock(ReconciliationService.class);
    private final ReconciliationPendingMarker marker = mock(ReconciliationPendingMarker.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicLong clock = new AtomicLong(1_000 * SECOND);

    // ── When a cycle starts ──────────────────────────────────────────────────

    @Test
    void disabledTriggerDoesNothingAtAll() {
        AutomaticReconciliationTrigger trigger = trigger(false);

        trigger.onCoreOnline();
        trigger.onCoreOffline();
        trigger.markPending("send");

        verifyNoInteractions(service, marker);
    }

    @Test
    void noPendingMarkerMeansNoCycle() {
        when(marker.currentToken()).thenReturn(Optional.empty());

        trigger(true).onCoreOnline();

        verify(service, never()).attemptReconciliation();
    }

    @Test
    void unreadableMarkerIsRetriedLaterWithoutThrowing() {
        when(marker.currentToken()).thenThrow(new RuntimeException("redis down"));
        AutomaticReconciliationTrigger trigger = trigger(true);

        trigger.onCoreOnline();

        verify(service, never()).attemptReconciliation();
        assertThat(cycles("error")).isEqualTo(1.0);
    }

    // ── Marking ──────────────────────────────────────────────────────────────

    @Test
    void offlinePollMarksPendingOnTheMarkThread() {
        trigger(true).onCoreOffline();

        verify(marker).mark("core offline");
    }

    @Test
    void paymentPathMarksSynchronously() {
        trigger(true).markPending("bridge-mode send counted");

        verify(marker).mark("bridge-mode send counted");
    }

    // ── When the marker is cleared ───────────────────────────────────────────

    @Test
    void settledCycleClearsTheMarkerItRead() {
        when(marker.currentToken()).thenReturn(Optional.of("T1"));
        when(service.attemptReconciliation()).thenReturn(attempt(Outcome.COMPLETED, 0, 0, 0));
        when(marker.clearIfUnchanged("T1")).thenReturn(true);

        trigger(true).onCoreOnline();

        verify(marker).clearIfUnchanged("T1");
        assertThat(cycles("settled")).isEqualTo(1.0);
    }

    @Test
    void cycleWithFailedAccountsKeepsTheMarker() {
        assertUnsettled(attempt(Outcome.COMPLETED, 1, 0, 0));
    }

    @Test
    void cycleWithDeferredAccountsKeepsTheMarker() {
        assertUnsettled(attempt(Outcome.COMPLETED, 0, 2, 0));
    }

    @Test
    void cycleThatLeavesAWindowOpenKeepsTheMarker() {
        // e.g. a bridge-mode send whose rail outcome is still unknown: the
        // account stays capped, so the owed reconciliation is not settled
        assertUnsettled(attempt(Outcome.COMPLETED, 0, 0, 1));
    }

    @Test
    void abortedCycleKeepsTheMarker() {
        assertUnsettled(attempt(Outcome.ABORTED, 0, 0, 0));
    }

    @Test
    void cycleSkippedBecauseAnotherInstanceRunsOneKeepsTheMarkerWithoutBackoff() {
        when(marker.currentToken()).thenReturn(Optional.of("T1"));
        when(service.attemptReconciliation()).thenReturn(attempt(Outcome.SKIPPED_ELSEWHERE, 0, 0, 0));
        AutomaticReconciliationTrigger trigger = trigger(true);

        trigger.onCoreOnline();
        trigger.onCoreOnline(); // next poll tries again right away

        verify(service, times(2)).attemptReconciliation();
        verify(marker, never()).clearIfUnchanged(anyString());
        assertThat(trigger.consecutiveUnsettled()).isZero();
        assertThat(cycles("skipped")).isEqualTo(2.0);
    }

    // ── Backoff ──────────────────────────────────────────────────────────────

    @Test
    void unsettledCyclesBackOffExponentiallyUpToTheMaximum() {
        when(marker.currentToken()).thenReturn(Optional.of("T1"));
        when(service.attemptReconciliation()).thenReturn(attempt(Outcome.COMPLETED, 1, 0, 0));
        AutomaticReconciliationTrigger trigger = trigger(true); // 30s initial, 120s max

        trigger.onCoreOnline();                 // cycle 1 → back off 30s
        clock.addAndGet(29 * SECOND);
        trigger.onCoreOnline();                 // still backing off
        verify(service, times(1)).attemptReconciliation();

        clock.addAndGet(1 * SECOND);
        trigger.onCoreOnline();                 // cycle 2 → back off 60s
        clock.addAndGet(59 * SECOND);
        trigger.onCoreOnline();
        verify(service, times(2)).attemptReconciliation();

        clock.addAndGet(1 * SECOND);
        trigger.onCoreOnline();                 // cycle 3 → back off 120s (max)
        clock.addAndGet(120 * SECOND);
        trigger.onCoreOnline();                 // cycle 4 → still 120s, never more
        clock.addAndGet(120 * SECOND);
        trigger.onCoreOnline();                 // cycle 5

        verify(service, times(5)).attemptReconciliation();
        assertThat(trigger.consecutiveUnsettled()).isEqualTo(5);
        assertThat(meters.get(AutomaticReconciliationTrigger.CONSECUTIVE_UNSETTLED_METRIC).gauge().value())
                .isEqualTo(5.0);
    }

    @Test
    void newOwedWorkIsTriedAtOnceDespiteBackoff() {
        // A stuck account backs off its own token; a new outage writes a new
        // token and must not wait behind that backoff.
        when(marker.currentToken())
                .thenReturn(Optional.of("STUCK"))
                .thenReturn(Optional.of("STUCK"))
                .thenReturn(Optional.of("NEW-OUTAGE"));
        when(service.attemptReconciliation()).thenReturn(attempt(Outcome.COMPLETED, 0, 0, 1));
        AutomaticReconciliationTrigger trigger = trigger(true);

        trigger.onCoreOnline();     // STUCK → unsettled → back off 30s
        trigger.onCoreOnline();     // STUCK again, within backoff → skipped
        verify(service, times(1)).attemptReconciliation();

        trigger.onCoreOnline();     // NEW-OUTAGE → runs immediately
        verify(service, times(2)).attemptReconciliation();
        // and backs off from the initial delay, not the stuck account's level
        assertThat(trigger.consecutiveUnsettled()).isEqualTo(1);
    }

    @Test
    void aSettledCycleResetsTheBackoff() {
        when(marker.currentToken()).thenReturn(Optional.of("T1"));
        when(service.attemptReconciliation())
                .thenReturn(attempt(Outcome.COMPLETED, 1, 0, 0))
                .thenReturn(attempt(Outcome.COMPLETED, 0, 0, 0))
                .thenReturn(attempt(Outcome.COMPLETED, 0, 0, 0));
        AutomaticReconciliationTrigger trigger = trigger(true);

        trigger.onCoreOnline();                 // unsettled → back off 30s
        clock.addAndGet(30 * SECOND);
        trigger.onCoreOnline();                 // settles
        trigger.onCoreOnline();                 // no backoff after settling

        verify(service, times(3)).attemptReconciliation();
        assertThat(trigger.consecutiveUnsettled()).isZero();
    }

    @Test
    void exceptionBacksOffAndDoesNotWedgeTheTrigger() {
        when(marker.currentToken()).thenReturn(Optional.of("T1"));
        when(service.attemptReconciliation())
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(attempt(Outcome.COMPLETED, 0, 0, 0));
        AutomaticReconciliationTrigger trigger = trigger(true);

        trigger.onCoreOnline();
        clock.addAndGet(30 * SECOND);
        trigger.onCoreOnline(); // runs again — the in-flight flag was released

        verify(service, times(2)).attemptReconciliation();
        verify(marker, times(1)).clearIfUnchanged("T1");
        assertThat(cycles("error")).isEqualTo(1.0);
    }

    @Test
    void invalidBackoffBoundsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AutomaticReconciliationTrigger(
                service, marker, meters, true, 0, 60, direct(), direct(), clock::get));
        assertThatIllegalArgumentException().isThrownBy(() -> new AutomaticReconciliationTrigger(
                service, marker, meters, true, 120, 60, direct(), direct(), clock::get));
    }

    // ── One cycle at a time per instance ─────────────────────────────────────

    @Test
    void onlyOneCycleIsInFlightPerInstance() throws Exception {
        when(marker.currentToken()).thenReturn(Optional.of("T1"));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(service.attemptReconciliation()).thenAnswer(inv -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return attempt(Outcome.COMPLETED, 0, 0, 0);
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AutomaticReconciliationTrigger trigger = new AutomaticReconciliationTrigger(
                service, marker, meters, true, 30, 120, executor, direct(), clock::get);

        trigger.onCoreOnline();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        trigger.onCoreOnline(); // polls while the first cycle is still running
        trigger.onCoreOnline();
        release.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        verify(service, times(1)).attemptReconciliation();
    }

    @Test
    void shutDownExecutorDoesNotWedgeTheTrigger() {
        when(marker.currentToken()).thenReturn(Optional.of("T1"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        AutomaticReconciliationTrigger trigger = new AutomaticReconciliationTrigger(
                service, marker, meters, true, 30, 120, executor, executor, clock::get);

        trigger.onCoreOnline(); // rejected — must not leave the in-flight flag set
        trigger.onCoreOnline();
        trigger.onCoreOffline();

        verify(service, never()).attemptReconciliation();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void assertUnsettled(ReconciliationAttempt result) {
        when(marker.currentToken()).thenReturn(Optional.of("T1"));
        when(service.attemptReconciliation()).thenReturn(result);
        AutomaticReconciliationTrigger trigger = trigger(true);

        trigger.onCoreOnline();

        verify(service).attemptReconciliation();
        verify(marker, never()).clearIfUnchanged(anyString());
        assertThat(trigger.consecutiveUnsettled()).isEqualTo(1);
        assertThat(cycles("unsettled")).isEqualTo(1.0);
    }

    private AutomaticReconciliationTrigger trigger(boolean enabled) {
        return new AutomaticReconciliationTrigger(service, marker, meters, enabled, 30, 120,
                direct(), direct(), clock::get);
    }

    private double cycles(String result) {
        return meters.get(AutomaticReconciliationTrigger.CYCLES_METRIC).tag("result", result).counter().count();
    }

    private static ReconciliationAttempt attempt(Outcome outcome, int failed, int deferred, int openWindows) {
        return new ReconciliationAttempt(outcome, new ReconciliationReport(0, 0, true, "test"),
                failed, deferred, openWindows);
    }

    private static ExecutorService direct() {
        return new DirectExecutor();
    }

    /** Runs tasks on the calling thread so assertions see the finished cycle. */
    private static final class DirectExecutor extends AbstractExecutorService {
        private boolean shutdown;
        @Override public void execute(Runnable command) {
            if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
            command.run();
        }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }
}
