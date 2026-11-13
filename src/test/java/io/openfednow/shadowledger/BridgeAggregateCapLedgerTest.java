package io.openfednow.shadowledger;

import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import io.openfednow.shadowledger.ShadowLedger.CappedDebitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

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
 * <p>The end-to-end behavior through {@code MessageRouter.routeOutbound}
 * and reconciliation is covered by {@code BridgeModeAggregateCapTest}.
 */
@TestPropertySource(properties = "openfednow.bridge-mode.max-aggregate-send-amount=500.00")
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
        jdbc.update("DELETE FROM saga_state");
    }

    private void seed(String accountId, long cents) {
        redis.opsForValue().set("balance:" + accountId, String.valueOf(cents));
    }

    // ── Counting ──────────────────────────────────────────────────────────────

    @Test
    void cappedDebitDebitsBalanceAndCountsTowardAggregate() {
        seed("ACC-AGG-COUNT", 100_000); // $1000.00

        CappedDebitResult first = shadowLedger.applyDebitCapped(
                "ACC-AGG-COUNT", new BigDecimal("150.00"), "TXN-AGG-1");
        CappedDebitResult second = shadowLedger.applyDebitCapped(
                "ACC-AGG-COUNT", new BigDecimal("100.00"), "TXN-AGG-2");

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
                "ACC-AGG-CAP", new BigDecimal("400.00"), "TXN-CAP-1"))
                .isEqualTo(CappedDebitResult.APPLIED);
        assertThat(shadowLedger.applyDebitCapped(
                "ACC-AGG-CAP", new BigDecimal("200.00"), "TXN-CAP-2"))
                .isEqualTo(CappedDebitResult.AGGREGATE_CAP_EXCEEDED);

        // Only the first debit happened — balance, aggregate and audit log agree.
        assertThat(redis.opsForValue().get("balance:ACC-AGG-CAP")).isEqualTo("60000");
        BridgeAggregateView view = shadowLedger.getAggregateView("ACC-AGG-CAP");
        assertThat(view.totalCounted()).isEqualByComparingTo(new BigDecimal("400.00"));
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
                "ACC-AGG-EXACT", new BigDecimal("500.00"), "TXN-EXACT-1"))
                .isEqualTo(CappedDebitResult.APPLIED);
        assertThat(shadowLedger.applyDebitCapped(
                "ACC-AGG-EXACT", new BigDecimal("0.01"), "TXN-EXACT-2"))
                .isEqualTo(CappedDebitResult.AGGREGATE_CAP_EXCEEDED);
    }

    @Test
    void insufficientFundsIsReportedAsValueWithoutOpeningAWindow() {
        seed("ACC-AGG-NSF", 5_000); // $50.00

        CappedDebitResult result = shadowLedger.applyDebitCapped(
                "ACC-AGG-NSF", new BigDecimal("100.00"), "TXN-NSF-1");

        assertThat(result).isEqualTo(CappedDebitResult.INSUFFICIENT_FUNDS);
        assertThat(redis.opsForValue().get("balance:ACC-AGG-NSF")).isEqualTo("5000");
        assertThat(shadowLedger.getAggregateView("ACC-AGG-NSF")).isNull();
    }

    // ── Concurrency ───────────────────────────────────────────────────────────

    @Test
    void concurrentCappedDebitsNeverExceedTheCap() throws Exception {
        // 10 threads × $100 against a $500 cap with ample balance. The capped
        // debit is a single atomic script with no retry loop, so the outcome
        // is exact: 5 applied, 5 rejected for the cap, none erroring.
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
                            "ACC-AGG-CONC", debitAmount, "TXN-CONC-" + idx);
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
        // exact outcome: the cap is used to the cent and nothing errors
        assertThat(applied.get()).isEqualTo(5);
        assertThat(capRejected.get()).isEqualTo(5);
        assertThat(retryExhausted.get()).isZero();
    }

    // ── Release on reversal ───────────────────────────────────────────────────

    @Test
    void reversalReleasesExactlyWhatWasCounted() {
        seed("ACC-AGG-REL", 100_000);
        shadowLedger.applyDebitCapped("ACC-AGG-REL", new BigDecimal("150.00"), "TXN-REL-1");
        shadowLedger.applyDebitCapped("ACC-AGG-REL", new BigDecimal("50.00"), "TXN-REL-2");

        shadowLedger.reverseDebit("TXN-REL-1");

        assertThat(redis.opsForValue().get("balance:ACC-AGG-REL")).isEqualTo("95000");
        BridgeAggregateView view = shadowLedger.getAggregateView("ACC-AGG-REL");
        assertThat(view.totalCounted()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(view.countedSends()).isEqualTo(1);
        // the released amount is available to new sends again
        assertThat(shadowLedger.applyDebitCapped(
                "ACC-AGG-REL", new BigDecimal("150.00"), "TXN-REL-3"))
                .isEqualTo(CappedDebitResult.APPLIED);
    }

    @Test
    void duplicateReversalReleasesOnlyOnce() {
        seed("ACC-AGG-DUP", 100_000);
        shadowLedger.applyDebitCapped("ACC-AGG-DUP", new BigDecimal("100.00"), "TXN-DUP-1");
        shadowLedger.applyDebitCapped("ACC-AGG-DUP", new BigDecimal("100.00"), "TXN-DUP-2");

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
        shadowLedger.applyDebitCapped("ACC-AGG-LATE", new BigDecimal("100.00"), "TXN-LATE-1");

        // Reconciliation confirmed the balance and closed the window...
        shadowLedger.clearAggregate("ACC-AGG-LATE");
        // ...a new window opens with a fresh send...
        shadowLedger.applyDebitCapped("ACC-AGG-LATE", new BigDecimal("200.00"), "TXN-LATE-2");
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
        shadowLedger.applyDebitCapped("ACC-AGG-MIX", new BigDecimal("150.00"), "TXN-MIX-CAPPED");

        shadowLedger.reverseDebit("TXN-MIX-PLAIN");

        BridgeAggregateView view = shadowLedger.getAggregateView("ACC-AGG-MIX");
        assertThat(view.totalCounted()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(view.countedSends()).isEqualTo(1);
    }

    // ── Clearing ──────────────────────────────────────────────────────────────

    @Test
    void clearAggregateClosesTheWindowAndReportsWhetherOneExisted() {
        seed("ACC-AGG-CLR", 100_000);
        shadowLedger.applyDebitCapped("ACC-AGG-CLR", new BigDecimal("100.00"), "TXN-CLR-1");

        assertThat(shadowLedger.clearAggregate("ACC-AGG-CLR")).isTrue();
        assertThat(shadowLedger.getAggregateView("ACC-AGG-CLR")).isNull();
        assertThat(shadowLedger.clearAggregate("ACC-AGG-CLR")).isFalse();

        // the cap starts fresh for the next window
        assertThat(shadowLedger.applyDebitCapped(
                "ACC-AGG-CLR", new BigDecimal("400.00"), "TXN-CLR-2"))
                .isEqualTo(CappedDebitResult.APPLIED);
    }

    // ── Policy ownership and read-side helpers (PR 2) ─────────────────────────

    @Test
    void ledgerReadsTheCapFromThePolicy() {
        assertThat(shadowLedger.isAggregateCapEnabled()).isTrue();
    }

    @Test
    void cappedDebitRefusesToRunWhenTheCapIsDisabled() {
        ShadowLedger uncapped = new ShadowLedger(redis, jdbc,
                new io.openfednow.bridgemode.BridgeAggregateCapPolicy("", 6, true));
        seed("ACC-AGG-OFF", 100_000);

        assertThat(uncapped.isAggregateCapEnabled()).isFalse();
        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> uncapped.applyDebitCapped(
                        "ACC-AGG-OFF", new BigDecimal("10.00"), "TXN-OFF-1"));
        assertThat(uncapped.wouldExceedAggregateCap("ACC-AGG-OFF", new BigDecimal("1000000.00")))
                .isFalse();
        // nothing was debited or counted
        assertThat(redis.opsForValue().get("balance:ACC-AGG-OFF")).isEqualTo("100000");
        assertThat(uncapped.hasOpenAggregate("ACC-AGG-OFF")).isFalse();
    }

    @Test
    void preCheckAgreesWithTheAtomicCheck() {
        seed("ACC-AGG-PRE", 100_000);
        shadowLedger.applyDebitCapped("ACC-AGG-PRE", new BigDecimal("300.00"), "TXN-PRE-1");

        assertThat(shadowLedger.wouldExceedAggregateCap("ACC-AGG-PRE", new BigDecimal("200.00"))).isFalse();
        assertThat(shadowLedger.wouldExceedAggregateCap("ACC-AGG-PRE", new BigDecimal("200.01"))).isTrue();
        // an account with no window is measured from zero
        assertThat(shadowLedger.wouldExceedAggregateCap("ACC-AGG-NONE", new BigDecimal("500.00"))).isFalse();
        assertThat(shadowLedger.wouldExceedAggregateCap("ACC-AGG-NONE", new BigDecimal("500.01"))).isTrue();
    }

    @Test
    void windowStaysOpenEvenWhenEverySendWasReleased() {
        // A window is closed by reconciliation, not by its total reaching zero:
        // the core still has to confirm the balance.
        seed("ACC-AGG-ZERO", 100_000);
        shadowLedger.applyDebitCapped("ACC-AGG-ZERO", new BigDecimal("100.00"), "TXN-ZERO-1");
        shadowLedger.reverseDebit("TXN-ZERO-1");

        assertThat(shadowLedger.hasOpenAggregate("ACC-AGG-ZERO")).isTrue();
        assertThat(shadowLedger.getAggregateView("ACC-AGG-ZERO").totalCounted())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void openAggregateAccountIdsListsEveryOpenWindowOnce() {
        seed("ACC-AGG-SCAN-1", 100_000);
        seed("ACC-AGG-SCAN-2", 100_000);
        shadowLedger.applyDebitCapped("ACC-AGG-SCAN-1", new BigDecimal("10.00"), "TXN-SCAN-1");
        shadowLedger.applyDebitCapped("ACC-AGG-SCAN-1", new BigDecimal("10.00"), "TXN-SCAN-2");
        shadowLedger.applyDebitCapped("ACC-AGG-SCAN-2", new BigDecimal("10.00"), "TXN-SCAN-3");

        assertThat(shadowLedger.openAggregateAccountIds())
                .containsExactlyInAnyOrder("ACC-AGG-SCAN-1", "ACC-AGG-SCAN-2");

        shadowLedger.clearAggregate("ACC-AGG-SCAN-1");
        assertThat(shadowLedger.openAggregateAccountIds()).containsExactly("ACC-AGG-SCAN-2");
    }

    // ── Closing on core confirmation (reconciliation) ─────────────────────────

    @Test
    void closeConfirmedReleasesConfirmedSendsAndClosesTheWindow() {
        seed("ACC-AGG-CC", 100_000);
        shadowLedger.applyDebitCapped("ACC-AGG-CC", new BigDecimal("100.00"), "TXN-CC-1");
        shadowLedger.applyDebitCapped("ACC-AGG-CC", new BigDecimal("50.00"), "TXN-CC-2");
        confirmAll("ACC-AGG-CC");

        assertThat(shadowLedger.closeConfirmedAggregate("ACC-AGG-CC")).isTrue();
        assertThat(shadowLedger.hasOpenAggregate("ACC-AGG-CC")).isFalse();
        // the balance is not touched by closing the window
        assertThat(redis.opsForValue().get("balance:ACC-AGG-CC")).isEqualTo("85000");
    }

    @Test
    void closeConfirmedKeepsUnconfirmedSendsCounted() {
        seed("ACC-AGG-PART", 100_000);
        shadowLedger.applyDebitCapped("ACC-AGG-PART", new BigDecimal("100.00"), "TXN-PART-1");
        confirmAll("ACC-AGG-PART");
        // counted after the confirmation: its row is unconfirmed
        shadowLedger.applyDebitCapped("ACC-AGG-PART", new BigDecimal("70.00"), "TXN-PART-2");

        assertThat(shadowLedger.closeConfirmedAggregate("ACC-AGG-PART")).isFalse();

        BridgeAggregateView view = shadowLedger.getAggregateView("ACC-AGG-PART");
        assertThat(view.totalCounted()).isEqualByComparingTo(new BigDecimal("70.00"));
        assertThat(view.countedSends()).isEqualTo(1);
    }

    @Test
    void partialCloseKeepsTheOriginalOpenedAtSoAStuckWindowKeepsAging() {
        seed("ACC-AGG-AGE", 100_000);
        shadowLedger.applyDebitCapped("ACC-AGG-AGE", new BigDecimal("100.00"), "TXN-AGE-1");
        shadowLedger.applyDebitCapped("ACC-AGG-AGE", new BigDecimal("50.00"), "TXN-AGE-2");
        // pretend the window opened long ago
        redis.opsForHash().put("bridge:aggregate:ACC-AGG-AGE", "opened_at", "1000");
        saga("TXN-AGE-1", "OUTCOME_UNKNOWN"); // holds the window open
        confirmAll("ACC-AGG-AGE");

        assertThat(shadowLedger.closeConfirmedAggregate("ACC-AGG-AGE")).isFalse();

        assertThat(shadowLedger.getAggregateView("ACC-AGG-AGE").openedAt())
                .isEqualTo(java.time.Instant.ofEpochSecond(1000));
    }

    @Test
    void closeConfirmedKeepsSendsWithAnUnresolvedRailOutcomeCounted() {
        seed("ACC-AGG-UNRES", 100_000);
        shadowLedger.applyDebitCapped("ACC-AGG-UNRES", new BigDecimal("100.00"), "TXN-UNRES-1");
        shadowLedger.applyDebitCapped("ACC-AGG-UNRES", new BigDecimal("40.00"), "TXN-UNRES-2");
        saga("TXN-UNRES-1", "OUTCOME_UNKNOWN");
        saga("TXN-UNRES-2", "COMPLETED");
        confirmAll("ACC-AGG-UNRES");

        assertThat(shadowLedger.closeConfirmedAggregate("ACC-AGG-UNRES")).isFalse();
        BridgeAggregateView view = shadowLedger.getAggregateView("ACC-AGG-UNRES");
        assertThat(view.totalCounted()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(view.countedSends()).isEqualTo(1);

        // once the outcome resolves, the next close releases it
        jdbc.update("UPDATE saga_state SET state = 'COMPLETED' WHERE transaction_id = 'TXN-UNRES-1'");
        assertThat(shadowLedger.closeConfirmedAggregate("ACC-AGG-UNRES")).isTrue();
    }

    @Test
    void closeConfirmedClosesAWindowWhoseSendsWereAllReversed() {
        seed("ACC-AGG-EMPTY", 100_000);
        shadowLedger.applyDebitCapped("ACC-AGG-EMPTY", new BigDecimal("100.00"), "TXN-EMPTY-1");
        shadowLedger.reverseDebit("TXN-EMPTY-1");

        assertThat(shadowLedger.closeConfirmedAggregate("ACC-AGG-EMPTY")).isTrue();
        assertThat(shadowLedger.hasOpenAggregate("ACC-AGG-EMPTY")).isFalse();
    }

    @Test
    void releasableWorkIsDetectedExactlyWhenACycleCouldHelp() {
        // nothing open: no releasable work
        assertThat(shadowLedger.hasReleasableAggregate()).isFalse();

        // a window whose send is unresolved: a cycle cannot help
        seed("ACC-AGG-RTRY", 100_000);
        shadowLedger.applyDebitCapped("ACC-AGG-RTRY", new BigDecimal("100.00"), "TXN-RTRY-1");
        saga("TXN-RTRY-1", "OUTCOME_UNKNOWN");
        confirmAll("ACC-AGG-RTRY");
        assertThat(shadowLedger.hasReleasableAggregate()).isFalse();

        // the saga resolves: now a cycle could release it
        jdbc.update("UPDATE saga_state SET state = 'COMPLETED' WHERE transaction_id = 'TXN-RTRY-1'");
        assertThat(shadowLedger.hasReleasableAggregate()).isTrue();

        shadowLedger.closeConfirmedAggregate("ACC-AGG-RTRY");
        assertThat(shadowLedger.hasReleasableAggregate()).isFalse();
    }

    @Test
    void emptyWindowCountsAsReleasableWork() {
        seed("ACC-AGG-RTRY2", 100_000);
        shadowLedger.applyDebitCapped("ACC-AGG-RTRY2", new BigDecimal("100.00"), "TXN-RTRY2-1");
        shadowLedger.reverseDebit("TXN-RTRY2-1"); // window stays open, zero counted

        assertThat(shadowLedger.hasReleasableAggregate()).isTrue();
    }

    private void confirmAll(String accountId) {
        jdbc.update("UPDATE shadow_ledger_transaction_log SET core_confirmed = TRUE WHERE account_id = ?",
                accountId);
    }

    private void saga(String transactionId, String state) {
        jdbc.update("INSERT INTO saga_state (saga_id, transaction_id, end_to_end_id, state, source_rail) "
                        + "VALUES (?, ?, ?, ?, 'FEDNOW')",
                "SAGA-" + transactionId, transactionId, "E2E-" + transactionId, state);
    }
}
