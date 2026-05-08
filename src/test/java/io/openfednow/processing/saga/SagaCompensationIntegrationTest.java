package io.openfednow.processing.saga;

import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import io.openfednow.iso20022.Pacs008Message;
import io.openfednow.shadowledger.ShadowLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests for {@link SagaOrchestrator} and the compensation flow — issue #15.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Saga initiation creates a durable {@code INITIATED} record in {@code saga_state}</li>
 *   <li>Sagas can be resumed after a simulated middleware restart</li>
 *   <li>Compensation reverses the Shadow Ledger debit and transitions to {@code FAILED}</li>
 *   <li>Compensation from {@code INITIATED} (no debit yet) does not throw and still
 *       reaches {@code FAILED}</li>
 *   <li>A resumed saga can advance forward through to {@code COMPLETED}</li>
 *   <li>Compensation creates a {@code REVERSAL} row in {@code shadow_ledger_transaction_log}</li>
 *   <li>Compensating an already-failed saga is a safe no-op (guard condition)</li>
 * </ul>
 */
class SagaCompensationIntegrationTest extends AbstractInfrastructureIntegrationTest {

    @Autowired private SagaOrchestrator orchestrator;
    @Autowired private ShadowLedger shadowLedger;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
        jdbc.update("DELETE FROM saga_state");
        redis.delete(redis.keys("balance:*"));
    }

    // ── Saga initiation ───────────────────────────────────────────────────────

    @Test
    void sagaInitiateCreatesDbRecord() {
        PaymentSaga saga = orchestrator.initiate(message("TXN-INIT-001", "E2E-INIT-001"));

        String state = jdbc.queryForObject(
                "SELECT state FROM saga_state WHERE saga_id = ?",
                String.class, saga.getSagaId());
        assertThat(state).isEqualTo("INITIATED");
    }

    @Test
    void sagaInitiateRecordsTransactionAndEndToEndIds() {
        PaymentSaga saga = orchestrator.initiate(message("TXN-INIT-002", "E2E-INIT-002"));

        String txnId = jdbc.queryForObject(
                "SELECT transaction_id FROM saga_state WHERE saga_id = ?",
                String.class, saga.getSagaId());
        String e2eId = jdbc.queryForObject(
                "SELECT end_to_end_id FROM saga_state WHERE saga_id = ?",
                String.class, saga.getSagaId());
        assertThat(txnId).isEqualTo("TXN-INIT-002");
        assertThat(e2eId).isEqualTo("E2E-INIT-002");
    }

    // ── Saga resume ───────────────────────────────────────────────────────────

    @Test
    void sagaCanBeResumedFromDb() {
        PaymentSaga original = orchestrator.initiate(message("TXN-RESUME-001", "E2E-RESUME-001"));
        orchestrator.advance(original, PaymentSaga.SagaState.FUNDS_RESERVED);

        PaymentSaga resumed = orchestrator.resume(original.getSagaId());

        assertThat(resumed.getSagaId()).isEqualTo(original.getSagaId());
        assertThat(resumed.getTransactionId()).isEqualTo("TXN-RESUME-001");
        assertThat(resumed.getState()).isEqualTo(PaymentSaga.SagaState.FUNDS_RESERVED);
    }

    @Test
    void resumedSagaCanContinueToCompletion() {
        PaymentSaga saga = orchestrator.initiate(message("TXN-COMPLETE-001", "E2E-COMPLETE-001"));
        orchestrator.advance(saga, PaymentSaga.SagaState.FUNDS_RESERVED);
        orchestrator.advance(saga, PaymentSaga.SagaState.CORE_SUBMITTED);

        // Simulate middleware restart: resume from persisted state
        PaymentSaga resumed = orchestrator.resume(saga.getSagaId());
        assertThat(resumed.getState()).isEqualTo(PaymentSaga.SagaState.CORE_SUBMITTED);

        orchestrator.advance(resumed, PaymentSaga.SagaState.FEDNOW_CONFIRMED);
        orchestrator.advance(resumed, PaymentSaga.SagaState.COMPLETED);

        String finalState = jdbc.queryForObject(
                "SELECT state FROM saga_state WHERE saga_id = ?",
                String.class, saga.getSagaId());
        assertThat(finalState).isEqualTo("COMPLETED");
    }

    // ── Compensation: funds reserved ──────────────────────────────────────────

    @Test
    void compensationReversesDebitAndReachesFailedState() {
        redis.opsForValue().set("balance:ACC-COMP-001", "10000"); // $100.00
        PaymentSaga saga = orchestrator.initiate(message("TXN-COMP-001", "E2E-COMP-001"));
        shadowLedger.applyDebit("ACC-COMP-001", new BigDecimal("100.00"), "TXN-COMP-001");
        orchestrator.advance(saga, PaymentSaga.SagaState.FUNDS_RESERVED);

        orchestrator.compensate(saga.getSagaId(), "AM04");

        // Balance must be restored to the original $100.00
        String balanceCents = redis.opsForValue().get("balance:ACC-COMP-001");
        assertThat(balanceCents).isEqualTo("10000");

        // Saga must be in terminal FAILED state
        String state = jdbc.queryForObject(
                "SELECT state FROM saga_state WHERE saga_id = ?",
                String.class, saga.getSagaId());
        assertThat(state).isEqualTo("FAILED");
    }

    @Test
    void compensationRecordsReturnReasonCodeInDb() {
        redis.opsForValue().set("balance:ACC-COMP-002", "5000"); // $50.00
        PaymentSaga saga = orchestrator.initiate(message("TXN-COMP-002", "E2E-COMP-002"));
        shadowLedger.applyDebit("ACC-COMP-002", new BigDecimal("50.00"), "TXN-COMP-002");
        orchestrator.advance(saga, PaymentSaga.SagaState.FUNDS_RESERVED);

        orchestrator.compensate(saga.getSagaId(), "AC04");

        String reasonCode = jdbc.queryForObject(
                "SELECT return_reason_code FROM saga_state WHERE saga_id = ?",
                String.class, saga.getSagaId());
        assertThat(reasonCode).isEqualTo("AC04");
    }

    // ── Compensation: no debit (INITIATED state) ──────────────────────────────

    @Test
    void compensationFromInitiatedDoesNotThrow() {
        PaymentSaga saga = orchestrator.initiate(message("TXN-NOFUND-001", "E2E-NOFUND-001"));

        assertThatCode(() -> orchestrator.compensate(saga.getSagaId(), "NARR"))
                .doesNotThrowAnyException();
    }

    @Test
    void compensationFromInitiatedReachesFailedWithoutReversal() {
        PaymentSaga saga = orchestrator.initiate(message("TXN-NOFUND-002", "E2E-NOFUND-002"));
        orchestrator.compensate(saga.getSagaId(), "NARR");

        String state = jdbc.queryForObject(
                "SELECT state FROM saga_state WHERE saga_id = ?",
                String.class, saga.getSagaId());
        assertThat(state).isEqualTo("FAILED");

        // No REVERSAL row should have been inserted (no debit was ever applied)
        Integer reversals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log " +
                "WHERE transaction_id = 'TXN-NOFUND-002' AND transaction_type = 'REVERSAL'",
                Integer.class);
        assertThat(reversals).isEqualTo(0);
    }

    // ── Reversal audit log ────────────────────────────────────────────────────

    @Test
    void reversalAppearsInAuditLog() {
        redis.opsForValue().set("balance:ACC-AUDIT-001", "20000"); // $200.00
        PaymentSaga saga = orchestrator.initiate(message("TXN-AUDIT-001", "E2E-AUDIT-001"));
        shadowLedger.applyDebit("ACC-AUDIT-001", new BigDecimal("200.00"), "TXN-AUDIT-001");
        orchestrator.advance(saga, PaymentSaga.SagaState.FUNDS_RESERVED);

        orchestrator.compensate(saga.getSagaId(), "AM04");

        Integer debitCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log " +
                "WHERE transaction_id = 'TXN-AUDIT-001' AND transaction_type = 'DEBIT'",
                Integer.class);
        Integer reversalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log " +
                "WHERE transaction_id = 'TXN-AUDIT-001' AND transaction_type = 'REVERSAL'",
                Integer.class);
        assertThat(debitCount).isEqualTo(1);
        assertThat(reversalCount).isEqualTo(1);
    }

    // ── Guard: already-terminal saga ─────────────────────────────────────────

    @Test
    void compensatingAlreadyFailedSagaIsNoOp() {
        PaymentSaga saga = orchestrator.initiate(message("TXN-GUARD-001", "E2E-GUARD-001"));
        orchestrator.compensate(saga.getSagaId(), "NARR"); // → FAILED

        // Second compensate must not throw and must leave state unchanged
        assertThatCode(() -> orchestrator.compensate(saga.getSagaId(), "NARR"))
                .doesNotThrowAnyException();

        String state = jdbc.queryForObject(
                "SELECT state FROM saga_state WHERE saga_id = ?",
                String.class, saga.getSagaId());
        assertThat(state).isEqualTo("FAILED");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static Pacs008Message message(String transactionId, String endToEndId) {
        return Pacs008Message.builder()
                .messageId("MSG-" + transactionId)
                .endToEndId(endToEndId)
                .transactionId(transactionId)
                .interbankSettlementAmount(new BigDecimal("100.00"))
                .interbankSettlementCurrency("USD")
                .creditorAccountNumber("ACC-SAGA-001")
                .build();
    }
}
