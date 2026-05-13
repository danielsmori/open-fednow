package io.openfednow.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.acl.core.SyncAsyncBridge;
import io.openfednow.events.PaymentEvent;
import io.openfednow.events.PaymentEvent.EventType;
import io.openfednow.events.PaymentEventPublisher;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import io.openfednow.processing.idempotency.IdempotencyService;
import io.openfednow.processing.saga.PaymentSaga;
import io.openfednow.processing.saga.SagaOrchestrator;
import io.openfednow.shadowledger.AvailabilityBridge;
import io.openfednow.shadowledger.ShadowLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Layer 1 — Message Router
 *
 * <p>Routes validated ISO 20022 messages between the FedNow Service and the
 * Anti-Corruption Layer (Layer 2). Applies idempotency checks and bridge-mode
 * detection before forwarding to the core banking adapter.
 *
 * <p>Message flow:
 * <pre>
 *   FedNow → FedNowGateway → MessageRouter → SyncAsyncBridge → CoreBankingAdapter
 *   FedNow ← FedNowGateway ← MessageRouter ← SyncAsyncBridge ← CoreBankingAdapter
 *
 *   Bridge mode (core offline):
 *   FedNow → FedNowGateway → MessageRouter → AvailabilityBridge (queue) → ACSP
 * </pre>
 */
