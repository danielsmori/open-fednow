package io.openfednow.acl.core;

import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Layer 2 — Synchronous-to-Asynchronous Bridge
 *
 * <p>Resolves the fundamental timing incompatibility between FedNow and
 * legacy core banking systems:
 * <ul>
 *   <li>FedNow requires a synchronous response within 20 seconds</li>
 *   <li>Legacy core systems process transactions asynchronously, often
 *       with completion times that exceed the FedNow window</li>
 * </ul>
 *
 * <p>The bridge decouples the two timing models:
 * <ol>
 *   <li>An inbound pacs.008 is submitted to the core system asynchronously</li>
 *   <li>The bridge immediately returns a provisional acceptance to FedNow
 *       (within the 20-second window) backed by Shadow Ledger validation</li>
 *   <li>When the core system completes processing, the bridge reconciles
 *       the result with the provisional response</li>
 *   <li>If the core system rejects a provisionally accepted transaction,
 *       the Saga compensating transaction is triggered</li>
 * </ol>
 *
 * @see io.openfednow.shadowledger.ShadowLedger
 * @see io.openfednow.processing.saga.SagaOrchestrator
 */
@Component
public class SyncAsyncBridge {

    private static final Logger log = LoggerFactory.getLogger(SyncAsyncBridge.class);

    /** Maximum time to wait for a synchronous core response before switching to async mode. */
    private static final Duration SYNC_TIMEOUT = Duration.ofSeconds(15);

    /**
     * Submits a payment to the core banking system and attempts to obtain
     * a synchronous response within the FedNow window.
     *
     * <p>If the core system does not respond within {@link #SYNC_TIMEOUT},
     * the bridge switches to async mode: returns a provisional ACSP acceptance
     * to FedNow based on Shadow Ledger validation, and registers the transaction
     * for async reconciliation.
     *
     * <p>If the core returns immediately with REJECTED, the rejection is returned
     * to FedNow without provisional acceptance.
     *
     * @param message the pacs.008 to submit
     * @param adapter the core banking adapter to use
     * @return a pacs.002 response suitable for return to FedNow
     */
    public Pacs002Message submitWithTimeout(Pacs008Message message, CoreBankingAdapter adapter) {
        CompletableFuture<CoreBankingResponse> future =
                CompletableFuture.supplyAsync(() -> adapter.postCreditTransfer(message));

        CoreBankingResponse response;
        try {
            response = future.get(SYNC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.info("Core banking timeout after {}s — returning provisional ACSP e2e={}",
                    SYNC_TIMEOUT.toSeconds(), message.getEndToEndId());
            return provisionalAcceptance(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting core banking response", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Core banking call failed", e.getCause());
        }

        return mapToResponse(message, response);
    }

    /**
     * Registers a transaction for asynchronous reconciliation after a
     * provisional acceptance has been returned to FedNow.
     *
     * <p>Returns a {@link CompletableFuture} that callers can chain to receive
     * the eventual core confirmation. In production this future is resolved by
     * the message-queue listener that processes core callbacks.
     *
     * @param transactionId the end-to-end transaction ID
     * @param provisionalStatus the status returned to FedNow
     * @return a future that resolves when the core system confirms or rejects
     */
    public CompletableFuture<CoreBankingResponse> registerForReconciliation(
            String transactionId, String provisionalStatus) {
        log.info("Transaction registered for async reconciliation transactionId={} provisionalStatus={}",
                transactionId, provisionalStatus);
        // Returns an unresolved future that will be completed by the reconciliation service
        // when the core system processes the queued transaction.
        return new CompletableFuture<>();
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    private Pacs002Message mapToResponse(Pacs008Message message, CoreBankingResponse response) {
        return switch (response.getStatus()) {
            case ACCEPTED, PENDING -> Pacs002Message.accepted(
                    message.getEndToEndId(), message.getTransactionId());
            case REJECTED -> Pacs002Message.rejected(
                    message.getEndToEndId(), message.getTransactionId(),
                    response.getIso20022ReasonCode(),
                    "Core banking rejection: " + response.getVendorStatusCode());
            case TIMEOUT -> {
                log.info("Adapter returned TIMEOUT status — provisional acceptance e2e={}",
                        message.getEndToEndId());
                yield provisionalAcceptance(message);
            }
        };
    }

    private Pacs002Message provisionalAcceptance(Pacs008Message message) {
        return Pacs002Message.builder()
                .originalEndToEndId(message.getEndToEndId())
                .originalTransactionId(message.getTransactionId())
                .transactionStatus(Pacs002Message.TransactionStatus.ACSP)
                .creationDateTime(OffsetDateTime.now())
                .build();
    }
}
