package io.openfednow.processing.fraud;

import io.openfednow.gateway.MessageRouter;
import io.openfednow.gateway.Rail;
import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that {@link MessageRouter} honors {@link FraudScreeningPort}
 * BLOCK decisions on both inbound and outbound paths — issue #47.
 *
 * <p>Uses a test-scoped {@link FraudScreeningPort} bean that returns BLOCK on demand,
 * so the router's behavior is testable without depending on the default rule set.
 * The companion {@link DefaultFraudScreeningIntegrationTest} covers the default rules
 * end-to-end.
 */
@ContextConfiguration(classes = FraudRoutingIntegrationTest.TestFraudConfig.class)
class FraudRoutingIntegrationTest extends AbstractInfrastructureIntegrationTest {

    @Autowired private MessageRouter messageRouter;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ToggleableFraudPort fraudPort;

    @BeforeEach
    void cleanup() {
        redis.delete(redis.keys("balance:*"));
        redis.delete(redis.keys("idempotency:*"));
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
        jdbc.update("DELETE FROM saga_state");
        jdbc.update("DELETE FROM idempotency_keys");
        fraudPort.reset();
    }

    // ── Inbound BLOCK ────────────────────────────────────────────────────────

    @Test
    void inboundBlockReturnsRjctFradWithoutInitiatingSaga() {
        fraudPort.setDecision(ScreeningResult.block("FRAD", "test block — debtor flagged"));

        ResponseEntity<Pacs002Message> response = messageRouter.routeInbound(
                pacs008("TXN-INB-BLOCK", "E2E-INB-BLOCK"), Rail.FEDNOW);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTransactionStatus())
                .isEqualTo(Pacs002Message.TransactionStatus.RJCT);
        assertThat(response.getBody().getRejectReasonCode()).isEqualTo("FRAD");
        assertThat(response.getBody().getRejectReasonDescription()).contains("fraud pre-screening");

        // No saga was initiated — fraud BLOCK short-circuits before saga creation
        Integer sagaCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM saga_state WHERE transaction_id = ?",
                Integer.class, "TXN-INB-BLOCK");
        assertThat(sagaCount).isZero();

        // Outcome is recorded for idempotency so a retry returns the same RJCT
        assertThat(fraudPort.callCount.get()).isEqualTo(1);
    }

    @Test
    void inboundPassProceedsThroughFullPipeline() {
        fraudPort.setDecision(ScreeningResult.pass());

        ResponseEntity<Pacs002Message> response = messageRouter.routeInbound(
                pacs008("TXN-INB-PASS", "E2E-INB-PASS"), Rail.FEDNOW);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        // The saga was created
        Integer sagaCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM saga_state WHERE transaction_id = ?",
                Integer.class, "TXN-INB-PASS");
        assertThat(sagaCount).isEqualTo(1);
    }

    @Test
    void inboundReviewProceedsLikePassWithoutBlocking() {
        fraudPort.setDecision(ScreeningResult.review("REVW", "elevated amount"));

        ResponseEntity<Pacs002Message> response = messageRouter.routeInbound(
                pacs008("TXN-INB-REVIEW", "E2E-INB-REVIEW"), Rail.FEDNOW);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody().getTransactionStatus())
                .isNotEqualTo(Pacs002Message.TransactionStatus.RJCT);
        // Saga still created — review proceeds
        Integer sagaCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM saga_state WHERE transaction_id = ?",
                Integer.class, "TXN-INB-REVIEW");
        assertThat(sagaCount).isEqualTo(1);
    }

    // ── Outbound BLOCK ───────────────────────────────────────────────────────

    @Test
    void outboundBlockReturnsRjctFradBeforeFundsCheck() {
        // Seed the balance well above the payment amount — if fraud is bypassed,
        // the funds check would pass. The BLOCK must fire first.
        redis.opsForValue().set("balance:ACC-OUT-DEBTOR", "1000000"); // $10,000
        fraudPort.setDecision(ScreeningResult.block("FRAD", "outbound test block"));

        Pacs008Message outbound = Pacs008Message.builder()
                .messageId("MSG-OUT-BLOCK")
                .endToEndId("E2E-OUT-BLOCK")
                .transactionId("TXN-OUT-BLOCK")
                .interbankSettlementAmount(new BigDecimal("100.00"))
                .interbankSettlementCurrency("USD")
                .debtorAccountNumber("ACC-OUT-DEBTOR")
                .creditorAccountNumber("ACC-OUT-CREDITOR")
                .build();

        ResponseEntity<Pacs002Message> response = messageRouter.routeOutbound(outbound);

        assertThat(response.getBody().getTransactionStatus())
                .isEqualTo(Pacs002Message.TransactionStatus.RJCT);
        assertThat(response.getBody().getRejectReasonCode()).isEqualTo("FRAD");

        // Saga was not created
        Integer sagaCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM saga_state WHERE transaction_id = ?",
                Integer.class, "TXN-OUT-BLOCK");
        assertThat(sagaCount).isZero();

        // Balance is unchanged — the debit was never applied
        assertThat(redis.opsForValue().get("balance:ACC-OUT-DEBTOR")).isEqualTo("1000000");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Pacs008Message pacs008(String transactionId, String endToEndId) {
        return Pacs008Message.builder()
                .messageId("MSG-" + transactionId)
                .endToEndId(endToEndId)
                .transactionId(transactionId)
                .interbankSettlementAmount(new BigDecimal("100.00"))
                .interbankSettlementCurrency("USD")
                .debtorAccountNumber("ACC-DEBTOR")
                .creditorAccountNumber("ACC-CREDITOR")
                .build();
    }

    /** Toggleable port: tests set the decision, then verify the router's response. */
    static class ToggleableFraudPort implements FraudScreeningPort {
        private volatile ScreeningResult result = ScreeningResult.pass();
        final AtomicInteger callCount = new AtomicInteger();

        void setDecision(ScreeningResult result) { this.result = result; }
        void reset() { this.result = ScreeningResult.pass(); callCount.set(0); }

        @Override
        public ScreeningResult screen(Pacs008Message message) {
            callCount.incrementAndGet();
            return result;
        }
    }

    @TestConfiguration
    static class TestFraudConfig {
        @Bean
        @Primary
        ToggleableFraudPort toggleableFraudPort() {
            return new ToggleableFraudPort();
        }
    }
}
