package io.openfednow.shadowledger;

import io.openfednow.acl.core.CoreBankingAdapter;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Layer 4 — 24/7 Availability Bridge
 *
 * <p>Manages the operational state of the OpenFedNow framework relative to
 * the core banking system's availability. Ensures that the institution
 * remains available to FedNow at all times, even during core system
 * maintenance windows.
 *
 * <p>The bridge operates in two modes:
 * <ul>
 *   <li><strong>Online mode</strong>: Core system is available. Transactions
 *       are submitted to the core system in real time via the ACL. The Shadow
 *       Ledger is continuously updated.</li>
 *   <li><strong>Bridge mode</strong>: Core system is offline. Inbound payments
 *       are queued via async messaging and processed against the Shadow Ledger.
 *       Outbound payment authorization uses Shadow Ledger balances. All queued
 *       transactions are replayed when the core comes back online.</li>
 * </ul>
 */
@Component
public class AvailabilityBridge {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityBridge.class);

    private final CoreBankingAdapter coreBankingAdapter;
    private final ShadowLedger shadowLedger;
    private final RabbitTemplate rabbitTemplate;
    private final ReconciliationService reconciliationService;

    /**
     * Whether an offline→online transition starts a reconciliation cycle on
     * its own ({@code openfednow.reconciliation.auto-on-core-recovery},
     * default true). With the bridge-mode aggregate cap (#68) this is what
     * lifts the cap after a maintenance window without an operator having to
     * remember {@code POST /admin/reconcile}; accounts stay capped until the
     * cycle confirms their balances. Disable only if reconciliation timing is
     * managed externally.
     */
    private final boolean autoReconcileOnRecovery;

    /**
     * Runs reconciliation off the scheduler thread. The @Scheduled pool is
     * shared with the saga timeout monitor and the cleanup sweeps; a long
     * reconciliation cycle must not delay those. Single-threaded: one trigger
     * at a time per instance (the service's own lock and the cross-instance
     * lease guard the rest).
     */
    private final ExecutorService reconcileExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "auto-reconciliation");
        t.setDaemon(true);
        return t;
    });

    /**
     * Tracks the last known core availability to detect mode transitions.
     * Volatile ensures visibility across threads without requiring synchronization.
     */
    private volatile boolean lastKnownAvailable = true;

    /**
     * Steady-state retry pacing: while the core is online and unconfirmed
     * ledger rows remain (the first auto cycle after a recovery partially
     * failed — a recovering core is often still flaky), the poll retriggers a
     * cycle, but at most once per this many seconds. Keeps a persistent
     * failure from writing a reconciliation_run row every poll.
     */
    private final int autoRetryIntervalSeconds;

    /** Epoch millis of the last automatic trigger; gates the steady-state retry. */
    private volatile long lastAutoTriggerAtMillis;

    /**
     * True while an automatically triggered cycle is running. Without this, a
     * cycle longer than the retry interval would let each poll queue another
     * task behind it on the serial executor; those would then run back to back
     * without re-checking whether work remains.
     */
    private final java.util.concurrent.atomic.AtomicBoolean autoCycleInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public AvailabilityBridge(CoreBankingAdapter coreBankingAdapter,
                               ShadowLedger shadowLedger,
                               RabbitTemplate rabbitTemplate,
                               ReconciliationService reconciliationService,
                               @Value("${openfednow.reconciliation.auto-on-core-recovery:true}")
                               boolean autoReconcileOnRecovery,
                               @Value("${openfednow.reconciliation.auto-retry-interval-seconds:300}")
                               int autoRetryIntervalSeconds) {
        if (autoRetryIntervalSeconds <= 0) {
            throw new IllegalArgumentException(
                    "openfednow.reconciliation.auto-retry-interval-seconds must be positive");
        }
        this.coreBankingAdapter = coreBankingAdapter;
        this.shadowLedger = shadowLedger;
        this.rabbitTemplate = rabbitTemplate;
        this.reconciliationService = reconciliationService;
        this.autoReconcileOnRecovery = autoReconcileOnRecovery;
        this.autoRetryIntervalSeconds = autoRetryIntervalSeconds;
    }

    /**
     * Returns whether the framework is currently in bridge mode
     * (core system offline, processing against Shadow Ledger only).
     *
     * @return true if operating in bridge mode
     */
    public boolean isInBridgeMode() {
        return !coreBankingAdapter.isCoreSystemAvailable();
    }

    /**
     * Queues a transaction for processing when the core system returns online.
     *
     * <p>Publishes the serialized transaction payload to the
     * {@code maintenance-window-transactions} RabbitMQ queue. The
     * {@link ReconciliationService} consumes this queue after the core returns.
     *
     * @param transactionId the FedNow end-to-end transaction identifier
     * @param payload       the serialized pacs.008 transaction payload
     */
    public void queueForCoreProcessing(String transactionId, String payload) {
        rabbitTemplate.convertAndSend(RabbitMqConfig.MAINTENANCE_QUEUE, payload,
                message -> {
                    message.getMessageProperties().setMessageId(transactionId);
                    message.getMessageProperties().setContentType("application/json");
                    return message;
                });
        log.info("Transaction queued for core replay transactionId={}", transactionId);
    }

    /**
     * Monitors core system availability and logs mode transitions.
     *
     * <p>Runs on a fixed delay controlled by
     * {@code openfednow.shadow-ledger.reconciliation-poll-interval-seconds}
     * (default: 30 seconds). Logs an event whenever the core transitions
     * between online and bridge modes so that operations dashboards and
     * alerting systems can detect maintenance windows.
     */
    @Scheduled(fixedDelayString =
            "${openfednow.shadow-ledger.reconciliation-poll-interval-seconds:30}000")
    public void pollCoreAvailability() {
        boolean currentlyAvailable;
        try {
            currentlyAvailable = coreBankingAdapter.isCoreSystemAvailable();
        } catch (Exception e) {
            log.warn("Core availability check threw exception — treating as unavailable", e);
            currentlyAvailable = false;
        }

        if (currentlyAvailable != lastKnownAvailable) {
            if (currentlyAvailable) {
                log.info("Core banking system ONLINE — exiting bridge mode");
                // Trigger only when there is something to reconcile. A probe
                // hiccup is treated as an outage above, so without this check
                // every blip would cost a full no-op cycle and an audit row.
                // If the pending-work probe itself fails, err toward running
                // the cycle — reconciling nothing is cheaper than not
                // reconciling something.
                if (hasPendingWorkSafely()) {
                    triggerAutoReconciliation("core recovery");
                } else {
                    log.info("Core recovered with no pending reconciliation work — no cycle started");
                }
            } else {
                log.warn("Core banking system OFFLINE — entering bridge mode, " +
                         "transactions will be queued for replay");
            }
            lastKnownAvailable = currentlyAvailable;
        } else if (currentlyAvailable) {
            // Steady-state retry: a cycle triggered at recovery can fail or
            // partially fail (the core is often still shaky right then). Work
            // left behind would otherwise wait for the NEXT outage, a restart,
            // or an operator — and with the aggregate cap (#68) that means
            // accounts stay capped. Retry whenever another cycle could make
            // progress (unconfirmed rows, or a window holding already-released
            // sends that failed to release), paced by the retry interval and
            // skipped while a cycle is still running. Windows waiting on an
            // unresolved send are excluded — no cycle can hurry those; see
            // ReconciliationService.hasRetryableWork().
            if (autoReconcileOnRecovery && retryIntervalElapsed() && !autoCycleInFlight.get()) {
                try {
                    if (reconciliationService.hasRetryableWork()) {
                        triggerAutoReconciliation("retryable work pending while online");
                    }
                } catch (Exception e) {
                    log.warn("Retryable-work check failed — retry deferred to the next poll", e);
                }
            }
        } else {
            log.debug("Core availability poll: mode=BRIDGE");
        }
    }

    private boolean retryIntervalElapsed() {
        return System.currentTimeMillis() - lastAutoTriggerAtMillis
                >= autoRetryIntervalSeconds * 1000L;
    }

    /** Pending-work probe that errs toward "yes" — see the call site. */
    private boolean hasPendingWorkSafely() {
        try {
            return reconciliationService.hasPendingWork();
        } catch (Exception e) {
            log.warn("Pending-work check failed — starting a reconciliation cycle anyway", e);
            return true;
        }
    }

    /**
     * Startup catch-up. A single instance restarted after a maintenance window
     * ended never observes an offline→online transition, so the poll alone
     * would leave unconfirmed entries — and any capped accounts (#68) — waiting
     * for a manual trigger. If the core is online and there is pending work,
     * start a cycle. Harmless with several instances: the reconciliation
     * lease lets exactly one of them run.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartupIfPending() {
        if (!autoReconcileOnRecovery) {
            return;
        }
        boolean available;
        try {
            available = coreBankingAdapter.isCoreSystemAvailable();
        } catch (Exception e) {
            log.warn("Startup core availability check threw exception — skipping startup "
                    + "reconciliation (the availability poll takes over)", e);
            return;
        }
        if (!available) {
            lastKnownAvailable = false;
            log.warn("Core offline at startup — entering bridge mode; reconciliation will "
                    + "trigger when the core returns");
            return;
        }
        try {
            if (reconciliationService.hasPendingWork()) {
                triggerAutoReconciliation("startup with pending work");
            }
        } catch (Exception e) {
            log.error("Startup pending-work check failed — reconciliation not triggered "
                    + "(the steady-state retry takes over if entries are pending)", e);
        }
    }

    /**
     * Submits one reconciliation cycle to the dedicated executor. Never runs
     * on the caller's thread: the poll shares Spring's scheduler with the saga
     * timeout monitor, which must not wait behind a reconciliation cycle.
     */
    private void triggerAutoReconciliation(String cause) {
        if (!autoReconcileOnRecovery) {
            log.info("Automatic reconciliation disabled "
                    + "(openfednow.reconciliation.auto-on-core-recovery=false) — "
                    + "waiting for a manual trigger ({})", cause);
            return;
        }
        log.info("Triggering automatic reconciliation ({})", cause);
        lastAutoTriggerAtMillis = System.currentTimeMillis();
        reconcileExecutor.submit(() -> {
            autoCycleInFlight.set(true);
            try {
                ReconciliationService.ReconciliationReport report =
                        reconciliationService.reconcile("AUTO_RECOVERY");
                log.info("Automatic reconciliation ({}) finished: {}", cause, report.summary());
            } catch (Throwable e) {
                // Throwable, not Exception: this Future is never inspected, so
                // anything narrower would let an Error vanish silently.
                log.error("Automatic reconciliation ({}) failed — unconfirmed entries and any "
                        + "capped accounts remain until the steady-state retry", cause, e);
            } finally {
                // Stamp at completion as well as at submit, so the retry
                // interval is measured from when the last cycle ENDED.
                lastAutoTriggerAtMillis = System.currentTimeMillis();
                autoCycleInFlight.set(false);
            }
        });
    }

    @PreDestroy
    void shutdownExecutor() {
        reconcileExecutor.shutdownNow();
    }
}
