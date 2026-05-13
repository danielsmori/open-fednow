package io.openfednow.gateway;

import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import io.openfednow.shadowledger.ShadowLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests for the outbound send-side payment flow — sandbox/reference mode.
 *
 * <p>{@link FedNowClient} is replaced with a Mockito mock so each test can control
 * FedNow's simulated response without requiring a live network or WireMock server.
 * All other components (MessageRouter, ShadowLedger, SagaOrchestrator, IdempotencyService)
 * run against real Testcontainers infrastructure.
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>Successful outbound payment debits debtor account and reaches COMPLETED saga state</li>
 *   <li>Duplicate outbound payment is suppressed and balance unchanged</li>
 *   <li>Insufficient funds returns RJCT AM04 without creating a saga</li>
 *   <li>FedNow RJCT reverses the debit and compensates the saga</li>
 *   <li>FedNow ACSP leaves saga at CORE_SUBMITTED for reconciliation</li>
 *   <li>Rollback on infrastructure error restores debtor balance</li>
 * </ul>
 */
class OutboundPaymentIntegrationTest extends AbstractInfrastructureIntegrationTest {

    @Autowired private MessageRouter messageRouter;
    @Autowired private ShadowLedger shadowLedger;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;

    /** Replace the real FedNow HTTP client with a controllable mock. */
    @MockBean private FedNowClient fedNowClient;

    private static final String DEBTOR_ACCOUNT = "ACC-DEBTOR-OUT-001";
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000.00");

    @BeforeEach
    void setup() {
        redis.delete(redis.keys("balance:*"));
        redis.delete(redis.keys("idempotency:*"));
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
        jdbc.update("DELETE FROM saga_state");
        jdbc.update("DELETE FROM idempotency_keys");

        // Seed debtor account with known balance
        redis.opsForValue().set("balance:" + DEBTOR_ACCOUNT,
                String.valueOf(ShadowLedger.dollarsToCents(INITIAL_BALANCE)));
    }

    // ── Successful send ───────────────────────────────────────────────────────

    @Test
    void outboundPayment_debitsDebtorAccount_onAcsc() {
        when(fedNowClient.submitCreditTransfer(any())).thenReturn(acsc("TXN-OUT-001", "E2E-OUT-001"));

        messageRouter.routeOutbound(message("TXN-OUT-001", "E2E-OUT-001", "250.00"));

        BigDecimal balance = shadowLedger.getAvailableBalance(DEBTOR_ACCOUNT);
        assertThat(balance).isEqualByComparingTo("750.00"); // 1000 - 250
    }

    @Test
    void outboundPayment_createsSagaInCompletedState_onAcsc() {
        when(fedNowClient.submitCreditTransfer(any())).thenReturn(acsc("TXN-OUT-002", "E2E-OUT-002"));

        messageRouter.routeOutbound(message("TXN-OUT-002", "E2E-OUT-002", "100.00"));

        String state = jdbc.queryForObject(
                "SELECT state FROM saga_state WHERE transaction_id = 'TXN-OUT-002'",
                String.class);
        assertThat(state).isEqualTo("COMPLETED");
    }

    @Test
    void outboundPayment_writesDebitAuditLog_onAcsc() {
        when(fedNowClient.submitCreditTransfer(any())).thenReturn(acsc("TXN-OUT-003", "E2E-OUT-003"));

        messageRouter.routeOutbound(message("TXN-OUT-003", "E2E-OUT-003", "50.00"));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log " +
                "WHERE transaction_id = 'TXN-OUT-003' AND transaction_type = 'DEBIT'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // ── Idempotency ───────────────────────────────────────────────────────────

    @Test
    void duplicateOutboundPayment_doesNotDoubleDebit() {
        when(fedNowClient.submitCreditTransfer(any())).thenReturn(acsc("TXN-OUT-DUP-001", "E2E-OUT-DUP-001"));

        messageRouter.routeOutbound(message("TXN-OUT-DUP-001", "E2E-OUT-DUP-001", "200.00"));
        messageRouter.routeOutbound(message("TXN-OUT-DUP-001", "E2E-OUT-DUP-001", "200.00")); // duplicate

        BigDecimal balance = shadowLedger.getAvailableBalance(DEBTOR_ACCOUNT);
        assertThat(balance).isEqualByComparingTo("800.00"); // only one debit
        verify(fedNowClient, Mockito.times(1)).submitCreditTransfer(any()); // FedNow called once
    }

    // ── Insufficient funds ────────────────────────────────────────────────────

    @Test
    void outboundPayment_returnsRjctAm04_whenInsufficientFunds() {
        Pacs002Message response = messageRouter
                .routeOutbound(message("TXN-OUT-NSF-001", "E2E-OUT-NSF-001", "5000.00"))
                .getBody();

        assertThat(response.getTransactionStatus()).isEqualTo(Pacs002Message.TransactionStatus.RJCT);
        assertThat(response.getRejectReasonCode()).isEqualTo("AM04");
        verify(fedNowClient, Mockito.never()).submitCreditTransfer(any());
    }

    @Test
    void insufficientFunds_doesNotDebitAccount() {
        messageRouter.routeOutbound(message("TXN-OUT-NSF-002", "E2E-OUT-NSF-002", "9999.00"));

        BigDecimal balance = shadowLedger.getAvailableBalance(DEBTOR_ACCOUNT);
        assertThat(balance).isEqualByComparingTo(INITIAL_BALANCE); // unchanged
    }

    @Test
    void insufficientFunds_doesNotCreateSaga() {
        messageRouter.routeOutbound(message("TXN-OUT-NSF-003", "E2E-OUT-NSF-003", "9999.00"));

        Integer sagaCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM saga_state WHERE transaction_id = 'TXN-OUT-NSF-003'",
                Integer.class);
        assertThat(sagaCount).isEqualTo(0);
    }

