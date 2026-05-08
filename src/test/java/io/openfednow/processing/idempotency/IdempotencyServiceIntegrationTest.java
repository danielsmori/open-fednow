package io.openfednow.processing.idempotency;

import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import io.openfednow.iso20022.Pacs002Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link IdempotencyService} — issue #12.
 *
 * <p>Covers duplicate detection via Redis (fast path), database fallback
 * (Redis-miss scenario), TTL correctness, and concurrent recording safety.
 *
 * <p>Uses Testcontainers Redis + PostgreSQL via {@link AbstractInfrastructureIntegrationTest}.
 */
class IdempotencyServiceIntegrationTest extends AbstractInfrastructureIntegrationTest {

    @Autowired private IdempotencyService idempotencyService;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        redis.delete(redis.keys("idempotency:*"));
        jdbc.update("DELETE FROM idempotency_keys");
    }

    // ── First submission ──────────────────────────────────────────────────────

    @Test
    void firstSubmissionIsNotADuplicate() {
        assertThat(idempotencyService.checkDuplicate("E2E-NEW-001")).isEmpty();
    }

    // ── Happy-path duplicate detection ───────────────────────────────────────

    @Test
    void recordedACSCOutcomeIsReturnedOnDuplicate() {
        Pacs002Message response = Pacs002Message.accepted("E2E-001", "TXN-001");
        idempotencyService.recordOutcome("E2E-001", response);

        Optional<Pacs002Message> duplicate = idempotencyService.checkDuplicate("E2E-001");

        assertThat(duplicate).isPresent();
        assertThat(duplicate.get().getTransactionStatus())
                .isEqualTo(Pacs002Message.TransactionStatus.ACSC);
    }

    @Test
    void recordedRJCTOutcomePreservesReasonCodeOnDuplicate() {
        Pacs002Message rejected = Pacs002Message.rejected("E2E-002", "TXN-002", "AM04", "Insufficient funds");
        idempotencyService.recordOutcome("E2E-002", rejected);

        Optional<Pacs002Message> duplicate = idempotencyService.checkDuplicate("E2E-002");

        assertThat(duplicate).isPresent();
        assertThat(duplicate.get().getTransactionStatus())
                .isEqualTo(Pacs002Message.TransactionStatus.RJCT);
        assertThat(duplicate.get().getRejectReasonCode()).isEqualTo("AM04");
    }

    @Test
    void recordedACSPOutcomeIsReturnedOnDuplicate() {
        Pacs002Message provisional = Pacs002Message.builder()
                .originalEndToEndId("E2E-003")
                .originalTransactionId("TXN-003")
                .transactionStatus(Pacs002Message.TransactionStatus.ACSP)
                .build();
        idempotencyService.recordOutcome("E2E-003", provisional);

        Optional<Pacs002Message> duplicate = idempotencyService.checkDuplicate("E2E-003");

        assertThat(duplicate).isPresent();
        assertThat(duplicate.get().getTransactionStatus())
                .isEqualTo(Pacs002Message.TransactionStatus.ACSP);
    }

    // ── Database fallback (Redis key expired / Redis restart) ─────────────────

    @Test
    void redisMissFallsBackToDatabase() {
        Pacs002Message response = Pacs002Message.accepted("E2E-FALLBACK", "TXN-FALLBACK");
        idempotencyService.recordOutcome("E2E-FALLBACK", response);

        // Manually evict the Redis key to simulate TTL expiry or Redis restart
        redis.delete("idempotency:E2E-FALLBACK");
        assertThat(redis.hasKey("idempotency:E2E-FALLBACK")).isFalse();

        // DB row must be found and response reconstructed
        Optional<Pacs002Message> duplicate = idempotencyService.checkDuplicate("E2E-FALLBACK");

        assertThat(duplicate).isPresent();
        assertThat(duplicate.get().getTransactionStatus())
                .isEqualTo(Pacs002Message.TransactionStatus.ACSC);
    }

    @Test
    void redisMissFallbackPreservesRJCTReasonCode() {
        Pacs002Message rejected = Pacs002Message.rejected("E2E-FALLBACK-RJCT", "TXN-FALLBACK-RJCT",
                "AC04", "Closed account");
        idempotencyService.recordOutcome("E2E-FALLBACK-RJCT", rejected);

        redis.delete("idempotency:E2E-FALLBACK-RJCT");

        Optional<Pacs002Message> duplicate = idempotencyService.checkDuplicate("E2E-FALLBACK-RJCT");

        assertThat(duplicate).isPresent();
        assertThat(duplicate.get().getTransactionStatus())
                .isEqualTo(Pacs002Message.TransactionStatus.RJCT);
        assertThat(duplicate.get().getRejectReasonCode()).isEqualTo("AC04");
    }

    // ── TTL correctness ───────────────────────────────────────────────────────

    @Test
    void redisKeyExpiresAfter48Hours() {
        idempotencyService.recordOutcome("E2E-TTL", Pacs002Message.accepted("E2E-TTL", "TXN-TTL"));

        Long ttlSeconds = redis.getExpire("idempotency:E2E-TTL", TimeUnit.SECONDS);

        // Allow a small buffer for processing time (must be > 47h59m)
        assertThat(ttlSeconds).isGreaterThan(47 * 3600L);
    }

    @Test
    void databaseRowHas48HourExpiryTimestamp() {
        idempotencyService.recordOutcome("E2E-DB-TTL", Pacs002Message.accepted("E2E-DB-TTL", "TXN-DB-TTL"));

        String expiresAt = jdbc.queryForObject(
                "SELECT expires_at FROM idempotency_keys WHERE end_to_end_id = 'E2E-DB-TTL'",
                String.class);

        assertThat(expiresAt).isNotNull();
        // expires_at must be in the future (roughly now + 48h)
        Integer futureCheck = jdbc.queryForObject(
                "SELECT CASE WHEN expires_at > NOW() + INTERVAL '47 hours' THEN 1 ELSE 0 END " +
                "FROM idempotency_keys WHERE end_to_end_id = 'E2E-DB-TTL'",
                Integer.class);
        assertThat(futureCheck).isEqualTo(1);
    }

    // ── Concurrent recording safety ───────────────────────────────────────────

    @Test
    void concurrentRecordingsOfSameOutcomeAreSafe() throws Exception {
        String e2eId = "E2E-CONCURRENT";
        Pacs002Message response = Pacs002Message.accepted(e2eId, "TXN-CONCURRENT");

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> idempotencyService.recordOutcome(e2eId, response)));
        }
        for (Future<?> f : futures) f.get();
        executor.shutdown();

        // ON CONFLICT DO NOTHING ensures exactly one row regardless of concurrency
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE end_to_end_id = ?",
                Integer.class, e2eId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentChecksDontReturnFalsePositives() throws Exception {
        // 10 threads check a key that doesn't exist yet — all must get empty
        String e2eId = "E2E-CONCURRENT-CHECK";
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<Optional<Pacs002Message>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> idempotencyService.checkDuplicate(e2eId)));
        }

        for (Future<Optional<Pacs002Message>> f : futures) {
            assertThat(f.get()).isEmpty();
        }
        executor.shutdown();
    }

    // ── Database persistence ──────────────────────────────────────────────────

    @Test
    void outcomeIsPersistedToDatabase() {
        idempotencyService.recordOutcome("E2E-DB", Pacs002Message.accepted("E2E-DB", "TXN-DB"));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE end_to_end_id = 'E2E-DB'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void secondRecordOfSameE2EDoesNotThrowOrDuplicate() {
        Pacs002Message response = Pacs002Message.accepted("E2E-DUPE-DB", "TXN-DUPE-DB");

        idempotencyService.recordOutcome("E2E-DUPE-DB", response);
        idempotencyService.recordOutcome("E2E-DUPE-DB", response); // must not throw

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE end_to_end_id = 'E2E-DUPE-DB'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
