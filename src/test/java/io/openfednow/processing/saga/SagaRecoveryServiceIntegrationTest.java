package io.openfednow.processing.saga;

import io.openfednow.gateway.Rail;
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

/**
 * Integration tests for {@link SagaRecoveryService} — issue #36.
 *
 * <p>Simulates a middleware restart by leaving sagas in non-terminal states
 * in {@code saga_state} and then invoking the recovery path directly. Verifies
 * that each non-terminal state reaches a terminal one (COMPLETED or FAILED)
 * with the correct Shadow Ledger side effects.
 */
class SagaRecoveryServiceIntegrationTest extends AbstractInfrastructureIntegrationTest {

    @Autowired private SagaOrchestrator orchestrator;
    @Autowired private SagaRecoveryService recoveryService;
    @Autowired private ShadowLedger shadowLedger;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
        jdbc.update("DELETE FROM saga_state");
        redis.delete(redis.keys("balance:*"));
    }

    // ── Empty state ──────────────────────────────────────────────────────────

    @Test
    void recoveryWithNoInflightSagasIsNoOp() {
        int recovered = recoveryService.recoverInflightSagas();
        assertThat(recovered).isZero();
    }

    // ── Per-state recovery dispatch ──────────────────────────────────────────

    @Test
    void initiatedSagaIsCompensatedAndReachesFailed() {
        PaymentSaga saga = orchestrator.initiate(
                message("TXN-REC-INIT", "E2E-REC-INIT"), Rail.FEDNOW);

        int recovered = recoveryService.recoverInflightSagas();

        assertThat(recovered).isEqualTo(1);
        assertThat(loadState(saga.getSagaId())).isEqualTo("FAILED");
        assertThat(loadReasonCode(saga.getSagaId())).isEqualTo(SagaRecoveryService.RECOVERY_REASON);
    }

    @Test
    void fundsReservedSagaIsCompensatedAndDebitIsReversed() {
        redis.opsForValue().set("balance:ACC-REC-FUNDS", "10000"); // $100.00
        PaymentSaga saga = orchestrator.initiate(
                message("TXN-REC-FUNDS", "E2E-REC-FUNDS"), Rail.FEDNOW);
        shadowLedger.applyDebit("ACC-REC-FUNDS", new BigDecimal("100.00"), "TXN-REC-FUNDS");
        orchestrator.advance(saga, PaymentSaga.SagaState.FUNDS_RESERVED);

        recoveryService.recoverInflightSagas();

        assertThat(loadState(saga.getSagaId())).isEqualTo("FAILED");
        // Balance must be restored — the reversal undid the debit
        assertThat(redis.opsForValue().get("balance:ACC-REC-FUNDS")).isEqualTo("10000");
    }

    @Test
    void coreSubmittedSagaIsCompensatedAndDebitIsReversed() {
        redis.opsForValue().set("balance:ACC-REC-CORE", "20000"); // $200.00
        PaymentSaga saga = orchestrator.initiate(
                message("TXN-REC-CORE", "E2E-REC-CORE"), Rail.RTP);
        shadowLedger.applyDebit("ACC-REC-CORE", new BigDecimal("75.00"), "TXN-REC-CORE");
        orchestrator.advance(saga, PaymentSaga.SagaState.FUNDS_RESERVED);
        orchestrator.advance(saga, PaymentSaga.SagaState.CORE_SUBMITTED);

        recoveryService.recoverInflightSagas();

        assertThat(loadState(saga.getSagaId())).isEqualTo("FAILED");
        assertThat(redis.opsForValue().get("balance:ACC-REC-CORE")).isEqualTo("20000");
    }

    @Test
    void fednowConfirmedSagaIsAdvancedToCompleted() {
        redis.opsForValue().set("balance:ACC-REC-CONF", "30000"); // $300.00
        PaymentSaga saga = orchestrator.initiate(
                message("TXN-REC-CONF", "E2E-REC-CONF"), Rail.FEDNOW);
        shadowLedger.applyDebit("ACC-REC-CONF", new BigDecimal("50.00"), "TXN-REC-CONF");
        orchestrator.advance(saga, PaymentSaga.SagaState.FUNDS_RESERVED);
        orchestrator.advance(saga, PaymentSaga.SagaState.CORE_SUBMITTED);
        orchestrator.advance(saga, PaymentSaga.SagaState.FEDNOW_CONFIRMED);

        recoveryService.recoverInflightSagas();

        assertThat(loadState(saga.getSagaId())).isEqualTo("COMPLETED");
        // No reversal happened — the debit stays applied ($300 - $50 = $250 = 25000 cents)
        assertThat(redis.opsForValue().get("balance:ACC-REC-CONF")).isEqualTo("25000");
    }

    @Test
    void compensatingSagaIsAdvancedToFailed() {
        PaymentSaga saga = orchestrator.initiate(
                message("TXN-REC-COMP", "E2E-REC-COMP"), Rail.FEDNOW);
        // Manually park the saga in COMPENSATING so recovery has to finalize it.
        jdbc.update(
                "UPDATE saga_state SET state = 'COMPENSATING', return_reason_code = 'AM04' WHERE saga_id = ?",
                saga.getSagaId());

        recoveryService.recoverInflightSagas();

        assertThat(loadState(saga.getSagaId())).isEqualTo("FAILED");
        // The original AM04 reason is preserved — recovery did not overwrite it
        assertThat(loadReasonCode(saga.getSagaId())).isEqualTo("AM04");
    }

    // ── Mixed batch ──────────────────────────────────────────────────────────

    @Test
    void mixedInflightStatesAreAllReachTerminalStateInOneRun() {
        PaymentSaga initiated = orchestrator.initiate(
                message("TXN-MIX-INIT", "E2E-MIX-INIT"), Rail.FEDNOW);

        PaymentSaga confirmed = orchestrator.initiate(
                message("TXN-MIX-CONF", "E2E-MIX-CONF"), Rail.FEDNOW);
        orchestrator.advance(confirmed, PaymentSaga.SagaState.FUNDS_RESERVED);
        orchestrator.advance(confirmed, PaymentSaga.SagaState.CORE_SUBMITTED);
        orchestrator.advance(confirmed, PaymentSaga.SagaState.FEDNOW_CONFIRMED);

        int recovered = recoveryService.recoverInflightSagas();

        assertThat(recovered).isEqualTo(2);
        assertThat(loadState(initiated.getSagaId())).isEqualTo("FAILED");
        assertThat(loadState(confirmed.getSagaId())).isEqualTo("COMPLETED");
    }

    // ── Idempotency ──────────────────────────────────────────────────────────

    @Test
    void recoveryIsIdempotentOnReRun() {
        PaymentSaga saga = orchestrator.initiate(
                message("TXN-IDEMP", "E2E-IDEMP"), Rail.FEDNOW);

        recoveryService.recoverInflightSagas();
        int secondRun = recoveryService.recoverInflightSagas();

        assertThat(secondRun).isZero();
        assertThat(loadState(saga.getSagaId())).isEqualTo("FAILED");
    }

    @Test
    void recoveryDoesNotReprocessAlreadyCompletedSagas() {
        PaymentSaga saga = orchestrator.initiate(
                message("TXN-DONE", "E2E-DONE"), Rail.FEDNOW);
        orchestrator.advance(saga, PaymentSaga.SagaState.FUNDS_RESERVED);
        orchestrator.advance(saga, PaymentSaga.SagaState.CORE_SUBMITTED);
        orchestrator.advance(saga, PaymentSaga.SagaState.FEDNOW_CONFIRMED);
        orchestrator.advance(saga, PaymentSaga.SagaState.COMPLETED);

        int recovered = recoveryService.recoverInflightSagas();
        assertThat(recovered).isZero();
        assertThat(loadState(saga.getSagaId())).isEqualTo("COMPLETED");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String loadState(String sagaId) {
        return jdbc.queryForObject(
                "SELECT state FROM saga_state WHERE saga_id = ?",
                String.class, sagaId);
    }

    private String loadReasonCode(String sagaId) {
        return jdbc.queryForObject(
                "SELECT return_reason_code FROM saga_state WHERE saga_id = ?",
                String.class, sagaId);
    }

    private static Pacs008Message message(String transactionId, String endToEndId) {
        return Pacs008Message.builder()
                .messageId("MSG-" + transactionId)
                .endToEndId(endToEndId)
                .transactionId(transactionId)
                .interbankSettlementAmount(new BigDecimal("100.00"))
                .interbankSettlementCurrency("USD")
                .creditorAccountNumber("ACC-RECOVERY-001")
                .build();
    }
}
