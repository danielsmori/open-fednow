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
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the hard timeout on {@link io.openfednow.processing.fraud.FraudScreeningPort}
 * invocations — audit item #18.
 *
 * <p>A production fraud port may call out to a hosted scoring service. If that
 * call hangs, the entire payment thread would block past the FedNow 20-second
 * SLA window. The router wraps every {@code screen()} invocation in a
 * {@link java.util.concurrent.CompletableFuture} with a hard deadline; on
 * timeout the framework fails open (PASS) so the payment proceeds.
 *
 * <p>This test pins {@code openfednow.fraud.screening-timeout-millis} to 100ms
 * for speed; the slow port delays 5 seconds, comfortably exceeding the bound.
 */
@ContextConfiguration(classes = FraudTimeoutIntegrationTest.SlowFraudConfig.class)
@TestPropertySource(properties = "openfednow.fraud.screening-timeout-millis=100")
class FraudTimeoutIntegrationTest extends AbstractInfrastructureIntegrationTest {

    @Autowired private MessageRouter messageRouter;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SlowFraudPort fraudPort;

    @BeforeEach
    void cleanup() {
        redis.delete(redis.keys("balance:*"));
        redis.delete(redis.keys("idempotency:*"));
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
        jdbc.update("DELETE FROM saga_state");
        jdbc.update("DELETE FROM idempotency_keys");
        fraudPort.reset();
    }

    @Test
    void slowFraudPortTimesOutAndFailsOpen() {
        // Delay 5 seconds — well past the 100ms timeout
        fraudPort.setDelayMillis(5_000);

        long start = System.currentTimeMillis();
        ResponseEntity<Pacs002Message> response = messageRouter.routeInbound(
                pacs008("TXN-TIMEOUT", "E2E-TIMEOUT"), Rail.FEDNOW);
        long elapsed = System.currentTimeMillis() - start;

        // Request returned in well under the port's 5-second delay — the timeout fired
        assertThat(elapsed).isLessThan(2_000);

        // Failed open — the payment proceeded as if the screen had returned PASS.
        // The exact downstream outcome depends on the sandbox adapter, but it must
        // not be FRAD (which is what a BLOCK would produce).
        if (response.getBody() != null
                && response.getBody().getTransactionStatus() == Pacs002Message.TransactionStatus.RJCT) {
            assertThat(response.getBody().getRejectReasonCode()).isNotEqualTo("FRAD");
        }

        // A saga was created — the timeout did NOT short-circuit before saga init
        Integer sagaCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM saga_state WHERE transaction_id = 'TXN-TIMEOUT'",
                Integer.class);
        assertThat(sagaCount).isEqualTo(1);
    }

    @Test
    void normalPortLatencyPassesThrough() {
        // No delay — port returns immediately
        fraudPort.setDelayMillis(0);

        ResponseEntity<Pacs002Message> response = messageRouter.routeInbound(
                pacs008("TXN-FAST", "E2E-FAST"), Rail.FEDNOW);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Integer sagaCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM saga_state WHERE transaction_id = 'TXN-FAST'",
                Integer.class);
        assertThat(sagaCount).isEqualTo(1);
    }

    @Test
    void portThatThrowsAlsoFailsOpen() {
        fraudPort.setException(new RuntimeException("simulated scoring-service outage"));

        ResponseEntity<Pacs002Message> response = messageRouter.routeInbound(
                pacs008("TXN-THROW", "E2E-THROW"), Rail.FEDNOW);

        // Failed open — exceptions from the port don't short-circuit the payment
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        if (response.getBody() != null
                && response.getBody().getTransactionStatus() == Pacs002Message.TransactionStatus.RJCT) {
            assertThat(response.getBody().getRejectReasonCode()).isNotEqualTo("FRAD");
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static Pacs008Message pacs008(String transactionId, String endToEndId) {
        return Pacs008Message.builder()
                .messageId("MSG-" + transactionId)
                .endToEndId(endToEndId)
                .transactionId(transactionId)
                .interbankSettlementAmount(new BigDecimal("50.00"))
                .interbankSettlementCurrency("USD")
                .debtorAccountNumber("ACC-DEBTOR")
                .creditorAccountNumber("ACC-CREDITOR")
                .build();
    }

    /** Port that can be configured to delay or throw — exercises the router's timeout path. */
    static class SlowFraudPort implements FraudScreeningPort {
        private volatile long delayMillis = 0;
        private volatile RuntimeException toThrow;

        void setDelayMillis(long delayMillis) { this.delayMillis = delayMillis; this.toThrow = null; }
        void setException(RuntimeException ex) { this.toThrow = ex; this.delayMillis = 0; }
        void reset() { this.delayMillis = 0; this.toThrow = null; }

        @Override
        public ScreeningResult screen(Pacs008Message message) {
            if (toThrow != null) {
                throw toThrow;
            }
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return ScreeningResult.pass();
        }
    }

    @TestConfiguration
    static class SlowFraudConfig {
        @Bean
        @Primary
        SlowFraudPort slowFraudPort() {
            return new SlowFraudPort();
        }
    }
}
