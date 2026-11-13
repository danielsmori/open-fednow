package io.openfednow.shadowledger;

import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reconciliation against live traffic — issues #142/#144.
 *
 * <p>Payments keep flowing while a reconciliation cycle runs. These tests
 * interleave real Shadow Ledger activity with a cycle (the mocked core adapter
 * blocks inside the cycle's balance read, exactly the window the bug lived in)
 * and assert the two guarantees of the fix:
 * <ul>
 *   <li>a concurrent debit or credit is never erased by the balance
 *       correction (it is applied as a delta, not an overwrite);</li>
 *   <li>a transaction-log row written after the cycle's cutoff is not marked
 *       {@code core_confirmed} by that cycle.</li>
 * </ul>
 *
 * <p>Real Redis and PostgreSQL; the service is built directly with the
 * local-only lock, like the other reconciliation tests.
 */
class ReconciliationConsistencyTest extends AbstractInfrastructureIntegrationTest {

    private static final String ACCOUNT = "ACC-RECON-LIVE";

    @Autowired private ShadowLedger shadowLedger;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    private final CoreBankingAdapter core = mock(CoreBankingAdapter.class);
    private final CountDownLatch insideCoreRead = new CountDownLatch(1);
    private final CountDownLatch releaseCoreRead = new CountDownLatch(1);

    @BeforeEach
    void setup() {
        Set<String> keys = redis.keys("balance:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        Set<String> windows = redis.keys("bridge:aggregate:*");
        if (windows != null && !windows.isEmpty()) redis.delete(windows);
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
        jdbc.update("DELETE FROM saga_state");
        jdbc.update("DELETE FROM reconciliation_run");
        when(core.isCoreSystemAvailable()).thenReturn(true);
    }

    @Test
    void debitLandingDuringTheCoreReadIsNotErased() throws Exception {
        // Shadow and core agree at $1000; one already-applied send awaits confirmation.
        redis.opsForValue().set("balance:" + ACCOUNT, "100000");
        insertPendingRow("TXN-BEFORE", "250.00");
        blockingCoreBalance(new BigDecimal("1000.00"));

        CompletableFuture<?> cycle = CompletableFuture.runAsync(service()::reconcile);
        assertThat(insideCoreRead.await(10, TimeUnit.SECONDS)).isTrue();
        // a payment lands exactly while the cycle waits on the core
        shadowLedger.applyDebit(ACCOUNT, new BigDecimal("100.00"), "TXN-DURING");
        releaseCoreRead.countDown();
        cycle.get(10, TimeUnit.SECONDS);

        // the old SET would have restored $1000, resurrecting the spent $100
        assertThat(shadowLedger.getAvailableBalance(ACCOUNT)).isEqualByComparingTo("900.00");
        // the old blanket UPDATE would have confirmed TXN-DURING against a core
        // balance that never covered it
        assertThat(confirmed("TXN-BEFORE")).isTrue();
        assertThat(confirmed("TXN-DURING")).isFalse();
    }

    @Test
    void discrepancyCorrectionIsAppliedAsADeltaNotAnOverwrite() throws Exception {
        // Core says $1000 while the snapshot reads $500 (e.g. STIP drift the
        // other way): the correction is +$500 on top of whatever is live.
        redis.opsForValue().set("balance:" + ACCOUNT, "50000");
        insertPendingRow("TXN-BEFORE", "250.00");
        blockingCoreBalance(new BigDecimal("1000.00"));

        CompletableFuture<?> cycle = CompletableFuture.runAsync(service()::reconcile);
        assertThat(insideCoreRead.await(10, TimeUnit.SECONDS)).isTrue();
        shadowLedger.applyDebit(ACCOUNT, new BigDecimal("100.00"), "TXN-DURING");
        releaseCoreRead.countDown();
        cycle.get(10, TimeUnit.SECONDS);

        // snapshot 500 → correction +500; live balance was 400 → 900
        assertThat(shadowLedger.getAvailableBalance(ACCOUNT)).isEqualByComparingTo("900.00");
        Integer corrections = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log "
                        + "WHERE account_id = ? AND transaction_type = 'RECONCILIATION'",
                Integer.class, ACCOUNT);
        assertThat(corrections).isEqualTo(1);
        assertThat(confirmed("TXN-DURING")).isFalse();
    }

    @Test
    void rowWrittenAfterTheCycleIsConfirmedByTheNextCycleOnly() {
        redis.opsForValue().set("balance:" + ACCOUNT, "100000");
        insertPendingRow("TXN-BEFORE", "250.00");
        when(core.getAvailableBalance(anyString())).thenReturn(new BigDecimal("1000.00"));

        service().reconcile();
        shadowLedger.applyDebit(ACCOUNT, new BigDecimal("100.00"), "TXN-AFTER");
        assertThat(confirmed("TXN-AFTER")).isFalse();

        when(core.getAvailableBalance(anyString())).thenReturn(new BigDecimal("900.00"));
        service().reconcile();
        assertThat(confirmed("TXN-AFTER")).isTrue();
        assertThat(shadowLedger.getAvailableBalance(ACCOUNT)).isEqualByComparingTo("900.00");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ReconciliationService service() {
        return new ReconciliationService(shadowLedger, jdbc, core, txManager, 100,
                ReconciliationLock.LOCAL_ONLY);
    }

    private void blockingCoreBalance(BigDecimal balance) {
        when(core.getAvailableBalance(anyString())).thenAnswer(inv -> {
            insideCoreRead.countDown();
            releaseCoreRead.await(10, TimeUnit.SECONDS);
            return balance;
        });
    }

    private void insertPendingRow(String transactionId, String amount) {
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES (?, ?, ?, 'DEBIT', ?::numeric, 0, 0, FALSE)
                """, transactionId, transactionId, ACCOUNT, amount);
    }

    private boolean confirmed(String transactionId) {
        Boolean b = jdbc.queryForObject(
                "SELECT core_confirmed FROM shadow_ledger_transaction_log WHERE transaction_id = ?",
                Boolean.class, transactionId);
        return Boolean.TRUE.equals(b);
    }
}
