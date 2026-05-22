package io.openfednow.processing.idempotency;

import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import io.openfednow.iso20022.Pacs002Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link IdempotencyCleanupService} — issue #38.
 *
 * <p>Verifies the end-to-end cleanup contract: rows past their
 * {@code expires_at} are removed; live rows are not; an expired EndToEndId
 * does not block a legitimate re-submission of the same payment after the
 * TTL has elapsed.
 */
class IdempotencyCleanupServiceIntegrationTest extends AbstractInfrastructureIntegrationTest {

    @Autowired private IdempotencyService idempotencyService;
    @Autowired private IdempotencyCleanupService cleanupService;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        redis.delete(redis.keys("idempotency:*"));
        jdbc.update("DELETE FROM idempotency_keys");
    }

    @Test
    void sweepDeletesRowsPastExpiry() {
        // Insert a row whose expires_at is one hour in the past
        jdbc.update("""
                INSERT INTO idempotency_keys
                    (end_to_end_id, message_id, response_status, processed_at, expires_at)
                VALUES (?, ?, 'ACSC', NOW() - INTERVAL '49' HOUR, NOW() - INTERVAL '1' HOUR)
                """,
                "E2E-EXPIRED", "MSG-EXPIRED");

        int deleted = cleanupService.sweepExpired();

        assertThat(deleted).isEqualTo(1);
        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE end_to_end_id = ?",
                Integer.class, "E2E-EXPIRED");
        assertThat(remaining).isZero();
    }

    @Test
    void sweepLeavesUnexpiredRowsAlone() {
        // Live row: expires_at is 47 hours in the future
        idempotencyService.recordOutcome(
                "E2E-LIVE", Pacs002Message.accepted("E2E-LIVE", "TXN-LIVE"));

        cleanupService.sweepExpired();

        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE end_to_end_id = ?",
                Integer.class, "E2E-LIVE");
        assertThat(remaining).isEqualTo(1);
    }

    @Test
    void sweepWithEmptyTableIsSafe() {
        int deleted = cleanupService.sweepExpired();
        assertThat(deleted).isZero();
    }

    @Test
    void mixedExpiredAndLiveRowsAreSweptCorrectly() {
        jdbc.update("""
                INSERT INTO idempotency_keys
                    (end_to_end_id, message_id, response_status, processed_at, expires_at)
                VALUES (?, ?, 'ACSC', NOW() - INTERVAL '50' HOUR, NOW() - INTERVAL '2' HOUR)
                """,
                "E2E-MIX-OLD", "MSG-MIX-OLD");
        idempotencyService.recordOutcome(
                "E2E-MIX-NEW", Pacs002Message.accepted("E2E-MIX-NEW", "TXN-MIX-NEW"));

        int deleted = cleanupService.sweepExpired();

        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE end_to_end_id = 'E2E-MIX-NEW'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE end_to_end_id = 'E2E-MIX-OLD'",
                Integer.class)).isZero();
    }

    @Test
    void expiredKeyDoesNotBlockLegitimateResubmissionAfterSweep() {
        // Simulate a payment processed 49 hours ago whose record has expired
        jdbc.update("""
                INSERT INTO idempotency_keys
                    (end_to_end_id, message_id, response_status, processed_at, expires_at)
                VALUES (?, ?, 'ACSC', NOW() - INTERVAL '49' HOUR, NOW() - INTERVAL '1' HOUR)
                """,
                "E2E-RESUBMIT", "MSG-RESUBMIT");

        cleanupService.sweepExpired();

        // After the sweep, checkDuplicate must return empty — the institution
        // is free to issue a new payment with the same EndToEndId (FedNow
        // dedup window has elapsed).
        Optional<Pacs002Message> dup = idempotencyService.checkDuplicate("E2E-RESUBMIT");
        assertThat(dup).isEmpty();

        // And the new submission can be recorded without conflict
        idempotencyService.recordOutcome(
                "E2E-RESUBMIT", Pacs002Message.accepted("E2E-RESUBMIT", "TXN-RESUBMIT-NEW"));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE end_to_end_id = 'E2E-RESUBMIT'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
