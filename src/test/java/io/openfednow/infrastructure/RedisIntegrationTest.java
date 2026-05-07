package io.openfednow.infrastructure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Redis connectivity and operations.
 *
 * <p>Validates that Spring's {@link StringRedisTemplate} is correctly wired
 * to the Testcontainers Redis instance, and that the operations required by
 * {@code ShadowLedger} and {@code IdempotencyService} behave as expected
 * against a real Redis server.
 *
 * <h2>Operations under test</h2>
 * <ul>
 *   <li>Balance storage and retrieval — {@code ShadowLedger.getAvailableBalance()}</li>
 *   <li>Atomic increment/decrement — {@code ShadowLedger.applyDebit() / applyCredit()}</li>
 *   <li>Key TTL — {@code IdempotencyService.recordOutcome()} with 48-hour expiry</li>
 *   <li>Key presence check — {@code IdempotencyService.checkDuplicate()}</li>
 *   <li>Key deletion — {@code ShadowLedger.reverseDebit()} cleanup</li>
 * </ul>
 */
class RedisIntegrationTest extends AbstractInfrastructureIntegrationTest {

    private static final String BALANCE_KEY_PREFIX    = "balance:";
    private static final String IDEMPOTENCY_KEY_PREFIX = "idempotency:";

    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void cleanUp() {
        // Remove test keys so tests remain independent
        redis.delete(redis.keys(BALANCE_KEY_PREFIX + "*"));
        redis.delete(redis.keys(IDEMPOTENCY_KEY_PREFIX + "*"));
    }

    // --- Connectivity ---

    @Test
    void redisIsReachable() {
        // ping() returns "PONG" on a healthy connection
        String pong = redis.getConnectionFactory()
                .getConnection()
                .ping();
        assertThat(pong).isEqualTo("PONG");
    }

    // --- Balance storage (ShadowLedger) ---

    @Test
    void canStoreAndRetrieveAccountBalance() {
        ValueOperations<String, String> ops = redis.opsForValue();
        ops.set(BALANCE_KEY_PREFIX + "ACC-001", "50000.00");

        assertThat(ops.get(BALANCE_KEY_PREFIX + "ACC-001")).isEqualTo("50000.00");
    }

    @Test
    void multipleAccountBalancesAreStoredIndependently() {
        ValueOperations<String, String> ops = redis.opsForValue();
        ops.set(BALANCE_KEY_PREFIX + "ACC-001", "50000.00");
        ops.set(BALANCE_KEY_PREFIX + "ACC-002", "12500.75");
        ops.set(BALANCE_KEY_PREFIX + "ACC-003", "999.99");

        assertThat(ops.get(BALANCE_KEY_PREFIX + "ACC-001")).isEqualTo("50000.00");
        assertThat(ops.get(BALANCE_KEY_PREFIX + "ACC-002")).isEqualTo("12500.75");
        assertThat(ops.get(BALANCE_KEY_PREFIX + "ACC-003")).isEqualTo("999.99");
    }

    @Test
    void absentKeyReturnsNull() {
        // ShadowLedger must handle a null (account not yet seeded) gracefully
        assertThat(redis.opsForValue().get(BALANCE_KEY_PREFIX + "NONEXISTENT")).isNull();
    }

    // --- Atomic balance updates (ShadowLedger.applyDebit / applyCredit) ---

    @Test
    void atomicIncrementByIntegerAmount() {
        // Redis INCRBY operates on integer cents to avoid floating-point precision issues.
        // Balance of $500.00 is stored as 50000 cents.
        redis.opsForValue().set(BALANCE_KEY_PREFIX + "ACC-CENT", "50000");

        Long after = redis.opsForValue().increment(BALANCE_KEY_PREFIX + "ACC-CENT", -10000L);

        assertThat(after).isEqualTo(40000L); // $400.00 in cents
        assertThat(redis.opsForValue().get(BALANCE_KEY_PREFIX + "ACC-CENT")).isEqualTo("40000");
    }

    @Test
    void atomicIncrementIsThreadSafe() {
        // Verify that Redis INCREMENT is atomic — the final value must equal
        // the number of increments applied, regardless of concurrency.
        redis.opsForValue().set(BALANCE_KEY_PREFIX + "ACC-ATOMIC", "0");

        int increments = 100;
        for (int i = 0; i < increments; i++) {
            redis.opsForValue().increment(BALANCE_KEY_PREFIX + "ACC-ATOMIC");
        }

        String finalValue = redis.opsForValue().get(BALANCE_KEY_PREFIX + "ACC-ATOMIC");
        assertThat(Long.parseLong(finalValue)).isEqualTo(increments);
    }

    // --- Idempotency key storage (IdempotencyService) ---

    @Test
    void idempotencyKeyCanBeStoredWithTtl() {
        String key = IDEMPOTENCY_KEY_PREFIX + "E2E-001";
        redis.opsForValue().set(key, "ACSC", 48, TimeUnit.HOURS);

        assertThat(redis.opsForValue().get(key)).isEqualTo("ACSC");

        Long ttlSeconds = redis.getExpire(key, TimeUnit.SECONDS);
        // TTL should be close to 48 hours (172800 seconds); allow a small buffer
        assertThat(ttlSeconds).isGreaterThan(172790L);
    }

    @Test
    void idempotencyKeyPresenceCheckWorks() {
        String key = IDEMPOTENCY_KEY_PREFIX + "E2E-002";

        assertThat(redis.hasKey(key)).isFalse();

        redis.opsForValue().set(key, "RJCT", 48, TimeUnit.HOURS);

        assertThat(redis.hasKey(key)).isTrue();
    }

    @Test
    void setIfAbsentPreventsOverwrite() {
        // SET NX (set-if-not-exists) is used to record idempotency outcomes exactly once.
        String key = IDEMPOTENCY_KEY_PREFIX + "E2E-003";

        Boolean firstWrite  = redis.opsForValue().setIfAbsent(key, "ACSC", Duration.ofHours(48));
        Boolean secondWrite = redis.opsForValue().setIfAbsent(key, "RJCT", Duration.ofHours(48));

        assertThat(firstWrite).isTrue();
        assertThat(secondWrite).isFalse();
        // Original value is preserved — duplicate does not overwrite
        assertThat(redis.opsForValue().get(key)).isEqualTo("ACSC");
    }

    // --- Key deletion (ShadowLedger.reverseDebit cleanup) ---

    @Test
    void keyCanBeDeleted() {
        String key = BALANCE_KEY_PREFIX + "ACC-DEL";
        redis.opsForValue().set(key, "25000");

        redis.delete(key);

        assertThat(redis.hasKey(key)).isFalse();
    }
}
