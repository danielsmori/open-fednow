package io.openfednow.shadowledger;

import io.openfednow.acl.core.CoreBankingAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
 *       Outbound payment authorization uses Shadow Ledger balances. Queued
 *       inbound payments are meant to be replayed to the core when it returns,
 *       but nothing consumes the queue yet (#151).</li>
 * </ul>
 */
@Component
public class AvailabilityBridge {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityBridge.class);

    private final CoreBankingAdapter coreBankingAdapter;
    private final ShadowLedger shadowLedger;
    private final RabbitTemplate rabbitTemplate;
    private final AutomaticReconciliationTrigger reconciliationTrigger;

    /**
     * Tracks the last known core availability to detect mode transitions.
     * Volatile ensures visibility across threads without requiring synchronization.
     */
    private volatile boolean lastKnownAvailable = true;

    public AvailabilityBridge(CoreBankingAdapter coreBankingAdapter,
                               ShadowLedger shadowLedger,
                               RabbitTemplate rabbitTemplate,
                               AutomaticReconciliationTrigger reconciliationTrigger) {
        this.coreBankingAdapter = coreBankingAdapter;
        this.shadowLedger = shadowLedger;
        this.rabbitTemplate = rabbitTemplate;
        this.reconciliationTrigger = reconciliationTrigger;
    }

    /**
     * Records that bridge-mode work happened and a reconciliation is owed once
     * the core is back (#68). Called for work the availability poll might not
     * see — an outage shorter than one poll interval. Never throws; a no-op
     * when automatic reconciliation is disabled.
     */
    public void markReconciliationPending(String reason) {
        reconciliationTrigger.markPending(reason);
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
     * {@code maintenance-window-transactions} RabbitMQ queue. Nothing consumes
     * this queue yet, so the core never receives these payments (#151); the
     * provisional Shadow Ledger credit is the only effect.
     *
     * @param transactionId the FedNow end-to-end transaction identifier
     * @param payload       the serialized pacs.008 transaction payload
     */
    public void queueForCoreProcessing(String transactionId, String payload) {
        // Mark first: the provisional credit is already applied, so a
        // reconciliation is owed even if the publish below fails.
        markReconciliationPending("inbound payment queued for core replay");
        rabbitTemplate.convertAndSend(RabbitMqConfig.MAINTENANCE_QUEUE, payload,
                message -> {
                    message.getMessageProperties().setMessageId(transactionId);
                    message.getMessageProperties().setContentType("application/json");
                    return message;
                });
        log.info("Transaction queued for core replay transactionId={}", transactionId);
    }

    /**
     * Monitors core system availability, logs mode transitions, and drives
     * automatic reconciliation (#68).
     *
     * <p>Runs on a fixed delay controlled by
     * {@code openfednow.shadow-ledger.reconciliation-poll-interval-seconds}
     * (default: 30 seconds). Logs an event whenever the core transitions
     * between online and bridge modes so that operations dashboards and
     * alerting systems can detect maintenance windows.
     *
     * <p>Every poll hands over to {@link AutomaticReconciliationTrigger}: one
     * that finds the core offline marks a reconciliation as owed (on every
     * poll, not only on the transition, so a mark lost to a transient Redis
     * error is retried); one that finds it online starts a cycle in the
     * background if one is owed — whether this instance saw the outage or not.
     * Neither blocks this (scheduler) thread on Redis or on the cycle.
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
                log.info("Core banking system ONLINE — exiting bridge mode, reconciliation pending");
            } else {
                log.warn("Core banking system OFFLINE — entering bridge mode, " +
                         "transactions will be queued for replay");
            }
            lastKnownAvailable = currentlyAvailable;
        } else {
            log.debug("Core availability poll: mode={}", currentlyAvailable ? "ONLINE" : "BRIDGE");
        }

        if (currentlyAvailable) {
            reconciliationTrigger.onCoreOnline();
        } else {
            reconciliationTrigger.onCoreOffline();
        }
    }
}
