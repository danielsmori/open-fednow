package io.openfednow.processing.cancellation;

import io.openfednow.gateway.Rail;
import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import io.openfednow.iso20022.Camt029Message;
import io.openfednow.iso20022.Camt056Message;
import io.openfednow.iso20022.Pacs008Message;
import io.openfednow.processing.saga.PaymentSaga;
import io.openfednow.processing.saga.SagaOrchestrator;
import io.openfednow.shadowledger.ShadowLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the end-to-end cancellation flow — issue #26.
 *
 * <p>Drives real Redis + Postgres via Testcontainers to confirm the Shadow Ledger
 * credit reversal lands in the right account and the saga reaches a terminal
 * state with the expected reason code carried through from the camt.056.
 */
class CancellationIntegrationTest extends AbstractInfrastructureIntegrationTest {

    @Autowired private CancellationService cancellationService;
    @Autowired private SagaOrchestrator orchestrator;
    @Autowired private ShadowLedger shadowLedger;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
        jdbc.update("DELETE FROM saga_state");
        redis.delete(redis.keys("balance:*"));
    }

    // ── CNCL path: credit is reversed end-to-end ──────────────────────────────

    @Test
    void cancellingFundsReservedSagaReversesShadowLedgerCredit() {
        redis.opsForValue().set("balance:ACC-CNCL", "10000"); // $100.00

        PaymentSaga saga = orchestrator.initiate(
                pacs008("TXN-CNCL", "E2E-CNCL", "ACC-CNCL"), Rail.FEDNOW);
        shadowLedger.applyCredit("ACC-CNCL", new BigDecimal("250.00"), "TXN-CNCL");
        orchestrator.advance(saga, PaymentSaga.SagaState.FUNDS_RESERVED);

        // Balance is $100 + $250 = $350 = 35000 cents
        assertThat(redis.opsForValue().get("balance:ACC-CNCL")).isEqualTo("35000");

        Camt029Message response = cancellationService.handleCancellationRequest(
                camt056("TXN-CNCL", "DUPL"));

        assertThat(response.getResolutionStatus()).isEqualTo(Camt029Message.ResolutionStatus.CNCL);
        assertThat(loadState(saga.getSagaId())).isEqualTo("FAILED");
        assertThat(loadReasonCode(saga.getSagaId())).isEqualTo("DUPL");
        // Credit reversed — balance is back to $100
        assertThat(redis.opsForValue().get("balance:ACC-CNCL")).isEqualTo("10000");
    }

    @Test
    void cancellingInitiatedSagaTerminatesWithoutReversal() {
        PaymentSaga saga = orchestrator.initiate(
                pacs008("TXN-INIT", "E2E-INIT", "ACC-INIT"), Rail.FEDNOW);

        Camt029Message response = cancellationService.handleCancellationRequest(
                camt056("TXN-INIT", "CUST"));

        assertThat(response.getResolutionStatus()).isEqualTo(Camt029Message.ResolutionStatus.CNCL);
        assertThat(loadState(saga.getSagaId())).isEqualTo("FAILED");
        assertThat(loadReasonCode(saga.getSagaId())).isEqualTo("CUST");
        // No reversal row inserted because no credit was applied
        Integer reversals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log " +
                "WHERE transaction_id = ? AND transaction_type = 'REVERSAL'",
                Integer.class, "TXN-INIT");
        assertThat(reversals).isZero();
    }

    // ── PDCR path ─────────────────────────────────────────────────────────────

    @Test
    void cancellingCoreSubmittedSagaReturnsPendingAndLeavesSagaUntouched() {
        PaymentSaga saga = orchestrator.initiate(
                pacs008("TXN-CORE", "E2E-CORE", "ACC-CORE"), Rail.FEDNOW);
        orchestrator.advance(saga, PaymentSaga.SagaState.FUNDS_RESERVED);
        orchestrator.advance(saga, PaymentSaga.SagaState.CORE_SUBMITTED);

        Camt029Message response = cancellationService.handleCancellationRequest(
                camt056("TXN-CORE", "DUPL"));

        assertThat(response.getResolutionStatus()).isEqualTo(Camt029Message.ResolutionStatus.PDCR);
        // Saga unchanged
        assertThat(loadState(saga.getSagaId())).isEqualTo("CORE_SUBMITTED");
    }

    // ── RJCR ARDT path: already settled ───────────────────────────────────────

    @Test
    void cancellingCompletedSagaIsRejectedWithArdt() {
        PaymentSaga saga = orchestrator.initiate(
                pacs008("TXN-DONE", "E2E-DONE", "ACC-DONE"), Rail.FEDNOW);
        orchestrator.advance(saga, PaymentSaga.SagaState.FUNDS_RESERVED);
        orchestrator.advance(saga, PaymentSaga.SagaState.CORE_SUBMITTED);
        orchestrator.advance(saga, PaymentSaga.SagaState.FEDNOW_CONFIRMED);
        orchestrator.advance(saga, PaymentSaga.SagaState.COMPLETED);

        Camt029Message response = cancellationService.handleCancellationRequest(
                camt056("TXN-DONE", "DUPL"));

        assertThat(response.getResolutionStatus()).isEqualTo(Camt029Message.ResolutionStatus.RJCR);
        assertThat(response.getRejectionReasonCode())
                .isEqualTo(CancellationService.RJCR_ALREADY_SETTLED);
        assertThat(loadState(saga.getSagaId())).isEqualTo("COMPLETED");
    }

    // ── RJCR NOOR path: no transaction ────────────────────────────────────────

    @Test
    void unknownTransactionIsRejectedWithNoor() {
        Camt029Message response = cancellationService.handleCancellationRequest(
                camt056("TXN-DOES-NOT-EXIST", "DUPL"));

        assertThat(response.getResolutionStatus()).isEqualTo(Camt029Message.ResolutionStatus.RJCR);
        assertThat(response.getRejectionReasonCode())
                .isEqualTo(CancellationService.RJCR_NO_ORIGINAL);
    }

    // ── Reason code propagation ───────────────────────────────────────────────

    @Test
    void cancellationReasonFromCamt056IsRecordedOnSaga() {
        redis.opsForValue().set("balance:ACC-FRAUD", "10000");
        PaymentSaga saga = orchestrator.initiate(
                pacs008("TXN-FRAUD", "E2E-FRAUD", "ACC-FRAUD"), Rail.FEDNOW);
        shadowLedger.applyCredit("ACC-FRAUD", new BigDecimal("100.00"), "TXN-FRAUD");
        orchestrator.advance(saga, PaymentSaga.SagaState.FUNDS_RESERVED);

        cancellationService.handleCancellationRequest(camt056("TXN-FRAUD", "FRAUD"));

        assertThat(loadReasonCode(saga.getSagaId())).isEqualTo("FRAUD");
    }

    // ── Audit row written ─────────────────────────────────────────────────────

    @Test
    void cancellationWritesReversalAuditRow() {
        redis.opsForValue().set("balance:ACC-AUDIT", "10000");
        PaymentSaga saga = orchestrator.initiate(
                pacs008("TXN-AUDIT", "E2E-AUDIT", "ACC-AUDIT"), Rail.FEDNOW);
        shadowLedger.applyCredit("ACC-AUDIT", new BigDecimal("75.00"), "TXN-AUDIT");
        orchestrator.advance(saga, PaymentSaga.SagaState.FUNDS_RESERVED);

        cancellationService.handleCancellationRequest(camt056("TXN-AUDIT", "DUPL"));

        Integer reversals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log " +
                "WHERE transaction_id = 'TXN-AUDIT' AND transaction_type = 'REVERSAL'",
                Integer.class);
        assertThat(reversals).isEqualTo(1);
        assertThat(saga).isNotNull();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    private static Pacs008Message pacs008(String transactionId, String endToEndId, String creditorAccount) {
        return Pacs008Message.builder()
                .messageId("MSG-" + transactionId)
                .endToEndId(endToEndId)
                .transactionId(transactionId)
                .interbankSettlementAmount(new BigDecimal("100.00"))
                .interbankSettlementCurrency("USD")
                .creditorAccountNumber(creditorAccount)
                .build();
    }

    private static Camt056Message camt056(String originalTransactionId, String reasonCode) {
        return Camt056Message.builder()
                .messageId("CNCL-" + originalTransactionId)
                .creationDateTime(OffsetDateTime.now())
                .caseId("CASE-" + originalTransactionId)
                .originalMessageId("MSG-" + originalTransactionId)
                .originalEndToEndId("E2E-" + originalTransactionId.substring(4))
                .originalTransactionId(originalTransactionId)
                .originalInterbankSettlementAmount(new BigDecimal("100.00"))
                .originalInterbankSettlementCurrency("USD")
                .cancellationReasonCode(reasonCode)
                .cancellationReasonDescription("Test cancellation")
                .build();
    }
}
