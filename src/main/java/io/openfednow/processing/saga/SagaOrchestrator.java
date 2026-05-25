package io.openfednow.processing.saga;

import io.openfednow.gateway.Rail;
import io.openfednow.iso20022.Pacs008Message;
import io.openfednow.shadowledger.ShadowLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
     * @param sourceRail the rail (FedNow or RTP) the message arrived on; persisted so that
     *                   asynchronous response paths can dispatch through the correct gateway
     * @return the initiated PaymentSaga instance
     */
    public PaymentSaga initiate(Pacs008Message message, Rail sourceRail) {
        String sagaId = "SAGA-" + UUID.randomUUID();
        PaymentSaga saga = new PaymentSaga(sagaId, message.getTransactionId(), sourceRail);

        jdbc.update("""
                INSERT INTO saga_state
                    (saga_id, transaction_id, end_to_end_id, state, source_rail)
                VALUES (?, ?, ?, ?, ?)
                """,
                sagaId,
                message.getTransactionId(),
                message.getEndToEndId(),
                saga.getState().name(),
                sourceRail.name());

        log.info("Saga initiated sagaId={} transactionId={} e2e={} sourceRail={}",
                sagaId, message.getTransactionId(), message.getEndToEndId(), sourceRail);
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
                "SELECT saga_id, transaction_id, source_rail, state FROM saga_state WHERE saga_id = ?",
                (rs, rowNum) -> new PaymentSaga(
                        rs.getString("saga_id"),
                        rs.getString("transaction_id"),
                        Rail.valueOf(rs.getString("source_rail")),
                        PaymentSaga.SagaState.valueOf(rs.getString("state"))),
                sagaId);
    }

    /**
     * Returns saga IDs that are still in a forward-progress state and have been
     * running longer than the supplied timeout window.
     *
     * <p>States considered <em>timed out</em> are {@code INITIATED},
     * {@code FUNDS_RESERVED}, and {@code CORE_SUBMITTED}. The terminal states
     * ({@code COMPLETED}, {@code FAILED}) are excluded as expected;
     * {@code COMPENSATING} is excluded because compensation is already in
     * progress; {@code FEDNOW_CONFIRMED} is excluded because settlement was
     * confirmed and the only remaining work is the bookkeeping transition to
     * {@code COMPLETED} (handled by {@code SagaRecoveryService} on restart).
     *
     * <p>Filtered against {@code created_at}, matching the timeout semantics
     * called out in issue #37 — "a saga that stalls indefinitely". Backed by
     * {@code idx_saga_state} so cost scales with in-flight sagas, not table size.
     *
     * @param timeoutSeconds how long a saga may stay in a forward-progress state
     *                       before it is treated as stuck. Must be positive.
     */
    public java.util.List<String> findTimedOutSagaIds(int timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }
        java.sql.Timestamp threshold = java.sql.Timestamp.from(
                java.time.Instant.now().minusSeconds(timeoutSeconds));
        return jdbc.queryForList(
                """
                SELECT saga_id FROM saga_state
                WHERE state IN ('INITIATED', 'FUNDS_RESERVED', 'CORE_SUBMITTED')
                  AND created_at < ?
                ORDER BY created_at ASC
                """,
                String.class,
                threshold);
    }

    /**
     * Returns all sagas that have not yet reached a terminal state, ordered oldest first.
     *
     * <p>Used by admin endpoints and operational dashboards to surface in-flight
     * payment work. The terminal states ({@code COMPLETED}, {@code FAILED}) are
     * excluded so the result reflects what an operator might need to act on.
     */
    public java.util.List<SagaSnapshot> listInflight() {
        return jdbc.query(
                """
                SELECT saga_id, transaction_id, end_to_end_id, state, source_rail,
                       return_reason_code, failure_description, created_at, updated_at
                FROM saga_state
                WHERE state NOT IN ('COMPLETED', 'FAILED')
                ORDER BY created_at ASC
                """,
                this::mapSnapshot);
    }

    /**
     * Looks up the full saga snapshot for a given ISO 20022 transaction ID.
     *
     * @return the snapshot, or {@link java.util.Optional#empty()} if no saga exists
     *         for that transaction
     */
    public java.util.Optional<SagaSnapshot> findByTransactionId(String transactionId) {
        return jdbc.query(
                """
                SELECT saga_id, transaction_id, end_to_end_id, state, source_rail,
                       return_reason_code, failure_description, created_at, updated_at
                FROM saga_state
                WHERE transaction_id = ?
                """,
                this::mapSnapshot,
                transactionId).stream().findFirst();
    }

    private SagaSnapshot mapSnapshot(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
        java.sql.Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new SagaSnapshot(
                rs.getString("saga_id"),
                rs.getString("transaction_id"),
                rs.getString("end_to_end_id"),
                PaymentSaga.SagaState.valueOf(rs.getString("state")),
                Rail.valueOf(rs.getString("source_rail")),
                rs.getString("return_reason_code"),
                rs.getString("failure_description"),
                createdAt != null ? createdAt.toInstant() : null,
                updatedAt != null ? updatedAt.toInstant() : null);
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
    @Transactional(rollbackFor = Exception.class)
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
     * Cancels an inbound payment saga in response to a camt.056 cancellation request.
     *
     * <p>Mirrors {@link #compensate(String, String)} for the inbound case — reverses the
     * Shadow Ledger <em>credit</em> (since inbound payments apply credits, not debits)
     * if funds were already reserved, and advances the saga to {@code FAILED}.
     *
     * <p>The caller is responsible for verifying that the saga is in a cancellable state
     * before invoking. The guard here only prevents re-cancellation of an already
     * terminal saga.
     *
     * @param sagaId      identifier of the saga to cancel
     * @param reasonCode  ISO 20022 reason code from the camt.056 (e.g., DUPL, FRAUD, CUST)
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelInboundSaga(String sagaId, String reasonCode) {
        PaymentSaga saga = resume(sagaId);

        if (saga.getState() == PaymentSaga.SagaState.FAILED
                || saga.getState() == PaymentSaga.SagaState.COMPLETED) {
            log.warn("Inbound saga already in terminal state — cancellation skipped sagaId={} state={}",
                    sagaId, saga.getState());
            return;
        }

        log.info("Inbound saga cancellation starting sagaId={} fromState={} reason={}",
                sagaId, saga.getState(), reasonCode);

        // Reverse the Shadow Ledger credit if it was applied. INITIATED means no funds
        // were credited yet, so no reversal is required — the saga simply terminates.
        PaymentSaga.SagaState current = saga.getState();
        boolean creditWasApplied = current == PaymentSaga.SagaState.FUNDS_RESERVED
                || current == PaymentSaga.SagaState.CORE_SUBMITTED
                || current == PaymentSaga.SagaState.FEDNOW_CONFIRMED;
        if (creditWasApplied) {
            try {
                shadowLedger.reverseCredit(saga.getTransactionId());
                log.info("Shadow Ledger credit reversed sagaId={} transactionId={}",
                        sagaId, saga.getTransactionId());
            } catch (Exception e) {
                log.error("Shadow Ledger credit reversal failed sagaId={}", sagaId, e);
            }
        }

        // COMPENSATING is allowed as a passthrough — recovery already moved us here.
        if (current != PaymentSaga.SagaState.COMPENSATING) {
            saga.compensate(saga.getState(), reasonCode);
            persistState(saga, reasonCode, "Cancelled via camt.056");
        }
        saga.advance(PaymentSaga.SagaState.FAILED);
        persistState(saga, reasonCode, "Cancelled via camt.056");

        log.info("Inbound saga cancellation complete sagaId={}", sagaId);
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
