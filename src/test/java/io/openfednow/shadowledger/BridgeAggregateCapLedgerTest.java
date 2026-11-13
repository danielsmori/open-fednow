package io.openfednow.shadowledger;

import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import io.openfednow.shadowledger.ShadowLedger.CappedDebitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ledger-level integration tests for the bridge-mode aggregate send cap —
 * issue #68, PR 1.
 *
 * <p>Covers the component's invariants in isolation, before it is wired into
 * the outbound dispatch path:
 *
 * <ul>
 *   <li>the cap check and the debit are one atomic Redis transaction, so the
 *       counted aggregate can never exceed the cap under concurrency;</li>
 *   <li>a reversal releases exactly what was counted, exactly once;</li>
 *   <li>a release after the window was cleared cannot touch a newer window
 *       (fail-closed window scoping);</li>
 *   <li>rejections mutate nothing.</li>
 * </ul>
 *
 * <p>The end-to-end test through {@code MessageRouter.routeOutbound}
 * ({@code BridgeModeAggregateCapTest}) lands with PR 2.
 */
class BridgeAggregateCapLedgerTest extends AbstractInfrastructureIntegrationTest {

    @Autowired private ShadowLedger shadowLedger;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    void cleanup() {
        Set<String> keys = redis.keys("balance:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        Set<String> aggregates = redis.keys("bridge:aggregate:*");
        if (aggregates != null && !aggregates.isEmpty()) redis.delete(aggregates);
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
    }

    private void seed(String accountId, long cents) {
        redis.opsForValue().set("balance:" + accountId, String.valueOf(cents));
    }

    // ── Counting ──────────────────────────────────────────────────────────────

    @Test
    void cappedDebitDebitsBalanceAndCountsTowardAggregate() {
        seed("ACC-AGG-COUNT", 100_000); // $1000.00

        CappedDebitResult first = shadowLedger.applyDebitCapped(
                "ACC-AGG-COUNT", new BigDecimal("150.00"), "TXN-AGG-1", 50_000);
        CappedDebitResult second = shadowLedger.applyDebitCapped(
                "ACC-AGG-COUNT", new BigDecimal("100.00"), "TXN-AGG-2", 50_000);

        assertThat(first).isEqualTo(CappedDebitResult.APPLIED);
        assertThat(second).isEqualTo(CappedDebitResult.APPLIED);
        assertThat(redis.opsForValue().get("balance:ACC-AGG-COUNT")).isEqualTo("75000");

        BridgeAggregateView view = shadowLedger.getAggregateView("ACC-AGG-COUNT");
        assertThat(view).isNotNull();
        assertThat(view.totalCounted()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(view.countedSends()).isEqualTo(2);
        assertThat(view.openedAt()).isNotNull();

        Integer debitRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log "
                        + "WHERE account_id = 'ACC-AGG-COUNT' AND transaction_type = 'DEBIT'",
                Integer.class);
        assertThat(debitRows).isEqualTo(2);
    }

    @Test
    void debitExceedingCapIsRejectedWithoutAnyMutation() {
        seed("ACC-AGG-CAP", 100_000); // $1000.00 — balance is NOT the constraint

        assertThat(shadowLedger.applyDebitCapped(
                "ACC-AGG-CAP", new BigDecimal("200.00"), "TXN-CAP-1", 25_000))
                .isEqualTo(CappedDebitResult.APPLIED);
        assertThat(shadowLedger.applyDebitCapped(
                "ACC-AGG-CAP", new BigDecimal("100.00"), "TXN-CAP-2", 25_000))
                .isEqualTo(CappedDebitResult.AGGREGATE_CAP_EXCEEDED);

        // Only the first debit happened — balance, aggregate and audit log agree.
        assertThat(redis.opsForValue().get("balance:ACC-AGG-CAP")).isEqualTo("80000");
        BridgeAggregateView view = shadowLedger.getAggregateView("ACC-AGG-CAP");
        assertThat(view.totalCounted()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(view.countedSends()).isEqualTo(1);
        Integer debitRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log "
                        + "WHERE account_id = 'ACC-AGG-CAP' AND transaction_type = 'DEBIT'",
                Integer.class);
        assertThat(debitRows).isEqualTo(1);
    }

    @Test
    void exactCapIsAllowedButNotACentMore() {
        seed("ACC-AGG-EXACT", 100_000);

        assertThat(shadowLedger.applyDebitCapped(
                "ACC-AGG-EXACT", new BigDecimal("250.00"), "TXN-EXACT-1", 25_000))
                .isEqualTo(CappedDebitResult.APPLIED);
        assertThat(shadowLedger.applyDebitCapped(
                "ACC-AGG-EXACT", new BigDecimal("0.01"), "TXN-EXACT-2", 25_000))
                .isEqualTo(CappedDebitResult.AGGREGATE_CAP_EXCEEDED);
    }

    @Test
    void insufficientFundsIsReportedAsValueWithoutOpeningAWindow() {
        seed("ACC-AGG-NSF", 5_000); // $50.00

        CappedDebitResult result = shadowLedger.applyDebitCapped(
                "ACC-AGG-NSF", new BigDecimal("100.00"), "TXN-NSF-1", 200_000);

        assertThat(result).isEqualTo(CappedDebitResult.INSUFFICIENT_FUNDS);
        assertThat(redis.opsForValue().get("balance:ACC-AGG-NSF")).isEqualTo("5000");
        assertThat(shadowLedger.getAggregateView("ACC-AGG-NSF")).isNull();
    }

    // ── Concurrency ───────────────────────────────────────────────────────────

    @Test
    void concurrentCappedDebitsNeverExceedTheCap() throws Exception {
        // 10 threads × $100 against a $500 cap with ample balance. Under
        // optimistic locking some threads may also exhaust their retries and
        // throw; the invariants are (a) counted aggregate never exceeds the cap
        // and (b) balance, aggregate and audit log agree on what was applied.
        seed("ACC-AGG-CONC", 1_000_000); // $10,000.00
        int threadCount = 10;
        long capCents = 50_000; // $500.00
        BigDecimal debitAmount = new BigDecimal("100.00");
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger capRejected = new AtomicInteger();
        AtomicInteger retryExhausted = new AtomicInteger();

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
                    CappedDebitResult r = shadowLedger.applyDebitCapped(
                            "ACC-AGG-CONC", debitAmount, "TXN-CONC-" + idx, capCents);
                    if (r == CappedDebitResult.APPLIED) applied.incrementAndGet();
                    if (r == CappedDebitResult.AGGREGATE_CAP_EXCEEDED) capRejected.incrementAndGet();
                } catch (IllegalStateException e) {
                    retryExhausted.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        executor.shutdown();

        BridgeAggregateView view = shadowLedger.getAggregateView("ACC-AGG-CONC");
        long countedCents = view == null ? 0
                : view.totalCounted().movePointRight(2).longValueExact();

        // (a) the cap held
        assertThat(countedCents).isLessThanOrEqualTo(capCents);
        // (b) everything agrees on what was applied
        assertThat(countedCents).isEqualTo(applied.get() * 10_000L);
        long remaining = Long.parseLong(redis.opsForValue().get("balance:ACC-AGG-CONC"));
        assertThat(remaining).isEqualTo(1_000_000L - applied.get() * 10_000L);
        Integer debitRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log "
                        + "WHERE account_id = 'ACC-AGG-CONC' AND transaction_type = 'DEBIT'",
                Integer.class);
        assertThat(debitRows).isEqualTo(applied.get());
        // every attempt is accounted for
        assertThat(applied.get() + capRejected.get() + retryExhausted.get())
                .isEqualTo(threadCount);
    }

