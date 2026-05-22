package io.openfednow.processing.saga;

import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the {@link SagaTimeoutMonitor} and the underlying
 * {@link SagaOrchestrator#findTimedOutSagaIds(int)} query — issue #37.
 *
 * <p>Exercises the SQL and the end-to-end compensation flow against real
 * Redis + Postgres via Testcontainers, including the Shadow Ledger debit
 * reversal that happens inside {@code compensate()}.
 */
class SagaTimeoutIntegrationTest extends AbstractInfrastructureIntegrationTest {

    @Autowired private SagaOrchestrator orchestrator;
    @Autowired private SagaTimeoutMonitor monitor;
    @Autowired private ShadowLedger shadowLedger;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
        jdbc.update("DELETE FROM saga_state");
        redis.delete(redis.keys("balance:*"));
    }

    // ── findTimedOutSagaIds query ────────────────────────────────────────────

    @Test
    void freshSagasAreNotConsideredTimedOut() {
        orchestrator.initiate(message("TXN-FRESH", "E2E-FRESH"), Rail.FEDNOW);

        List<String> stale = orchestrator.findTimedOutSagaIds(30);

        assertThat(stale).isEmpty();
    }

    @Test
    void agedSagaInForwardProgressIsTimedOut() {
        PaymentSaga saga = orchestrator.initiate(
                message("TXN-AGED", "E2E-AGED"), Rail.FEDNOW);
        // Back-date the row so the threshold check fires
        backdate(saga.getSagaId(), 120);

        List<String> stale = orchestrator.findTimedOutSagaIds(30);

        assertThat(stale).containsExactly(saga.getSagaId());
    }

    @Test
    void terminalAndCompensatingSagasAreExcluded() {
        // COMPLETED — must be excluded
        PaymentSaga done = orchestrator.initiate(
                message("TXN-DONE", "E2E-DONE"), Rail.FEDNOW);
        orchestrator.advance(done, PaymentSaga.SagaState.FUNDS_RESERVED);
        orchestrator.advance(done, PaymentSaga.SagaState.CORE_SUBMITTED);
        orchestrator.advance(done, PaymentSaga.SagaState.FEDNOW_CONFIRMED);
        orchestrator.advance(done, PaymentSaga.SagaState.COMPLETED);
        backdate(done.getSagaId(), 120);

        // FAILED — must be excluded
        PaymentSaga failed = orchestrator.initiate(
                message("TXN-FAILED", "E2E-FAILED"), Rail.FEDNOW);
        orchestrator.compensate(failed.getSagaId(), "AM04");
        backdate(failed.getSagaId(), 120);

        // COMPENSATING — must be excluded (already in progress)
        PaymentSaga inComp = orchestrator.initiate(
                message("TXN-INCOMP", "E2E-INCOMP"), Rail.FEDNOW);
        jdbc.update("UPDATE saga_state SET state = 'COMPENSATING' WHERE saga_id = ?",
                inComp.getSagaId());
        backdate(inComp.getSagaId(), 120);

        // FEDNOW_CONFIRMED — must be excluded (settlement happened)
        PaymentSaga confirmed = orchestrator.initiate(
                message("TXN-CONFIRMED", "E2E-CONFIRMED"), Rail.FEDNOW);
        orchestrator.advance(confirmed, PaymentSaga.SagaState.FUNDS_RESERVED);
        orchestrator.advance(confirmed, PaymentSaga.SagaState.CORE_SUBMITTED);
        orchestrator.advance(confirmed, PaymentSaga.SagaState.FEDNOW_CONFIRMED);
        backdate(confirmed.getSagaId(), 120);

        assertThat(orchestrator.findTimedOutSagaIds(30)).isEmpty();
    }

    @Test
    void invalidTimeoutIsRejected() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> orchestrator.findTimedOutSagaIds(0))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> orchestrator.findTimedOutSagaIds(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── End-to-end sweep ─────────────────────────────────────────────────────

    @Test
    void monitorSweepCompensatesTimedOutSagaAndReversesDebit() {
        redis.opsForValue().set("balance:ACC-TIMEOUT", "10000"); // $100.00
        PaymentSaga saga = orchestrator.initiate(
                message("TXN-TIMEOUT", "E2E-TIMEOUT"), Rail.FEDNOW);
        shadowLedger.applyDebit("ACC-TIMEOUT", new BigDecimal("100.00"), "TXN-TIMEOUT");
        orchestrator.advance(saga, PaymentSaga.SagaState.FUNDS_RESERVED);
        backdate(saga.getSagaId(), monitor.getTimeoutSeconds() + 60);

        double counterBefore = meterRegistry.counter(SagaTimeoutMonitor.TIMEOUT_METRIC).count();

        int compensated = monitor.sweepTimedOutSagas();

        assertThat(compensated).isEqualTo(1);

        // Saga reached FAILED with the XPIR reason code
        String state = jdbc.queryForObject(
                "SELECT state FROM saga_state WHERE saga_id = ?",
                String.class, saga.getSagaId());
        assertThat(state).isEqualTo("FAILED");

        String reason = jdbc.queryForObject(
                "SELECT return_reason_code FROM saga_state WHERE saga_id = ?",
                String.class, saga.getSagaId());
        assertThat(reason).isEqualTo(SagaTimeoutMonitor.TIMEOUT_REASON_CODE);

        // Shadow Ledger debit was reversed by the compensate() call
        assertThat(redis.opsForValue().get("balance:ACC-TIMEOUT")).isEqualTo("10000");

        // Counter advanced by exactly one
        double counterAfter = meterRegistry.counter(SagaTimeoutMonitor.TIMEOUT_METRIC).count();
        assertThat(counterAfter - counterBefore).isEqualTo(1.0);
    }

    @Test
    void monitorSweepIsNoOpWhenNothingIsStale() {
        orchestrator.initiate(message("TXN-FRESH-S", "E2E-FRESH-S"), Rail.FEDNOW);

        int compensated = monitor.sweepTimedOutSagas();

        assertThat(compensated).isZero();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void backdate(String sagaId, int seconds) {
        jdbc.update(
                "UPDATE saga_state SET created_at = ? WHERE saga_id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(seconds)),
                sagaId);
    }

    private static Pacs008Message message(String transactionId, String endToEndId) {
        return Pacs008Message.builder()
                .messageId("MSG-" + transactionId)
                .endToEndId(endToEndId)
                .transactionId(transactionId)
                .interbankSettlementAmount(new BigDecimal("100.00"))
                .interbankSettlementCurrency("USD")
                .creditorAccountNumber("ACC-TIMEOUT-001")
                .build();
    }
}