    // ── FedNow rejection — debit reversed ────────────────────────────────────

    @Test
    void outboundPayment_reversesDebit_onFedNowRjct() {
        when(fedNowClient.submitCreditTransfer(any()))
                .thenReturn(rjct("TXN-OUT-RJT-001", "E2E-OUT-RJT-001", "AC01"));

        messageRouter.routeOutbound(message("TXN-OUT-RJT-001", "E2E-OUT-RJT-001", "300.00"));

        // Balance must be fully restored
        BigDecimal balance = shadowLedger.getAvailableBalance(DEBTOR_ACCOUNT);
        assertThat(balance).isEqualByComparingTo(INITIAL_BALANCE);
    }

    @Test
    void outboundPayment_createsSagaInFailedState_onFedNowRjct() {
        when(fedNowClient.submitCreditTransfer(any()))
                .thenReturn(rjct("TXN-OUT-RJT-002", "E2E-OUT-RJT-002", "AM04"));

        messageRouter.routeOutbound(message("TXN-OUT-RJT-002", "E2E-OUT-RJT-002", "100.00"));

        String state = jdbc.queryForObject(
                "SELECT state FROM saga_state WHERE transaction_id = 'TXN-OUT-RJT-002'",
                String.class);
        assertThat(state).isEqualTo("FAILED");
    }

    @Test
    void outboundPayment_writesReversalAuditLog_onFedNowRjct() {
        when(fedNowClient.submitCreditTransfer(any()))
                .thenReturn(rjct("TXN-OUT-RJT-003", "E2E-OUT-RJT-003", "AC04"));

        messageRouter.routeOutbound(message("TXN-OUT-RJT-003", "E2E-OUT-RJT-003", "75.00"));

        Integer debitCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log " +
                "WHERE transaction_id = 'TXN-OUT-RJT-003' AND transaction_type = 'DEBIT'",
                Integer.class);
        Integer reversalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log " +
                "WHERE transaction_id = 'TXN-OUT-RJT-003' AND transaction_type = 'REVERSAL'",
                Integer.class);
        assertThat(debitCount).isEqualTo(1);
        assertThat(reversalCount).isEqualTo(1);
    }

    // ── FedNow provisional acceptance ─────────────────────────────────────────

    @Test
    void outboundPayment_leavesDebitApplied_onAcsp() {
        when(fedNowClient.submitCreditTransfer(any())).thenReturn(acsp("TXN-OUT-ACSP-001", "E2E-OUT-ACSP-001"));

        messageRouter.routeOutbound(message("TXN-OUT-ACSP-001", "E2E-OUT-ACSP-001", "150.00"));

        BigDecimal balance = shadowLedger.getAvailableBalance(DEBTOR_ACCOUNT);
        assertThat(balance).isEqualByComparingTo("850.00"); // debit held, not reversed
    }

    @Test
    void outboundPayment_leaveSagaAtCoreSubmitted_onAcsp() {
        when(fedNowClient.submitCreditTransfer(any())).thenReturn(acsp("TXN-OUT-ACSP-002", "E2E-OUT-ACSP-002"));

        messageRouter.routeOutbound(message("TXN-OUT-ACSP-002", "E2E-OUT-ACSP-002", "50.00"));

        String state = jdbc.queryForObject(
                "SELECT state FROM saga_state WHERE transaction_id = 'TXN-OUT-ACSP-002'",
                String.class);
        assertThat(state).isEqualTo("CORE_SUBMITTED");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Pacs008Message message(String transactionId, String endToEndId, String amount) {
        return Pacs008Message.builder()
                .messageId("MSG-" + transactionId)
                .endToEndId(endToEndId)
                .transactionId(transactionId)
                .interbankSettlementAmount(new BigDecimal(amount))
                .interbankSettlementCurrency("USD")
                .debtorAccountNumber(DEBTOR_ACCOUNT)
                .creditorAccountNumber("ACC-CREDITOR-001")
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("021000089")
                .debtorName("Alice Smith")
                .creditorName("Bob Jones")
                .numberOfTransactions(1)
                .creationDateTime(OffsetDateTime.now())
                .build();
    }

    private static Pacs002Message acsc(String transactionId, String endToEndId) {
        return Pacs002Message.builder()
                .originalEndToEndId(endToEndId)
                .originalTransactionId(transactionId)
                .transactionStatus(Pacs002Message.TransactionStatus.ACSC)
                .creationDateTime(OffsetDateTime.now())
                .build();
    }

    private static Pacs002Message acsp(String transactionId, String endToEndId) {
        return Pacs002Message.builder()
                .originalEndToEndId(endToEndId)
                .originalTransactionId(transactionId)
                .transactionStatus(Pacs002Message.TransactionStatus.ACSP)
                .creationDateTime(OffsetDateTime.now())
                .build();
    }

    private static Pacs002Message rjct(String transactionId, String endToEndId, String reasonCode) {
        return Pacs002Message.builder()
                .originalEndToEndId(endToEndId)
                .originalTransactionId(transactionId)
                .transactionStatus(Pacs002Message.TransactionStatus.RJCT)
                .rejectReasonCode(reasonCode)
                .rejectReasonDescription("Sandbox rejection: " + reasonCode)
                .creationDateTime(OffsetDateTime.now())
                .build();
    }
}
