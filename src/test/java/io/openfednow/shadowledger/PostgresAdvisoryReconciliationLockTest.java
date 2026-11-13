package io.openfednow.shadowledger;

import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import io.openfednow.shadowledger.ReconciliationService.Outcome;
import io.openfednow.shadowledger.ReconciliationService.ReconciliationAttempt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PostgresAdvisoryReconciliationLock} against a real PostgreSQL
 * (Testcontainers) — issue #68, PR 3.
 *
 * <p>Each {@code new PostgresAdvisoryReconciliationLock(dataSource)} and each
 * {@link ReconciliationService} built here stands in for a separate instance:
 * they share only the database, exactly like pods in a deployment.
 */
class PostgresAdvisoryReconciliationLockTest extends AbstractInfrastructureIntegrationTest {

    /** A bigint advisory key shows up in pg_locks as classid (high) / objid (low) / objsubid 1. */
    private static final String LOCK_FILTER = "WHERE locktype = 'advisory' AND classid = ?::bigint::oid "
            + "AND objid = ?::bigint::oid AND objsubid = 1";
    private static final long CLASS_ID = PostgresAdvisoryReconciliationLock.LOCK_KEY >>> 32;
    private static final long OBJ_ID = PostgresAdvisoryReconciliationLock.LOCK_KEY & 0xFFFFFFFFL;

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private ReconciliationLock configuredLock;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
    }

    @AfterEach
    void releaseStrayLocks() {
        // A failed assertion must not leave the lock held for the next test.
        // Advisory locks belong to sessions, so end any session still holding it.
        jdbc.queryForList(
                "SELECT pg_terminate_backend(pid) FROM pg_locks " + LOCK_FILTER
                        + " AND pid <> pg_backend_pid()",
                CLASS_ID, OBJ_ID);
    }

    @Test
    void postgresDeploymentsGetTheAdvisoryLock() {
        assertThat(configuredLock).isInstanceOf(PostgresAdvisoryReconciliationLock.class);
    }

    @Test
    void onlyOneInstanceHoldsTheLockAtATime() {
        PostgresAdvisoryReconciliationLock podA = new PostgresAdvisoryReconciliationLock(dataSource);
        PostgresAdvisoryReconciliationLock podB = new PostgresAdvisoryReconciliationLock(dataSource);

        Optional<ReconciliationLock.Lease> leaseA = podA.tryAcquire();
        assertThat(leaseA).isPresent();
        assertThat(podB.tryAcquire()).isEmpty();

        leaseA.get().close();
        Optional<ReconciliationLock.Lease> leaseB = podB.tryAcquire();
        assertThat(leaseB).isPresent();
        leaseB.get().close();
    }

    @Test
    void leaseReportsItselfHeldUntilClosed() {
        ReconciliationLock.Lease lease = new PostgresAdvisoryReconciliationLock(dataSource).tryAcquire().orElseThrow();

        assertThat(lease.stillHeld()).isTrue();
        lease.close();
        assertThat(lease.stillHeld()).isFalse();
    }

    @Test
    void closingALeaseTwiceIsHarmless() {
        ReconciliationLock.Lease lease = new PostgresAdvisoryReconciliationLock(dataSource).tryAcquire().orElseThrow();

        lease.close();
        assertThatCode(lease::close).doesNotThrowAnyException();
        // and the lock really is free
        new PostgresAdvisoryReconciliationLock(dataSource).tryAcquire().orElseThrow().close();
    }

    @Test
    void lockIsReleasedWhenTheHoldingSessionDies() throws Exception {
        PostgresAdvisoryReconciliationLock podA = new PostgresAdvisoryReconciliationLock(dataSource);
        PostgresAdvisoryReconciliationLock podB = new PostgresAdvisoryReconciliationLock(dataSource);
        ReconciliationLock.Lease leaseA = podA.tryAcquire().orElseThrow();

        // Simulate pod A crashing mid-cycle: its database session is killed and
        // it never gets to release the lock.
        assertThat(leaseA.stillHeld()).isTrue();
        List<Integer> holders = jdbc.queryForList(
                "SELECT pid FROM pg_locks " + LOCK_FILTER + " AND granted", Integer.class, CLASS_ID, OBJ_ID);
        assertThat(holders).hasSize(1);
        jdbc.queryForList("SELECT pg_terminate_backend(?)", holders.get(0));

        Optional<ReconciliationLock.Lease> leaseB = Optional.empty();
        for (int i = 0; i < 100 && leaseB.isEmpty(); i++) {
            leaseB = podB.tryAcquire();
            if (leaseB.isEmpty()) Thread.sleep(50);
        }
        assertThat(leaseB).as("lock freed by the dead session").isPresent();
        // the dead pod learns it lost the lock — its cycle stops at the next check
        assertThat(leaseA.stillHeld()).isFalse();
        // and its late cleanup must not throw or release pod B's lock
        assertThatCode(leaseA::close).doesNotThrowAnyException();
        assertThat(leaseB.get().stillHeld()).isTrue();
        leaseB.get().close();
    }

    @Test
    void failedUnlockOnALiveSessionNeverLeaksTheLockIntoThePool() {
        // From the bug bash: the unlock fails while the session is alive. The
        // connection must be aborted, not returned to the pool still holding
        // the lock — that would make every instance skip reconciliation forever.
        DataSource unlockFails = mock(DataSource.class);
        try {
            when(unlockFails.getConnection()).thenAnswer(inv -> failingUnlock(dataSource.getConnection()));
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }

        ReconciliationLock.Lease lease = new PostgresAdvisoryReconciliationLock(unlockFails).tryAcquire().orElseThrow();
        lease.close();

        Integer holders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_locks " + LOCK_FILTER + " AND granted", Integer.class, CLASS_ID, OBJ_ID);
        assertThat(holders).as("no pooled session may keep the lock").isZero();
        new PostgresAdvisoryReconciliationLock(dataSource).tryAcquire().orElseThrow().close();
    }

    private static java.sql.Connection failingUnlock(java.sql.Connection real) {
        return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                java.sql.Connection.class.getClassLoader(), new Class<?>[]{java.sql.Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") && String.valueOf(args[0]).contains("unlock")) {
                        throw new java.sql.SQLException("simulated unlock failure");
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Test
    void twoInstancesNeverRunAReconciliationCycleAtTheSameTime() throws Exception {
        insertPendingRow("ACC-LOCK-1");

        CountDownLatch podAInsideCycle = new CountDownLatch(1);
        CountDownLatch releasePodA = new CountDownLatch(1);
        CoreBankingAdapter coreA = mock(CoreBankingAdapter.class);
        when(coreA.isCoreSystemAvailable()).thenReturn(true);
        when(coreA.getAvailableBalance(any())).thenAnswer(inv -> {
            podAInsideCycle.countDown();
            releasePodA.await(10, TimeUnit.SECONDS);
            return BigDecimal.ZERO;
        });
        ReconciliationService podA = pod(coreA);
        ReconciliationService podB = pod(onlineCore());

        CompletableFuture<ReconciliationAttempt> first =
                CompletableFuture.supplyAsync(podA::attemptReconciliation);
        assertThat(podAInsideCycle.await(10, TimeUnit.SECONDS)).isTrue();

        // pod B notices the recovery while pod A is mid-cycle
        ReconciliationAttempt second = podB.attemptReconciliation();
        assertThat(second.outcome()).isEqualTo(Outcome.SKIPPED_ELSEWHERE);

        releasePodA.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.COMPLETED);

        // with pod A done, pod B can run (and finds nothing left to do)
        ReconciliationAttempt retry = podB.attemptReconciliation();
        assertThat(retry.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(retry.report().transactionsReplayed()).isZero();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ReconciliationService pod(CoreBankingAdapter core) {
        ShadowLedger ledger = mock(ShadowLedger.class);
        when(ledger.getAvailableBalance(any())).thenReturn(BigDecimal.ZERO);
        return new ReconciliationService(ledger, jdbc, core, txManager, 100,
                new PostgresAdvisoryReconciliationLock(dataSource));
    }

    private static CoreBankingAdapter onlineCore() {
        CoreBankingAdapter core = mock(CoreBankingAdapter.class);
        when(core.isCoreSystemAvailable()).thenReturn(true);
        when(core.getAvailableBalance(any())).thenReturn(BigDecimal.ZERO);
        return core;
    }

    private void insertPendingRow(String accountId) {
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES (?, ?, ?, 'DEBIT', 10.00, 100.00, 90.00, FALSE)
                """, "TXN-" + accountId, "TXN-" + accountId, accountId);
    }
}
