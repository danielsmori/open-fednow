package io.openfednow.events;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable domain event representing a significant lifecycle transition of a payment.
 *
 * <p>Published to the Kafka event bus (when enabled) by {@link io.openfednow.gateway.MessageRouter}
 * so that downstream consumers — audit systems, analytics pipelines, notification services — can
 * react to payment state changes without polling the core banking system or the Shadow Ledger.
 *
 * <p>The Kafka bus is optional and coexists with the RabbitMQ maintenance-window queue:
 * RabbitMQ handles the sync→async deferral of payments during core-offline windows;
 * Kafka carries the event stream for real-time observability and integration.
 *
 * @see PaymentEventPublisher
 * @see KafkaPaymentEventPublisher
 */
public record PaymentEvent(
        EventType eventType,
        String transactionId,
        String endToEndId,
        BigDecimal amount,
        String currency,
        String rejectReasonCode,
        Instant occurredAt
) {

    /**
     * Classifies what happened to the payment at this point in its lifecycle.
     *
     * <p>Inbound events describe credit transfers arriving from FedNow/RTP;
     * outbound events describe credit transfers initiated by this institution.
     */
    public enum EventType {

        /**
         * Inbound credit transfer accepted by the core banking system.
         * Funds have been credited to the creditor's Shadow Ledger balance.
         */
        INBOUND_CREDIT_APPLIED,

        /**
         * Inbound credit transfer rejected by the core banking system.
         * No credit was applied; a RJCT pacs.002 was returned to FedNow.
         */
        INBOUND_PAYMENT_REJECTED,

        /**
         * Inbound credit transfer queued for deferred processing during a core
         * maintenance window. Provisional ACSP credit was applied; the core will
         * confirm when it returns online.
         */
        INBOUND_QUEUED_FOR_BRIDGE,

        /**
         * Outbound credit transfer confirmed by FedNow (ACSC).
         * Funds have been settled and the saga is COMPLETED.
         */
        OUTBOUND_PAYMENT_COMPLETED,

        /**
         * Outbound credit transfer rejected — either by a local insufficient-funds
         * check or by a downstream RJCT from FedNow. Any reserved debit has been
         * reversed in the Shadow Ledger.
         */
        OUTBOUND_PAYMENT_REJECTED,

        /**
         * Outbound credit transfer provisionally accepted by FedNow (ACSP).
         * The debit remains reserved; the reconciliation service will advance
         * the saga to COMPLETED on final confirmation.
         */
        OUTBOUND_PAYMENT_PENDING
    }
}
