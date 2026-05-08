package io.openfednow.processing.saga;

import io.openfednow.iso20022.Pacs008Message;
import io.openfednow.shadowledger.ShadowLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Layer 3 — Saga Orchestrator
 *
 * <p>Manages the lifecycle of PaymentSaga instances across all in-flight
 * FedNow transactions. Coordinates the sequence of steps, handles timeouts,
 * and triggers compensation when failures occur.
 *
 * <p>All saga state transitions are persisted to the {@code saga_state}
 * PostgreSQL table before being acted upon, ensuring that a middleware
 * restart can resume any in-flight saga from its last known state.
 */
@Component
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    private final JdbcTemplate jdbc;
    private final ShadowLedger shadowLedger;

    public SagaOrchestrator(JdbcTemplate jdbc, ShadowLedger shadowLedger) {
        this.jdbc = jdbc;
        this.shadowLedger = shadowLedger;
    }

    /**
     * Initiates a new payment saga for an inbound pacs.008 credit transfer.
     *
     * <p>Creates a durable saga record in {@code saga_state} and returns
     * an {@link INITIATED} PaymentSaga. The caller should immediately advance
     * the saga to {@code FUNDS_RESERVED} after reserving funds in the Shadow Ledger.
     *
     * @param message the validated pacs.008 to process
     * @return the initiated PaymentSaga instance
     */
    public PaymentSaga initiate(Pacs008Message message) {
        String sagaId = "SAGA-" + UUID.randomUUID();
        PaymentSaga saga = new PaymentSaga(sagaId, message.getTransactionId());

        jdbc.update("""
                INSERT INTO saga_state
                    (saga_id, transaction_id, end_to_end_id, state)
                VALUES (?, ?, ?, ?)
                """,
                sagaId,
                message.getTransactionId(),
                message.getEndToEndId(),
                saga.getState().name());

        log.info("Saga initiated sagaId={} transactionId={} e2e={}",
                sagaId, message.getTransactionId(), message.getEndToEndId());
        return saga;
    }

    /**
     * Resumes a saga that was interrupted (e.g., by a system restart).
     *
     * <p>Loads the persisted saga state from {@code saga_state} and returns a
     * {@link PaymentSaga} with the last confirmed state, ready to continue from
     * that point.
     *
     * @param sagaId the identifier of the saga to resume
     * @return the resumed PaymentSaga
     * @throws IllegalArgumentException if the saga is not found
     */
    public PaymentSaga resume(String sagaId) {
        return jdbc.queryForObject(
                "SELECT saga_id, transaction_id, state FROM saga_state WHERE saga_id = ?",
                (rs, rowNum) -> new PaymentSaga(
                        rs.getString("saga_id"),
                        rs.getString("transaction_id"),
                        PaymentSaga.SagaState.valueOf(rs.getString("state"))),
                sagaId);
    }

    /**
     * Triggers the compensation sequence for a saga that has failed.
     *
     * <p>Loads the saga, determines which steps have been completed, and
     * reverses them in order:
     * <ol>
     *   <li>If funds were reserved ({@code FUNDS_RESERVED} or beyond):
     *       reverse the Shadow Ledger debit</li>
     *   <li>Updates the saga state to {@code COMPENSATING} then {@code FAILED}</li>
     * </ol>
     *
     * @param sagaId the identifier of the failed saga
     * @param reason ISO 20022 reason code for the failure (e.g. AM04, AC01)
     */
    public void compensate(String sagaId, String reason) {
        PaymentSaga saga = resume(sagaId);

        // Guard: terminal states cannot be compensated again
        if (saga.getState() == PaymentSaga.SagaState.FAILED
                || saga.getState() == PaymentSaga.SagaState.COMPENSATING) {
            log.warn("Saga already in terminal/compensating state — skipping sagaId={} state={}",
                    sagaId, saga.getState());
            return;
        }

        log.info("Saga compensation starting sagaId={} fromState={} reason={}",
                sagaId, saga.getState(), reason);

        // Reverse Shadow Ledger debit if funds were reserved (only in forward-progress states)
        PaymentSaga.SagaState current = saga.getState();
        boolean fundsWereReserved = current == PaymentSaga.SagaState.FUNDS_RESERVED
                || current == PaymentSaga.SagaState.CORE_SUBMITTED
                || current == PaymentSaga.SagaState.FEDNOW_CONFIRMED
                || current == PaymentSaga.SagaState.COMPLETED;
        if (fundsWereReserved) {
            try {
                shadowLedger.reverseDebit(saga.getTransactionId());
                log.info("Shadow Ledger debit reversed sagaId={} transactionId={}",
                        sagaId, saga.getTransactionId());
            } catch (Exception e) {
                log.error("Shadow Ledger reversal failed sagaId={}", sagaId, e);
            }
        }

        // Persist COMPENSATING state
        saga.compensate(saga.getState(), reason);
        persistState(saga, reason, null);

        // Persist terminal FAILED state
        saga.advance(PaymentSaga.SagaState.FAILED);
        persistState(saga, reason, null);

        log.info("Saga compensation complete sagaId={}", sagaId);
    }

    /**
     * Advances a saga's state and persists the transition to the database.
     * Called by processing components after each successful step.
     */
    public void advance(PaymentSaga saga, PaymentSaga.SagaState nextState) {
        saga.advance(nextState);
        persistState(saga, null, null);
        log.debug("Saga advanced sagaId={} state={}", saga.getSagaId(), nextState);
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    private void persistState(PaymentSaga saga, String returnReasonCode, String failureDescription) {
        jdbc.update("""
                UPDATE saga_state
                SET state = ?, return_reason_code = ?, failure_description = ?,
                    updated_at = NOW()
                WHERE saga_id = ?
                """,
                saga.getState().name(),
                returnReasonCode,
                failureDescription,
                saga.getSagaId());
    }
}
