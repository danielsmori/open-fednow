package io.openfednow.processing.idempotency;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IdempotencyCleanupService} — issue #38.
 *
 * <p>Verifies the SQL contract and return value plumbing with a mocked
 * {@link JdbcTemplate}, so these tests run on every {@code mvn test} without
 * Docker. The companion integration test exercises a real PostgreSQL container.
 */
class IdempotencyCleanupServiceTest {

    @Test
    void sweepIssuesExpectedDeleteStatement() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(contains("DELETE FROM idempotency_keys"))).thenReturn(0);

        new IdempotencyCleanupService(jdbc).sweepExpired();

        verify(jdbc).update("DELETE FROM idempotency_keys WHERE expires_at < NOW()");
    }

    @Test
    void sweepReturnsRowsAffected() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(contains("DELETE FROM idempotency_keys"))).thenReturn(42);

        int deleted = new IdempotencyCleanupService(jdbc).sweepExpired();

        assertThat(deleted).isEqualTo(42);
    }

    @Test
    void sweepWithNoExpiredRowsReturnsZero() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(contains("DELETE FROM idempotency_keys"))).thenReturn(0);

        int deleted = new IdempotencyCleanupService(jdbc).sweepExpired();

        assertThat(deleted).isZero();
    }
}
