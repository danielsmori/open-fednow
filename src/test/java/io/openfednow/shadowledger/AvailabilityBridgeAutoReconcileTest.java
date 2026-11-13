package io.openfednow.shadowledger;

import io.openfednow.acl.core.CoreBankingAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The automatic reconciliation trigger in {@link AvailabilityBridge} — issue
 * #68, PR 3.
 *
 * <p>Reconciliation runs on a dedicated executor thread, so assertions use
 * Mockito's {@code timeout()} (it happened within the window) and
 * {@code after()} (it never happened within the window).
 */
class AvailabilityBridgeAutoReconcileTest {

    private final CoreBankingAdapter core = mock(CoreBankingAdapter.class);
    private final ReconciliationService reconciliation = mock(ReconciliationService.class);

    private AvailabilityBridge bridge(boolean autoEnabled) {
        return bridge(autoEnabled, 300);
    }

    private AvailabilityBridge bridge(boolean autoEnabled, int retryIntervalSeconds) {
        // Transitions only start a cycle when there is something to do.
        when(reconciliation.hasPendingWork()).thenReturn(true);
        return new AvailabilityBridge(core, mock(ShadowLedger.class),
                mock(RabbitTemplate.class), reconciliation, autoEnabled, retryIntervalSeconds);
    }

    @Test
    void offlineToOnlineTransitionTriggersExactlyOneCycle() {
        AvailabilityBridge bridge = bridge(true);
        when(core.isCoreSystemAvailable()).thenReturn(false);
        bridge.pollCoreAvailability(); // online -> offline

        when(core.isCoreSystemAvailable()).thenReturn(true);
        bridge.pollCoreAvailability(); // offline -> online: trigger
        verify(reconciliation, timeout(2000).times(1)).reconcile("AUTO_RECOVERY");

        bridge.pollCoreAvailability(); // stays online: no second trigger
        verify(reconciliation, after(300).times(1)).reconcile("AUTO_RECOVERY");
    }

    @Test
    void stayingOnlineNeverTriggers() {
        AvailabilityBridge bridge = bridge(true);
        when(core.isCoreSystemAvailable()).thenReturn(true);

        bridge.pollCoreAvailability();
        bridge.pollCoreAvailability();

        verify(reconciliation, after(300).never()).reconcile("AUTO_RECOVERY");
    }

    @Test
    void goingOfflineNeverTriggers() {
        AvailabilityBridge bridge = bridge(true);
        when(core.isCoreSystemAvailable()).thenReturn(false);

        bridge.pollCoreAvailability();

        verify(reconciliation, after(300).never()).reconcile("AUTO_RECOVERY");
    }

    @Test
    void disabledFlagSuppressesTheTrigger() {
        AvailabilityBridge bridge = bridge(false);
        when(core.isCoreSystemAvailable()).thenReturn(false);
        bridge.pollCoreAvailability();
        when(core.isCoreSystemAvailable()).thenReturn(true);

        bridge.pollCoreAvailability();

        verify(reconciliation, after(300).never()).reconcile("AUTO_RECOVERY");
    }

    // ── Startup catch-up ─────────────────────────────────────────────────────

    @Test
    void startupWithPendingWorkAndAnOnlineCoreTriggersOneCycle() {
        AvailabilityBridge bridge = bridge(true);
        when(core.isCoreSystemAvailable()).thenReturn(true);
        when(reconciliation.hasPendingWork()).thenReturn(true);

        bridge.reconcileOnStartupIfPending();

        verify(reconciliation, timeout(2000).times(1)).reconcile("AUTO_RECOVERY");
    }

    @Test
    void startupWithNothingPendingDoesNotTrigger() {
        AvailabilityBridge bridge = bridge(true);
        when(core.isCoreSystemAvailable()).thenReturn(true);
        when(reconciliation.hasPendingWork()).thenReturn(false);

        bridge.reconcileOnStartupIfPending();

        verify(reconciliation, after(300).never()).reconcile("AUTO_RECOVERY");
    }

    @Test
    void startupDuringAnOutageDefersAndTheRecoveryTransitionStillFires() {
        AvailabilityBridge bridge = bridge(true);
        when(core.isCoreSystemAvailable()).thenReturn(false);
        bridge.reconcileOnStartupIfPending(); // enters bridge mode, no trigger
        verify(reconciliation, after(300).never()).reconcile("AUTO_RECOVERY");

        when(core.isCoreSystemAvailable()).thenReturn(true);
        bridge.pollCoreAvailability(); // the recovery is still observed

        verify(reconciliation, timeout(2000).times(1)).reconcile("AUTO_RECOVERY");
    }

