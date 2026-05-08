package io.openfednow.shadowledger;

import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency integration tests for {@link ShadowLedger} — issue #16.
 *
 * <p>Verifies that Redis WATCH/MULTI/EXEC optimistic locking maintains balance
 * integrity and prevents overdrafts under concurrent payment workloads.
 *
 * <p>Thread counts for debit tests are kept at 3 (= MAX_RETRY_ATTEMPTS) to
 * guarantee all threads succeed even in the worst-case scheduling scenario
 * where only one thread succeeds per retry round. Credit tests use higher
 * concurrency because {@code INCRBY} is atomic and never conflicts.
 */
class ShadowLedgerConcurrencyTest extends AbstractInfrastructureIntegrationTest {

    @Autowired private ShadowLedger shadowLedger;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    void cleanup() {
        redis.delete(redis.keys("balance:*"));
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
    }

    // ── Concurrent debits ─────────────────────────────────────────────────────

    @Test
    void concurrentDebitsProduceExactFinalBalance() throws Exception {
        // 3 threads × $100 from $300 — thread count matches MAX_RETRY_ATTEMPTS so all
        // are guaranteed to succeed even in worst-case serial retry scheduling.
        redis.opsForValue().set("balance:ACC-CONC-DEBIT", "30000"); // $300.00
        int threadCount = 3;
        BigDecimal debitAmount = new BigDecimal("100.00");

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try { start.await(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                shadowLedger.applyDebit("ACC-CONC-DEBIT", debitAmount, "TXN-CONC-DEBIT-" + idx);
                return null;
            }));
        }

        start.countDown();
        for (Future<?> f : futures) f.get(); // all must succeed; any exception fails the test
        executor.shutdown();

        String raw = redis.opsForValue().get("balance:ACC-CONC-DEBIT");
        assertThat(raw).isEqualTo("0");
    }

    @Test
    void overdraftPreventedUnderConcurrency() throws Exception {
        // 10 threads each attempt to debit $100 from a $500 account.
        // At most 5 can succeed; the rest must be rejected (not silently drop funds).
        redis.opsForValue().set("balance:ACC-CONC-OVERDRAFT", "50000"); // $500.00
        int threadCount = 10;
        BigDecimal debitAmount = new BigDecimal("100.00");
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try { start.await(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try {
                    shadowLedger.applyDebit("ACC-CONC-OVERDRAFT", debitAmount,
                            "TXN-OVERDRAFT-" + idx);
                    successes.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<?> f : futures) f.get();
        executor.shutdown();

        String raw = redis.opsForValue().get("balance:ACC-CONC-OVERDRAFT");
        long remainingCents = Long.parseLong(raw);

        // Safety: no overdraft
        assertThat(remainingCents).isGreaterThanOrEqualTo(0);

        // Conservation: balance + debited amount == starting balance
        long debitedCents = (long) successes.get() * 10_000L;
        assertThat(remainingCents).isEqualTo(50_000L - debitedCents);

        // Cannot succeed more than 5 times ($500 / $100)
        assertThat(successes.get()).isLessThanOrEqualTo(5);

        // All 10 threads must account for (success or failure)
        assertThat(successes.get() + failures.get()).isEqualTo(threadCount);
    }

    // ── Concurrent credits ────────────────────────────────────────────────────

    @Test
    void concurrentCreditsProduceExactFinalBalance() throws Exception {
        // INCRBY is atomic in Redis — no conflicts possible regardless of thread count.
        // 100 threads × $1.00 = $100.00 final balance.
        redis.opsForValue().set("balance:ACC-CONC-CREDIT", "0");
        int threadCount = 100;
        BigDecimal creditAmount = new BigDecimal("1.00");

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try { start.await(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                shadowLedger.applyCredit("ACC-CONC-CREDIT", creditAmount, "TXN-CREDIT-" + idx);
                return null;
            }));
        }

        start.countDown();
        for (Future<?> f : futures) f.get();
        executor.shutdown();

        String raw = redis.opsForValue().get("balance:ACC-CONC-CREDIT");
        assertThat(raw).isEqualTo("10000"); // 100 × $1.00 = $100.00 = 10 000 cents
    }

    // ── Audit log integrity ───────────────────────────────────────────────────

    @Test
    void auditLogRowCountMatchesSuccessfulDebitOperations() throws Exception {
        // All 3 debits succeed → exactly 3 DEBIT rows must appear in the audit log.
        redis.opsForValue().set("balance:ACC-CONC-AUDIT", "30000"); // $300.00
        int threadCount = 3;
        BigDecimal debitAmount = new BigDecimal("100.00");

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try { start.await(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                shadowLedger.applyDebit("ACC-CONC-AUDIT", debitAmount, "TXN-AUDIT-CONC-" + idx);
                return null;
            }));
        }

        start.countDown();
        for (Future<?> f : futures) f.get();
        executor.shutdown();

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log " +
                "WHERE account_id = 'ACC-CONC-AUDIT' AND transaction_type = 'DEBIT'",
                Integer.class);
        assertThat(rowCount).isEqualTo(3);

        // Final balance must also be $0.00 (conservation check)
        assertThat(redis.opsForValue().get("balance:ACC-CONC-AUDIT")).isEqualTo("0");
    }
}
