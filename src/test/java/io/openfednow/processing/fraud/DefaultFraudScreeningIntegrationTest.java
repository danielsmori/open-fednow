package io.openfednow.processing.fraud;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import io.openfednow.iso20022.Pacs008Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the Redis-backed velocity rule on {@link DefaultFraudScreeningService}
 * — issue #47.
 *
 * <p>Drives a real Redis container via Testcontainers because the velocity counter
 * relies on Redis {@code INCR} + {@code EXPIRE} semantics. Other rules are exercised
 * in the companion mocked unit test.
 */
class DefaultFraudScreeningIntegrationTest extends AbstractInfrastructureIntegrationTest {

    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    void cleanup() {
        redis.delete(redis.keys(DefaultFraudScreeningService.VELOCITY_KEY_PREFIX + "*"));
    }

    @Test
    void debtorVelocityBeyondLimitIsBlocked() {
        DefaultFraudScreeningService service = newService(3, 60);

        // First 3 calls pass — they fill the bucket.
        for (int i = 0; i < 3; i++) {
            ScreeningResult result = service.screen(payment("ACC-VEL", "ACC-OK", "TXN-" + i));
            assertThat(result.decision()).isEqualTo(ScreeningResult.Decision.PASS);
        }

        // The 4th must be blocked.
        ScreeningResult blocked = service.screen(payment("ACC-VEL", "ACC-OK", "TXN-4"));
        assertThat(blocked.decision()).isEqualTo(ScreeningResult.Decision.BLOCK);
        assertThat(blocked.description()).contains("debtor velocity");
        assertThat(blocked.description()).contains("exceeds 3");
    }

    @Test
    void velocityCounterExpiresAfterWindow() throws Exception {
        // Use a 1-second window so we can wait it out without slowing the suite excessively.
        DefaultFraudScreeningService service = newService(2, 1);

        service.screen(payment("ACC-EXP", "ACC-OK", "TXN-1"));
        service.screen(payment("ACC-EXP", "ACC-OK", "TXN-2"));
        // 3rd would block now
        assertThat(service.screen(payment("ACC-EXP", "ACC-OK", "TXN-3")).decision())
                .isEqualTo(ScreeningResult.Decision.BLOCK);

        // Wait for the window to expire — Redis will TTL the counter out
        Thread.sleep(1_500);

        // The next call must pass — bucket is fresh
        assertThat(service.screen(payment("ACC-EXP", "ACC-OK", "TXN-4")).decision())
                .isEqualTo(ScreeningResult.Decision.PASS);
    }

    @Test
    void velocityIsPerDebtorNotGlobal() {
        DefaultFraudScreeningService service = newService(1, 60);

        // First call for ACC-A passes
        assertThat(service.screen(payment("ACC-A", "ACC-OK", "TXN-A1")).decision())
                .isEqualTo(ScreeningResult.Decision.PASS);
        // Second call for ACC-A blocks
        assertThat(service.screen(payment("ACC-A", "ACC-OK", "TXN-A2")).decision())
                .isEqualTo(ScreeningResult.Decision.BLOCK);

        // First call for ACC-B must still pass — different bucket
        assertThat(service.screen(payment("ACC-B", "ACC-OK", "TXN-B1")).decision())
                .isEqualTo(ScreeningResult.Decision.PASS);
    }

    @Test
    void velocityRedisKeyIsCleanedUpBySetExpiration() {
        DefaultFraudScreeningService service = newService(5, 60);

        service.screen(payment("ACC-TTL", "ACC-OK", "TXN-TTL"));

        // The key should exist and have an expiration roughly equal to the window
        Long ttl = redis.getExpire(DefaultFraudScreeningService.VELOCITY_KEY_PREFIX + "ACC-TTL");
        assertThat(ttl).isPositive();
        assertThat(ttl).isLessThanOrEqualTo(60L);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private DefaultFraudScreeningService newService(int velocity, int windowSec) {
        return new DefaultFraudScreeningService(
                redis, new SimpleMeterRegistry(),
                new BigDecimal("1000000"),  // amount cap well above test values so velocity is the only signal
                velocity, windowSec, "");
    }

    private static Pacs008Message payment(String debtor, String creditor, String transactionId) {
        return Pacs008Message.builder()
                .messageId("MSG-" + transactionId)
                .endToEndId("E2E-" + transactionId)
                .transactionId(transactionId)
                .interbankSettlementAmount(new BigDecimal("100.00"))
                .interbankSettlementCurrency("USD")
                .debtorAccountNumber(debtor)
                .creditorAccountNumber(creditor)
                .build();
    }
}