    @Test
    void startupCheckFailureIsSwallowedAndDoesNotTrigger() {
        AvailabilityBridge bridge = bridge(true);
        when(core.isCoreSystemAvailable()).thenThrow(new RuntimeException("core probe failed"));

        bridge.reconcileOnStartupIfPending();

        verify(reconciliation, after(300).never()).reconcile("AUTO_RECOVERY");
    }

    @Test
    void disabledFlagSuppressesTheStartupTrigger() {
        AvailabilityBridge bridge = bridge(false);
        when(core.isCoreSystemAvailable()).thenReturn(true);
        when(reconciliation.hasPendingWork()).thenReturn(true);

        bridge.reconcileOnStartupIfPending();

        verify(reconciliation, after(300).never()).reconcile("AUTO_RECOVERY");
        verify(reconciliation, never()).hasPendingWork();
    }

    // ── Pending-work gating and the steady-state retry ───────────────────────

    @Test
    void recoveryWithNothingPendingStartsNoCycle() {
        AvailabilityBridge bridge = bridge(true);
        when(reconciliation.hasPendingWork()).thenReturn(false);
        when(core.isCoreSystemAvailable()).thenReturn(false);
        bridge.pollCoreAvailability();
        when(core.isCoreSystemAvailable()).thenReturn(true);

        bridge.pollCoreAvailability();

        verify(reconciliation, after(300).never()).reconcile("AUTO_RECOVERY");
    }

    @Test
    void recoveryStillTriggersWhenThePendingWorkProbeFails() {
        AvailabilityBridge bridge = bridge(true);
        when(reconciliation.hasPendingWork()).thenThrow(new RuntimeException("db hiccup"));
        when(core.isCoreSystemAvailable()).thenReturn(false);
        bridge.pollCoreAvailability();
        when(core.isCoreSystemAvailable()).thenReturn(true);

        bridge.pollCoreAvailability();

        // erring toward reconciling: a no-op cycle is cheaper than a stuck cap
        verify(reconciliation, timeout(2000).times(1)).reconcile("AUTO_RECOVERY");
    }

    @Test
    void retryableWorkWhileOnlineRetriggersACycle() {
        AvailabilityBridge bridge = bridge(true, 300);
        when(core.isCoreSystemAvailable()).thenReturn(true);
        when(reconciliation.hasRetryableWork()).thenReturn(true);

        bridge.pollCoreAvailability(); // steady online, retryable work pending

        verify(reconciliation, timeout(2000).times(1)).reconcile("AUTO_RECOVERY");
    }

    @Test
    void steadyStateRetryIsPacedByTheRetryInterval() {
        AvailabilityBridge bridge = bridge(true, 300);
        when(core.isCoreSystemAvailable()).thenReturn(true);
        when(reconciliation.hasRetryableWork()).thenReturn(true);

        bridge.pollCoreAvailability(); // triggers and stamps the interval gate
        verify(reconciliation, timeout(2000).times(1)).reconcile("AUTO_RECOVERY");

        bridge.pollCoreAvailability(); // within the interval: no second cycle
        bridge.pollCoreAvailability();

        verify(reconciliation, after(300).times(1)).reconcile("AUTO_RECOVERY");
    }

    @Test
    void steadyStateRetryFiresAgainOnceTheIntervalElapses() {
        AvailabilityBridge bridge = bridge(true, 1);
        when(core.isCoreSystemAvailable()).thenReturn(true);
        when(reconciliation.hasRetryableWork()).thenReturn(true);

        bridge.pollCoreAvailability();
        verify(reconciliation, timeout(2000).times(1)).reconcile("AUTO_RECOVERY");

        try { Thread.sleep(1100); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        bridge.pollCoreAvailability();

        verify(reconciliation, timeout(2000).times(2)).reconcile("AUTO_RECOVERY");
    }

    @Test
    void steadyStateWithoutRetryableWorkNeverRetriggers() {
        AvailabilityBridge bridge = bridge(true, 1);
        when(core.isCoreSystemAvailable()).thenReturn(true);
        when(reconciliation.hasRetryableWork()).thenReturn(false);

        bridge.pollCoreAvailability();
        bridge.pollCoreAvailability();

        verify(reconciliation, after(300).never()).reconcile("AUTO_RECOVERY");
    }

    @Test
    void nonPositiveRetryIntervalIsRejected() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> bridge(true, 0))
                .withMessageContaining("auto-retry-interval-seconds");
    }
}
