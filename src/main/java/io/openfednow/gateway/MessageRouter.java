package io.openfednow.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.acl.core.SyncAsyncBridge;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import io.openfednow.processing.idempotency.IdempotencyService;
import io.openfednow.shadowledger.AvailabilityBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

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

    public MessageRouter(FedNowClient fedNowClient,
                         CoreBankingAdapter coreBankingAdapter,
                         IdempotencyService idempotencyService,
                         AvailabilityBridge availabilityBridge,
                         SyncAsyncBridge syncAsyncBridge,
                         ObjectMapper objectMapper) {
        this.fedNowClient = fedNowClient;
        this.coreBankingAdapter = coreBankingAdapter;
        this.idempotencyService = idempotencyService;
        this.availabilityBridge = availabilityBridge;
        this.syncAsyncBridge = syncAsyncBridge;
        this.objectMapper = objectMapper;
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

        Pacs002Message response;

        // Step 2 — bridge mode: core offline, queue for replay
        if (availabilityBridge.isInBridgeMode()) {
            log.info("Bridge mode active — queuing inbound payment e2e={}", message.getEndToEndId());
            String payload = serializeMessage(message);
            availabilityBridge.queueForCoreProcessing(message.getEndToEndId(), payload);
            response = Pacs002Message.builder()
                    .originalEndToEndId(message.getEndToEndId())
                    .originalTransactionId(message.getTransactionId())
                    .transactionStatus(Pacs002Message.TransactionStatus.ACSP)
                    .creationDateTime(OffsetDateTime.now())
                    .build();
        } else {
            // Step 3 — online mode: submit with sync/async bridge
            response = syncAsyncBridge.submitWithTimeout(message, coreBankingAdapter);
        }

        log.info("Inbound credit transfer status={} rejectCode={}",
                response.getTransactionStatus(), response.getRejectReasonCode());

        // Step 4 — record outcome for future idempotency checks
        idempotencyService.recordOutcome(message.getEndToEndId(), response);

        return ResponseEntity.ok(response);
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

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
     * Populates MDC with the ISO 20022 identifiers from the message so that
     * all log lines emitted during this request carry the payment context.
     *
     * @param message pacs.008.001.08 message assembled by the ACL
     * @return pacs.002 status report returned by FedNow
     */
    public ResponseEntity<Pacs002Message> routeOutbound(Pacs008Message message) {
        MDC.put(CorrelationFilter.MDC_END_TO_END_ID, message.getEndToEndId());
        MDC.put(CorrelationFilter.MDC_TRANSACTION_ID, message.getTransactionId());

        log.info("Routing outbound credit transfer amount={} currency={}",
                message.getInterbankSettlementAmount(),
                message.getInterbankSettlementCurrency());

        Pacs002Message response = fedNowClient.submitCreditTransfer(message);

        log.info("FedNow response status={} rejectCode={}",
                response.getTransactionStatus(),
                response.getRejectReasonCode());

        return ResponseEntity.ok(response);
    }
}