    // ── Release on reversal ───────────────────────────────────────────────────

    @Test
    void reversalReleasesExactlyWhatWasCounted() {
        seed("ACC-AGG-REL", 100_000);
        shadowLedger.applyDebitCapped("ACC-AGG-REL", new BigDecimal("150.00"), "TXN-REL-1", 50_000);
        shadowLedger.applyDebitCapped("ACC-AGG-REL", new BigDecimal("50.00"), "TXN-REL-2", 50_000);

        shadowLedger.reverseDebit("TXN-REL-1");

        assertThat(redis.opsForValue().get("balance:ACC-AGG-REL")).isEqualTo("95000");
        BridgeAggregateView view = shadowLedger.getAggregateView("ACC-AGG-REL");
        assertThat(view.totalCounted()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(view.countedSends()).isEqualTo(1);
        // the released amount is available to new sends again
        assertThat(shadowLedger.applyDebitCapped(
                "ACC-AGG-REL", new BigDecimal("150.00"), "TXN-REL-3", 50_000))
                .isEqualTo(CappedDebitResult.APPLIED);
    }

    @Test
    void duplicateReversalReleasesOnlyOnce() {
        seed("ACC-AGG-DUP", 100_000);
        shadowLedger.applyDebitCapped("ACC-AGG-DUP", new BigDecimal("100.00"), "TXN-DUP-1", 50_000);
        shadowLedger.applyDebitCapped("ACC-AGG-DUP", new BigDecimal("100.00"), "TXN-DUP-2", 50_000);

        shadowLedger.reverseDebit("TXN-DUP-1");
        shadowLedger.reverseDebit("TXN-DUP-1"); // compensation retry / duplicate delivery

        BridgeAggregateView view = shadowLedger.getAggregateView("ACC-AGG-DUP");
        assertThat(view.totalCounted()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(view.countedSends()).isEqualTo(1);
        // balance credited once, not twice (existing SQL idempotency guard)
        assertThat(redis.opsForValue().get("balance:ACC-AGG-DUP")).isEqualTo("90000");
    }

    @Test
    void lateReversalAfterClearCannotTouchANewerWindow() {
        seed("ACC-AGG-LATE", 100_000);
        shadowLedger.applyDebitCapped("ACC-AGG-LATE", new BigDecimal("100.00"), "TXN-LATE-1", 50_000);

        // Reconciliation confirmed the balance and closed the window...
        shadowLedger.clearAggregate("ACC-AGG-LATE");
        // ...a new window opens with a fresh send...
        shadowLedger.applyDebitCapped("ACC-AGG-LATE", new BigDecimal("200.00"), "TXN-LATE-2", 50_000);
        // ...and only then does the old send's compensation arrive.
        shadowLedger.reverseDebit("TXN-LATE-1");

        // The balance credit still happens, but the new window is untouched.
        BridgeAggregateView view = shadowLedger.getAggregateView("ACC-AGG-LATE");
        assertThat(view.totalCounted()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(view.countedSends()).isEqualTo(1);
        assertThat(redis.opsForValue().get("balance:ACC-AGG-LATE")).isEqualTo("80000");
    }

    @Test
    void reversalOfAnUncountedDebitLeavesTheWindowAlone() {
        seed("ACC-AGG-MIX", 100_000);
        // online-mode send: plain debit, not counted
        shadowLedger.applyDebit("ACC-AGG-MIX", new BigDecimal("100.00"), "TXN-MIX-PLAIN");
        // bridge-mode send: counted
        shadowLedger.applyDebitCapped("ACC-AGG-MIX", new BigDecimal("150.00"), "TXN-MIX-CAPPED", 50_000);

        shadowLedger.reverseDebit("TXN-MIX-PLAIN");

        BridgeAggregateView view = shadowLedger.getAggregateView("ACC-AGG-MIX");
        assertThat(view.totalCounted()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(view.countedSends()).isEqualTo(1);
    }

    // ── Clearing ──────────────────────────────────────────────────────────────

    @Test
    void clearAggregateClosesTheWindowAndReportsWhetherOneExisted() {
        seed("ACC-AGG-CLR", 100_000);
        shadowLedger.applyDebitCapped("ACC-AGG-CLR", new BigDecimal("100.00"), "TXN-CLR-1", 50_000);

        assertThat(shadowLedger.clearAggregate("ACC-AGG-CLR")).isTrue();
        assertThat(shadowLedger.getAggregateView("ACC-AGG-CLR")).isNull();
        assertThat(shadowLedger.clearAggregate("ACC-AGG-CLR")).isFalse();

        // the cap starts fresh for the next window
        assertThat(shadowLedger.applyDebitCapped(
                "ACC-AGG-CLR", new BigDecimal("400.00"), "TXN-CLR-2", 50_000))
                .isEqualTo(CappedDebitResult.APPLIED);
    }
}