@Component
public class MessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);

    private final FedNowClient fedNowClient;
    private final CoreBankingAdapter coreBankingAdapter;
    private final IdempotencyService idempotencyService;
    private final AvailabilityBridge availabilityBridge;
    private final SyncAsyncBridge syncAsyncBridge;
    private final ObjectMapper objectMapper;
    private final ShadowLedger shadowLedger;
    private final SagaOrchestrator sagaOrchestrator;
    private final PaymentEventPublisher eventPublisher;

    public MessageRouter(FedNowClient fedNowClient,
                         CoreBankingAdapter coreBankingAdapter,
                         IdempotencyService idempotencyService,
                         AvailabilityBridge availabilityBridge,
                         SyncAsyncBridge syncAsyncBridge,
                         ObjectMapper objectMapper,
                         ShadowLedger shadowLedger,
                         SagaOrchestrator sagaOrchestrator,
                         PaymentEventPublisher eventPublisher) {
        this.fedNowClient = fedNowClient;
        this.coreBankingAdapter = coreBankingAdapter;
        this.idempotencyService = idempotencyService;
        this.availabilityBridge = availabilityBridge;
        this.syncAsyncBridge = syncAsyncBridge;
        this.objectMapper = objectMapper;
        this.shadowLedger = shadowLedger;
        this.sagaOrchestrator = sagaOrchestrator;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Routes an inbound pacs.008 credit transfer from FedNow to the
     * Anti-Corruption Layer for processing against the core banking system.
     *
     * <p>Processing order:
     * <ol>
     *   <li>Set MDC correlation fields for end-to-end log tracing</li>
     *   <li>Check idempotency — return the cached pacs.002 if this is a duplicate</li>
     *   <li>If in bridge mode (core offline) — queue the transaction for replay
     *       and return an ACSP provisional acceptance</li>
     *   <li>Submit to the core via {@link SyncAsyncBridge} — returns ACSC, RJCT,
     *       or ACSP depending on core timing</li>
     *   <li>Record the outcome in the idempotency service</li>
     * </ol>
     *
     * @param message validated pacs.008.001.08 message from FedNow
     * @return pacs.002 status report (ACSC = accepted, RJCT = rejected, ACSP = provisional)
     */
    public ResponseEntity<Pacs002Message> routeInbound(Pacs008Message message) {
        MDC.put(CorrelationFilter.MDC_END_TO_END_ID, message.getEndToEndId());
        MDC.put(CorrelationFilter.MDC_TRANSACTION_ID, message.getTransactionId());

        log.info("Inbound credit transfer received amount={} currency={}",
                message.getInterbankSettlementAmount(),
                message.getInterbankSettlementCurrency());

        // Step 1 — idempotency check
        Optional<Pacs002Message> cached = idempotencyService.checkDuplicate(message.getEndToEndId());
        if (cached.isPresent()) {
            log.info("Duplicate inbound payment suppressed e2e={}", message.getEndToEndId());
            return ResponseEntity.ok(cached.get());
        }

        // Step 2 — initiate saga (durable record of this payment's lifecycle)
        PaymentSaga saga = sagaOrchestrator.initiate(message);

        Pacs002Message response;

        // Step 3 — bridge mode: core offline, queue for replay
        if (availabilityBridge.isInBridgeMode()) {
            log.info("Bridge mode active — queuing inbound payment e2e={}", message.getEndToEndId());
            // Apply provisional credit: funds arrive regardless of core availability.
            // The reconciliation pass will confirm core_confirmed when the core comes back.
            shadowLedger.applyCredit(
                    message.getCreditorAccountNumber(),
                    message.getInterbankSettlementAmount(),
                    message.getTransactionId());
            sagaOrchestrator.advance(saga, PaymentSaga.SagaState.FUNDS_RESERVED);

            String payload = serializeMessage(message);
            availabilityBridge.queueForCoreProcessing(message.getEndToEndId(), payload);
            response = Pacs002Message.builder()
                    .originalEndToEndId(message.getEndToEndId())
                    .originalTransactionId(message.getTransactionId())
                    .transactionStatus(Pacs002Message.TransactionStatus.ACSP)
                    .creationDateTime(OffsetDateTime.now())
                    .build();
            eventPublisher.publish(event(message, EventType.INBOUND_QUEUED_FOR_BRIDGE, null));
        } else {
            // Step 3 online — submit with sync/async bridge
            response = syncAsyncBridge.submitWithTimeout(message, coreBankingAdapter);

            if (response.getTransactionStatus() != Pacs002Message.TransactionStatus.RJCT) {
                // ACSC or ACSP: credit applied to shadow ledger
                shadowLedger.applyCredit(
                        message.getCreditorAccountNumber(),
                        message.getInterbankSettlementAmount(),
                        message.getTransactionId());
                sagaOrchestrator.advance(saga, PaymentSaga.SagaState.FUNDS_RESERVED);
                sagaOrchestrator.advance(saga, PaymentSaga.SagaState.CORE_SUBMITTED);
                if (response.getTransactionStatus() == Pacs002Message.TransactionStatus.ACSC) {
                    sagaOrchestrator.advance(saga, PaymentSaga.SagaState.FEDNOW_CONFIRMED);
                    sagaOrchestrator.advance(saga, PaymentSaga.SagaState.COMPLETED);
                }
                // ACSP: remain at CORE_SUBMITTED; reconciliation advances to COMPLETED
                eventPublisher.publish(event(message, EventType.INBOUND_CREDIT_APPLIED, null));
            } else {
                // RJCT: core declined the transfer — no credit applied, compensate saga
                sagaOrchestrator.compensate(saga.getSagaId(), response.getRejectReasonCode());
                eventPublisher.publish(event(message, EventType.INBOUND_PAYMENT_REJECTED,
                        response.getRejectReasonCode()));
            }
        }

        log.info("Inbound credit transfer status={} rejectCode={}",
                response.getTransactionStatus(), response.getRejectReasonCode());

        // Step 4 — record outcome for future idempotency checks
        idempotencyService.recordOutcome(message.getEndToEndId(), response);

        return ResponseEntity.ok(response);
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    private PaymentEvent event(Pacs008Message message, EventType type, String rejectReasonCode) {
        return new PaymentEvent(
                type,
                message.getTransactionId(),
                message.getEndToEndId(),
                message.getInterbankSettlementAmount(),
                message.getInterbankSettlementCurrency(),
                rejectReasonCode,
                Instant.now());
    }

    private String serializeMessage(Pacs008Message message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize pacs.008 for bridge queue e2e={}", message.getEndToEndId(), e);
            return message.getEndToEndId(); // fallback: use the e2e ID as a minimal payload
        }
    }

    /**
     * Routes an outbound pacs.008 credit transfer to the FedNow Service.
     *
     * <p>Processing order:
     * <ol>
     *   <li>Idempotency check — return cached pacs.002 if this endToEndId was already sent</li>
     *   <li>Sufficient-funds check — return RJCT AM04 immediately if the Shadow Ledger
     *       balance cannot cover the amount (fail fast, no saga created)</li>
     *   <li>Initiate saga</li>
     *   <li>Apply debit (reserve funds) in the Shadow Ledger → advance saga to FUNDS_RESERVED</li>
     *   <li>Submit to FedNow via {@link FedNowClient}</li>
     *   <li>On ACSC: advance saga to COMPLETED</li>
     *   <li>On RJCT: compensate saga — reverses the debit via Shadow Ledger</li>
     *   <li>On ACSP: leave saga at CORE_SUBMITTED — reconciliation advances to COMPLETED</li>
     *   <li>Record outcome for idempotency</li>
     * </ol>
     *
     * @param message pacs.008.001.08 assembled by the ACL
     * @return pacs.002 status report returned by FedNow (or synthetic RJCT on infrastructure error)
     */
    public ResponseEntity<Pacs002Message> routeOutbound(Pacs008Message message) {
        MDC.put(CorrelationFilter.MDC_END_TO_END_ID, message.getEndToEndId());
        MDC.put(CorrelationFilter.MDC_TRANSACTION_ID, message.getTransactionId());

        log.info("Outbound credit transfer received amount={} currency={}",
                message.getInterbankSettlementAmount(),
                message.getInterbankSettlementCurrency());

        // Step 1 — idempotency check
        Optional<Pacs002Message> cached = idempotencyService.checkDuplicate(message.getEndToEndId());
        if (cached.isPresent()) {
            log.info("Duplicate outbound payment suppressed e2e={}", message.getEndToEndId());
            return ResponseEntity.ok(cached.get());
        }

        // Step 2 — sufficient-funds check (fail fast before saga is created)
        java.math.BigDecimal available = shadowLedger.getAvailableBalance(message.getDebtorAccountNumber());
        if (available.compareTo(message.getInterbankSettlementAmount()) < 0) {
            log.info("Outbound payment rejected: insufficient funds account={} available={} requested={}",
                    message.getDebtorAccountNumber(), available, message.getInterbankSettlementAmount());
            Pacs002Message insufficientFunds = Pacs002Message.rejected(
                    message.getEndToEndId(), message.getTransactionId(),
                    "AM04", "Insufficient funds in debtor Shadow Ledger balance");
            idempotencyService.recordOutcome(message.getEndToEndId(), insufficientFunds);
            eventPublisher.publish(event(message, EventType.OUTBOUND_PAYMENT_REJECTED, "AM04"));
            return ResponseEntity.ok(insufficientFunds);
        }

        // Step 3 — initiate saga
        PaymentSaga saga = sagaOrchestrator.initiate(message);

        // Step 4 — reserve funds: debit debtor account in Shadow Ledger
        shadowLedger.applyDebit(
                message.getDebtorAccountNumber(),
                message.getInterbankSettlementAmount(),
                message.getTransactionId());
        sagaOrchestrator.advance(saga, PaymentSaga.SagaState.FUNDS_RESERVED);

        // Step 5 — submit to FedNow
        Pacs002Message response;
        try {
            response = fedNowClient.submitCreditTransfer(message);
            sagaOrchestrator.advance(saga, PaymentSaga.SagaState.CORE_SUBMITTED);
        } catch (Exception e) {
            log.error("FedNow submission failed, compensating sagaId={}", saga.getSagaId(), e);
            sagaOrchestrator.compensate(saga.getSagaId(), "NARR");
            response = Pacs002Message.rejected(
                    message.getEndToEndId(), message.getTransactionId(),
                    "NARR", "FedNow submission error — debit reversed");
            idempotencyService.recordOutcome(message.getEndToEndId(), response);
            return ResponseEntity.ok(response);
        }

        // Step 6 — handle FedNow response
        if (response.getTransactionStatus() == Pacs002Message.TransactionStatus.RJCT) {
            log.info("FedNow rejected outbound payment, compensating sagaId={} reason={}",
                    saga.getSagaId(), response.getRejectReasonCode());
            sagaOrchestrator.compensate(saga.getSagaId(), response.getRejectReasonCode());
            eventPublisher.publish(event(message, EventType.OUTBOUND_PAYMENT_REJECTED,
                    response.getRejectReasonCode()));
        } else if (response.getTransactionStatus() == Pacs002Message.TransactionStatus.ACSC) {
            sagaOrchestrator.advance(saga, PaymentSaga.SagaState.FEDNOW_CONFIRMED);
            sagaOrchestrator.advance(saga, PaymentSaga.SagaState.COMPLETED);
            eventPublisher.publish(event(message, EventType.OUTBOUND_PAYMENT_COMPLETED, null));
        } else {
            // ACSP: leave at CORE_SUBMITTED — reconciliation advances to COMPLETED
            eventPublisher.publish(event(message, EventType.OUTBOUND_PAYMENT_PENDING, null));
        }

        log.info("Outbound credit transfer status={} rejectCode={}",
                response.getTransactionStatus(), response.getRejectReasonCode());

        // Step 7 — record outcome for idempotency
        idempotencyService.recordOutcome(message.getEndToEndId(), response);

        return ResponseEntity.ok(response);
    }
}
