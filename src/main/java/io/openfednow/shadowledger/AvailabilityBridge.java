package io.openfednow.shadowledger;

import io.openfednow.acl.core.CoreBankingAdapter;
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
 *       Outbound payment authorization uses Shadow Ledger balances. All queued
 *       transactions are replayed when the core comes back online.</li>
 * </ul>
 */
@Component
public class AvailabilityBridge {

    private final CoreBankingAdapter coreBankingAdapter;
    private final ShadowLedger shadowLedger;

    public AvailabilityBridge(CoreBankingAdapter coreBankingAdapter, ShadowLedger shadowLedger) {
        this.coreBankingAdapter = coreBankingAdapter;
        this.shadowLedger = shadowLedger;
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
     * Called when a payment arrives during a maintenance window.
     *
     * @param transactionId the FedNow end-to-end transaction identifier
     * @param payload       the serialized transaction payload
     */
    public void queueForCoreProcessing(String transactionId, String payload) {
        // TODO: publish to async message queue (RabbitMQ/Kafka)
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Monitors core system availability and transitions between online
     * and bridge modes. Called on a scheduled polling interval.
     */
    public void pollCoreAvailability() {
        // TODO: implement availability polling and mode transition logic
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
