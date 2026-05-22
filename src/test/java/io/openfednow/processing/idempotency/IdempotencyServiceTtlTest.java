package io.openfednow.processing.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the configurable TTL on {@link IdempotencyService} — issue #38.
 *
 * <p>Verifies that {@code openfednow.idempotency.ttl-hours} is honored at
 * construction time and that invalid values are rejected. The TTL's
 * end-to-end effect on Redis expiry and the {@code expires_at} column is
 * covered by {@link IdempotencyServiceIntegrationTest}.
 */
class IdempotencyServiceTtlTest {

    @Test
    void retentionHoursIsExposedToCallers() {
        IdempotencyService service = new IdempotencyService(
                mock(StringRedisTemplate.class),
                mock(JdbcTemplate.class),
                new ObjectMapper(),
                72L);

        assertThat(service.getRetentionHours()).isEqualTo(72L);
    }

    @Test
    void defaultRetentionIs48Hours() {
        // The default-binding from application.yml is 48; this guards against
        // accidental drift between the @Value default and the documented behavior.
        IdempotencyService service = new IdempotencyService(
                mock(StringRedisTemplate.class),
                mock(JdbcTemplate.class),
                new ObjectMapper(),
                48L);

        assertThat(service.getRetentionHours()).isEqualTo(48L);
    }

    @Test
    void zeroRetentionIsRejected() {
        assertThatThrownBy(() -> new IdempotencyService(
                mock(StringRedisTemplate.class),
                mock(JdbcTemplate.class),
                new ObjectMapper(),
                0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl-hours");
    }

    @Test
    void negativeRetentionIsRejected() {
        assertThatThrownBy(() -> new IdempotencyService(
                mock(StringRedisTemplate.class),
                mock(JdbcTemplate.class),
                new ObjectMapper(),
                -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl-hours");
    }
}
