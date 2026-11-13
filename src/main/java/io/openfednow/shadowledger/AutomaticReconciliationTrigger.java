package io.openfednow.shadowledger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.openfednow.shadowledger.ReconciliationService.Outcome;
import io.openfednow.shadowledger.ReconciliationService.ReconciliationAttempt;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Starts reconciliation automatically once the core is back online — issue #68.
 *
 * <p>The bridge-mode aggregate send cap fails closed: an account that sent
 * during a maintenance window stays capped until reconciliation confirms its
 * sends. An operator-only trigger would leave every such account capped until
 * someone remembered to run it, so {@link AvailabilityBridge} calls
 * {@link #onCoreOnline()} on every availability poll that finds the core up,
 * and {@link #onCoreOffline()} on every poll that finds it down.
 *
 * <p><strong>When a cycle runs.</strong> Only while a reconciliation is owed
 * ({@link ReconciliationPendingMarker}). The marker is cleared only by a cycle
 * that {@linkplain ReconciliationAttempt#fullyReconciled() settled everything}:
 * ran to the end, reconciled every account, and left no account capped.
 * <ul>
 *   <li>A cycle <em>skipped</em> because another instance is running one is
 *       retried on the next poll, quietly — the cross-instance
 *       {@link ReconciliationLock} makes that cheap.</li>
 *   <li>A cycle that ran but did not settle (aborted, failed or deferred
 *       accounts, windows still open — e.g. a send whose rail outcome is still
 *       unknown) is retried with exponential backoff, from
 *       {@code openfednow.reconciliation.auto-retry-backoff-initial-seconds}
 *       up to {@code ...-max-seconds}, so a permanently stuck account does not
 *       make every instance run a full cycle every poll. The backoff applies
 *       only while the owed work is unchanged: any new mark (a new outage, new
 *       bridge-mode activity) writes a new token, and a new token is tried at
 *       once — a stuck account never delays the next window's reconciliation.
 *       The {@code reconciliation.auto.consecutive_unsettled} gauge makes the
 *       stuck state visible. Backoff state is per instance: each instance backs
 *       off after its own unsettled cycles, and the gauge reads 0 on instances
 *       that were always skipped.</li>
 * </ul>
 *
 * <p><strong>Threads.</strong> Nothing here blocks the scheduler thread:
 * Spring's default scheduler has a single thread shared with saga timeouts
 * and compensation retries. Cycles run on a dedicated thread (at most one in
 * flight per instance); the poll's offline mark runs on a second, separate
 * thread so it is never queued behind a running cycle — and is dropped if a
 * previous mark is still pending, since the next offline poll marks again.
 * Marks from payment paths ({@link #markPending}) stay synchronous: those
 * threads already depend on Redis for the Shadow Ledger.
 *
 * <p>Enabled by default ({@code openfednow.reconciliation.auto-on-core-recovery}).
 * When disabled, nothing is marked or run, and reconciliation is
 * operator-driven only ({@code /admin/reconcile}).
 */
@Component
public class AutomaticReconciliationTrigger {

    private static final Logger log = LoggerFactory.getLogger(AutomaticReconciliationTrigger.class);

    static final String CYCLES_METRIC = "reconciliation.auto.cycles";
    static final String CONSECUTIVE_UNSETTLED_METRIC = "reconciliation.auto.consecutive_unsettled";

    private final ReconciliationService reconciliationService;
    private final ReconciliationPendingMarker pendingMarker;
    private final boolean enabled;
    private final long initialBackoffNanos;
    private final long maxBackoffNanos;
    private final ExecutorService cycleExecutor;
    private final ExecutorService markExecutor;
    private final LongSupplier nanoClock;

    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicInteger consecutiveUnsettled = new AtomicInteger();
    /**
     * Backoff state, touched only on the cycle thread: the marker token the last
     * unsettled cycle read, and the {@link #nanoClock} reading before which that
     * same token is not retried.
     */
    private String backoffToken;
    private long nextAttemptNanos;

    private final Counter settledCycles;
    private final Counter unsettledCycles;
    private final Counter skippedCycles;
    private final Counter failedCycles;

    @Autowired
    public AutomaticReconciliationTrigger(
            ReconciliationService reconciliationService,
            ReconciliationPendingMarker pendingMarker,
            MeterRegistry meterRegistry,
            @Value("${openfednow.reconciliation.auto-on-core-recovery:true}") boolean enabled,
            @Value("${openfednow.reconciliation.auto-retry-backoff-initial-seconds:30}") long initialBackoffSeconds,
            @Value("${openfednow.reconciliation.auto-retry-backoff-max-seconds:900}") long maxBackoffSeconds) {
        this(reconciliationService, pendingMarker, meterRegistry, enabled,
                initialBackoffSeconds, maxBackoffSeconds,
                Executors.newSingleThreadExecutor(daemon("reconciliation-auto")),
                new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1),
                        daemon("reconciliation-mark"), new ThreadPoolExecutor.DiscardPolicy()),
                System::nanoTime);
    }

    /** Visible for tests: synchronous or controllable executors, a fake clock. */
    AutomaticReconciliationTrigger(ReconciliationService reconciliationService,
                                   ReconciliationPendingMarker pendingMarker,
                                   MeterRegistry meterRegistry,
                                   boolean enabled,
                                   long initialBackoffSeconds,
                                   long maxBackoffSeconds,
                                   ExecutorService cycleExecutor,
                                   ExecutorService markExecutor,
                                   LongSupplier nanoClock) {
        if (initialBackoffSeconds <= 0 || maxBackoffSeconds < initialBackoffSeconds) {
            throw new IllegalArgumentException("openfednow.reconciliation.auto-retry-backoff-initial-seconds "
                    + "must be positive and not exceed ...-max-seconds");
        }
        this.reconciliationService = reconciliationService;
        this.pendingMarker = pendingMarker;
        this.enabled = enabled;
        this.initialBackoffNanos = TimeUnit.SECONDS.toNanos(initialBackoffSeconds);
        this.maxBackoffNanos = TimeUnit.SECONDS.toNanos(maxBackoffSeconds);
        this.cycleExecutor = cycleExecutor;
        this.markExecutor = markExecutor;
        this.nanoClock = nanoClock;

        this.settledCycles = cycles(meterRegistry, "settled");
        this.unsettledCycles = cycles(meterRegistry, "unsettled");
        this.skippedCycles = cycles(meterRegistry, "skipped");
        this.failedCycles = cycles(meterRegistry, "error");
        Gauge.builder(CONSECUTIVE_UNSETTLED_METRIC, consecutiveUnsettled, AtomicInteger::get)
                .description("Automatic reconciliation cycles in a row that ran but did not settle the backlog")
                .register(meterRegistry);

        log.info("Automatic reconciliation on core recovery {}", enabled ? "ENABLED" : "DISABLED");
    }

    private static Counter cycles(MeterRegistry registry, String result) {
        return Counter.builder(CYCLES_METRIC)
                .description("Automatic reconciliation cycles by result")
                .tag("result", result)
                .register(registry);
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Called by the availability poller each time it finds the core online.
     * Returns immediately; the marker is read and the cycle, if one is owed,
     * runs on the cycle thread.
     */
    public void onCoreOnline() {
        if (!enabled) {
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            log.debug("Automatic reconciliation already in flight on this instance");
            return;
        }
        try {
            cycleExecutor.execute(this::runCycle);
        } catch (RejectedExecutionException e) {
            inFlight.set(false);
            log.warn("Automatic reconciliation not started — executor is shutting down");
        }
    }

    /**
     * Called by the availability poller each time it finds the core offline:
     * records that a reconciliation is owed, off the scheduler thread.
     */
    public void onCoreOffline() {
        if (!enabled) {
            return;
        }
        try {
            markExecutor.execute(() -> pendingMarker.mark("core offline"));
        } catch (RejectedExecutionException e) {
            log.debug("Offline mark not scheduled — executor is shutting down");
        }
    }

    /**
     * Records, synchronously, that bridge-mode work happened and a
     * reconciliation is owed. For payment paths, which may see an outage too
     * short for the poll to notice. Never throws.
     */
    public void markPending(String reason) {
        if (enabled) {
            pendingMarker.mark(reason);
        }
    }

    private void runCycle() {
        String readToken = null;
        try {
            Optional<String> token = pendingMarker.currentToken();
            if (token.isEmpty()) {
                // Nothing owed (perhaps settled by another instance): this
                // instance is no longer behind on anything.
                consecutiveUnsettled.set(0);
                backoffToken = null;
                return;
            }
            readToken = token.get();
            if (backoffToken != null && !readToken.equals(backoffToken)) {
                // New owed work: it starts its own backoff from the initial
                // delay instead of inheriting a stuck account's maximum.
                consecutiveUnsettled.set(0);
                backoffToken = null;
            }
            if (readToken.equals(backoffToken) && nanoClock.getAsLong() - nextAttemptNanos < 0) {
                log.debug("Automatic reconciliation backing off after {} unsettled cycle(s)",
                        consecutiveUnsettled.get());
                return;
            }
            log.info("Core online with reconciliation pending — starting automatic reconciliation");
            ReconciliationAttempt attempt = reconciliationService.attemptReconciliation();

            if (attempt.outcome() == Outcome.SKIPPED_ELSEWHERE || attempt.outcome() == Outcome.SKIPPED_IN_PROGRESS) {
                // Normal on every other instance while one runs the cycle.
                skippedCycles.increment();
                log.debug("Automatic reconciliation skipped outcome={}", attempt.outcome());
                return;
            }
            if (!attempt.fullyReconciled()) {
                unsettledCycles.increment();
                long backoff = backoffAfter(consecutiveUnsettled.incrementAndGet());
                backoffToken = token.get();
                nextAttemptNanos = nanoClock.getAsLong() + backoff;
                log.warn("Automatic reconciliation did not settle the backlog outcome={} failedAccounts={} "
                                + "deferredAccounts={} openWindowsRemaining={} consecutiveUnsettled={} "
                                + "summary={} — retrying in {}s",
                        attempt.outcome(), attempt.failedAccounts(), attempt.deferredAccounts(),
                        attempt.openWindowsRemaining(), consecutiveUnsettled.get(),
                        attempt.report().summary(), TimeUnit.NANOSECONDS.toSeconds(backoff));
                return;
            }

            settledCycles.increment();
            consecutiveUnsettled.set(0);
            backoffToken = null;
            if (pendingMarker.clearIfUnchanged(token.get())) {
                log.info("Automatic reconciliation complete — pending marker cleared ({})",
                        attempt.report().summary());
            } else if (pendingMarker.currentToken().isEmpty()) {
                log.debug("Automatic reconciliation complete — marker already cleared by another instance");
            } else {
                log.info("Automatic reconciliation complete, but a reconciliation was marked owed again "
                        + "during the cycle — another cycle runs on the next poll");
            }
        } catch (RuntimeException e) {
            failedCycles.increment();
            if (readToken == null) {
                // The marker itself was unreadable (e.g. Redis down): no cycle
                // ran, so this is not an unsettled cycle. Counting it would
                // inflate the backoff of the next real owed work.
                log.warn("Automatic reconciliation could not read the pending marker — retrying on the next poll",
                        e);
                return;
            }
            long backoff = backoffAfter(consecutiveUnsettled.incrementAndGet());
            // Back off the work this cycle failed on.
            backoffToken = readToken;
            nextAttemptNanos = nanoClock.getAsLong() + backoff;
            log.error("Automatic reconciliation failed — retrying in {}s",
                    TimeUnit.NANOSECONDS.toSeconds(backoff), e);
        } finally {
            inFlight.set(false);
        }
    }

    /** initial × 2^(n-1), capped at the maximum, without overflow. */
    private long backoffAfter(int consecutive) {
        int shift = Math.min(consecutive - 1, 62);
        return initialBackoffNanos > (maxBackoffNanos >> shift) ? maxBackoffNanos : initialBackoffNanos << shift;
    }

    /** Visible for tests. */
    int consecutiveUnsettled() {
        return consecutiveUnsettled.get();
    }

    /**
     * Lets a running cycle finish (bounded) before the context closes its
     * DataSource, so the cycle's run record is closed and its lock released
     * normally.
     */
    @PreDestroy
    void shutdown() {
        markExecutor.shutdownNow();
        cycleExecutor.shutdown();
        try {
            if (!cycleExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Automatic reconciliation still running at shutdown — interrupting");
                cycleExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cycleExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
