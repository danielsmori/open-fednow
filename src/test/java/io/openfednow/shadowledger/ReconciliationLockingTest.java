package io.openfednow.shadowledger;

import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.shadowledger.ReconciliationService.Outcome;
import io.openfednow.shadowledger.ReconciliationService.ReconciliationAttempt;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cross-instance locking and the detailed result of
 * {@link ReconciliationService#attemptReconciliation()} — issue #68, PR 3.
 *
 * <p>The PostgreSQL advisory lock itself is exercised against a real database
 * in {@code PostgresAdvisoryReconciliationLockTest}; here a fake lock pins how
 * the service uses it.
 */
class ReconciliationLockingTest {

    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager txManager;

    @BeforeAll
    static void runMigrations() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:reconcile_locking_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        txManager = new DataSourceTransactionManager(dataSource);
    }

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
        jdbc.update("DELETE FROM reconciliation_run");
    }

    // ── Lock usage ───────────────────────────────────────────────────────────

    @Test
    void lockHeldElsewhereSkipsTheCycleWithoutTouchingAnything() {
        CoreBankingAdapter core = core();
        insertPendingRow("ACC-A");
        ReconciliationService service = service(ledger(), core, Optional::empty);

        ReconciliationAttempt attempt = service.attemptReconciliation();

        assertThat(attempt.outcome()).isEqualTo(Outcome.SKIPPED_ELSEWHERE);
        assertThat(attempt.fullyReconciled()).isFalse();
        assertThat(attempt.report().summary()).contains("another instance");
        verify(core, never()).getAvailableBalance(any());
        assertThat(unconfirmed()).isEqualTo(1);
        assertThat(runs()).isZero();
    }

    @Test
    void unreachableLockAbortsTheCycleInsteadOfRunningUnguarded() {
        CoreBankingAdapter core = core();
        insertPendingRow("ACC-A");
        ReconciliationService service = service(ledger(), core, () -> {
            throw new IllegalStateException("database unreachable");
        });

        ReconciliationAttempt attempt = service.attemptReconciliation();

        assertThat(attempt.outcome()).isEqualTo(Outcome.ABORTED);
        assertThat(attempt.report().reconciliationSuccessful()).isFalse();
        verify(core, never()).getAvailableBalance(any());
        assertThat(unconfirmed()).isEqualTo(1);
    }

    @Test
    void leaseIsReleasedAfterACycle() {
        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        ReconciliationLock counting = () -> {
            acquired.incrementAndGet();
            return Optional.of(released::incrementAndGet);
        };
        insertPendingRow("ACC-A");

        ReconciliationAttempt attempt = service(ledger(), core(), counting).attemptReconciliation();

        assertThat(attempt.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(acquired.get()).isEqualTo(1);
        assertThat(released.get()).isEqualTo(1);
    }

    @Test
    void leaseIsReleasedEvenWhenTheCycleThrows() {
        AtomicInteger released = new AtomicInteger();
        ReconciliationLock counting = () -> Optional.of(released::incrementAndGet);
        ShadowLedger ledger = ledger();
        when(ledger.isAggregateCapEnabled()).thenReturn(true);
        when(ledger.openAggregateAccountIds()).thenThrow(new IllegalStateException("redis down"));
        ReconciliationService service = service(ledger, core(), counting);

        ReconciliationAttempt attempt = service.attemptReconciliation();

        assertThat(attempt.outcome()).isEqualTo(Outcome.ABORTED);
        assertThat(released.get()).isEqualTo(1);
    }

    @Test
    void lockLostMidCycleAbortsTheCycle() {
        // the lock's session died mid-cycle: another instance may now hold it
        ReconciliationLock lost = () -> Optional.of(new ReconciliationLock.Lease() {
            @Override public void close() { }
            @Override public boolean stillHeld() { return false; }
        });
        insertPendingRow("ACC-A");
        insertPendingRow("ACC-B");
        CoreBankingAdapter core = core();

        ReconciliationAttempt attempt = service(ledger(), core, lost).attemptReconciliation();

        assertThat(attempt.outcome()).isEqualTo(Outcome.ABORTED);
        assertThat(attempt.report().summary()).contains("lock lost");
        assertThat(attempt.fullyReconciled()).isFalse();
        // checked before the first account: nothing was reconciled without the lock
        verify(core, never()).getAvailableBalance(any());
        assertThat(unconfirmed()).isEqualTo(2);
    }

    // ── fullyReconciled ──────────────────────────────────────────────────────

    @Test
    void cleanCycleIsFullyReconciled() {
        insertPendingRow("ACC-A");

        ReconciliationAttempt attempt = service(ledger(), core(), ReconciliationLock.LOCAL_ONLY)
                .attemptReconciliation();

        assertThat(attempt.fullyReconciled()).isTrue();
    }

    @Test
    void cycleWithAFailedAccountIsNotFullyReconciled() {
        CoreBankingAdapter core = core();
        when(core.getAvailableBalance(eq("ACC-BAD"))).thenThrow(new RuntimeException("core error"));
        insertPendingRow("ACC-BAD");
        insertPendingRow("ACC-OK");

        ReconciliationAttempt attempt = service(ledger(), core, ReconciliationLock.LOCAL_ONLY)
                .attemptReconciliation();

        assertThat(attempt.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(attempt.failedAccounts()).isEqualTo(1);
        assertThat(attempt.fullyReconciled()).isFalse();
    }

    @Test
    void cycleThatDefersAWindowAccountIsNotFullyReconciled() {
        ShadowLedger ledger = ledger();
        when(ledger.isAggregateCapEnabled()).thenReturn(true);
        when(ledger.hasOpenAggregate("ACC-W")).thenReturn(true);
        when(ledger.openAggregateAccountIds()).thenReturn(List.of("ACC-W"));
        CoreBankingAdapter core = core();
        when(core.isCoreSystemAvailable()).thenReturn(false);
        insertPendingRow("ACC-W");

        ReconciliationAttempt attempt = service(ledger, core, ReconciliationLock.LOCAL_ONLY)
                .attemptReconciliation();

        assertThat(attempt.deferredAccounts()).isPositive();
        assertThat(attempt.fullyReconciled()).isFalse();
    }

    @Test
    void failedWindowReleaseIsNotFullyReconciled() {
        ShadowLedger ledger = ledger();
        when(ledger.isAggregateCapEnabled()).thenReturn(true);
        when(ledger.hasOpenAggregate("ACC-W")).thenReturn(true);
        when(ledger.openAggregateAccountIds()).thenReturn(List.of());
        when(ledger.closeConfirmedAggregate("ACC-W")).thenThrow(new IllegalStateException("redis blip"));
        insertPendingRow("ACC-W");

        ReconciliationAttempt attempt = service(ledger, core(), ReconciliationLock.LOCAL_ONLY)
                .attemptReconciliation();

        assertThat(attempt.failedAccounts()).isEqualTo(1);
        assertThat(attempt.fullyReconciled()).isFalse();
    }

    @Test
    void windowStillOpenAfterTheSweepIsNotFullyReconciled() {
        // e.g. a counted send whose saga is still OUTCOME_UNKNOWN: the account
        // stays capped, so the cycle must not settle the owed reconciliation
        ShadowLedger ledger = ledger();
        when(ledger.isAggregateCapEnabled()).thenReturn(true);
        when(ledger.hasOpenAggregate("ACC-W")).thenReturn(true);
        when(ledger.openAggregateAccountIds()).thenReturn(List.of("ACC-W"));
        when(ledger.closeConfirmedAggregate("ACC-W")).thenReturn(false);
        insertPendingRow("ACC-W");

        ReconciliationAttempt attempt = service(ledger, core(), ReconciliationLock.LOCAL_ONLY)
                .attemptReconciliation();

        assertThat(attempt.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(attempt.failedAccounts()).isZero();
        assertThat(attempt.openWindowsRemaining()).isEqualTo(1);
        assertThat(attempt.fullyReconciled()).isFalse();
    }

    @Test
    void publicReportIsUnchangedByTheNewEntryPoint() {
        insertPendingRow("ACC-A");

        ReconciliationService.ReconciliationReport report =
                service(ledger(), core(), ReconciliationLock.LOCAL_ONLY).reconcile();

        assertThat(report.transactionsReplayed()).isEqualTo(1);
        assertThat(report.reconciliationSuccessful()).isTrue();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static ReconciliationService service(ShadowLedger ledger, CoreBankingAdapter core,
                                                 ReconciliationLock lock) {
        return new ReconciliationService(ledger, jdbc, core, txManager, 100, lock);
    }

    private static ShadowLedger ledger() {
        ShadowLedger ledger = mock(ShadowLedger.class);
        when(ledger.getAvailableBalance(any())).thenReturn(BigDecimal.ZERO);
        return ledger;
    }

    private static CoreBankingAdapter core() {
        CoreBankingAdapter core = mock(CoreBankingAdapter.class);
        when(core.getAvailableBalance(any())).thenReturn(BigDecimal.ZERO);
        when(core.isCoreSystemAvailable()).thenReturn(true);
        return core;
    }

    private static void insertPendingRow(String accountId) {
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES (?, ?, ?, 'DEBIT', 10.00, 100.00, 90.00, FALSE)
                """, "TXN-" + accountId, "TXN-" + accountId, accountId);
    }

    private static int unconfirmed() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log WHERE core_confirmed = FALSE",
                Integer.class);
        return n == null ? 0 : n;
    }

    private static int runs() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_run", Integer.class);
        return n == null ? 0 : n;
    }
}
