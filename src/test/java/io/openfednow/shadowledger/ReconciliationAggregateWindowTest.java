package io.openfednow.shadowledger;

import io.openfednow.acl.core.CoreBankingAdapter;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How {@link ReconciliationService} handles bridge-mode aggregate windows —
 * issue #68, PR 2.
 *
 * <p>Pins the gating that keeps the cap fail-closed without taxing the
 * common case:
 * <ul>
 *   <li>accounts without a window never trigger a core health check, and the
 *       open-window sweep does not run while the cap is disabled — so with the
 *       cap off, reconciliation does exactly what it did before;</li>
 *   <li>an account with a window is deferred untouched while the core is
 *       offline (its rows are not confirmed against an offline core);</li>
 *   <li>confirmed sends are released only after the per-account transaction
 *       has committed, and not at all if the core drops in between.</li>
 * </ul>
 *
 * <p>Real H2 with the production migrations; {@link ShadowLedger} and
 * {@link CoreBankingAdapter} are mocks.
 */
class ReconciliationAggregateWindowTest {

    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager txManager;

    @BeforeAll
    static void runMigrations() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:reconcile_aggregate_window_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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

    // ── No window: no extra work ─────────────────────────────────────────────

    @Test
    void capDisabledAndNoWindowsMeansNoHealthChecksAndNoSweep() {
        ShadowLedger ledger = ledger(false);
        CoreBankingAdapter core = core(true);
        insertPendingRow("ACC-A", "TXN-A");
        insertPendingRow("ACC-B", "TXN-B");

        service(ledger, core).reconcile();

        verify(core, never()).isCoreSystemAvailable();
        verify(ledger, never()).openAggregateAccountIds();
        verify(ledger, never()).closeConfirmedAggregate(anyString());
        assertThat(unconfirmed("ACC-A") + unconfirmed("ACC-B")).isZero();
    }

    @Test
    void capEnabledWithNoOpenWindowsNeverProbesTheCore() {
        ShadowLedger ledger = ledger(true);
        when(ledger.openAggregateAccountIds()).thenReturn(List.of());
        CoreBankingAdapter core = core(true);
        insertPendingRow("ACC-A", "TXN-A");

        service(ledger, core).reconcile();

        verify(core, never()).isCoreSystemAvailable();
        verify(ledger, never()).closeConfirmedAggregate(anyString());
    }

    // ── Account with a window ────────────────────────────────────────────────

    @Test
    void windowAccountIsDeferredUntouchedWhileTheCoreIsOffline() {
        ShadowLedger ledger = ledger(true);
        when(ledger.hasOpenAggregate("ACC-W")).thenReturn(true);
        when(ledger.openAggregateAccountIds()).thenReturn(List.of("ACC-W"));
        CoreBankingAdapter core = core(false);
        insertPendingRow("ACC-W", "TXN-W");

        service(ledger, core).reconcile();

        assertThat(unconfirmed("ACC-W")).isEqualTo(1);
        verify(core, never()).getAvailableBalance("ACC-W");
        verify(ledger, never()).reconcileIfUnchanged(anyString(), any(), any());
        verify(ledger, never()).closeConfirmedAggregate(anyString());
    }

    @Test
    void confirmedSendsAreReleasedOnlyAfterTheTransactionCommitted() {
        ShadowLedger ledger = ledger(true);
        when(ledger.hasOpenAggregate("ACC-W")).thenReturn(true);
        when(ledger.openAggregateAccountIds()).thenReturn(List.of());
        CoreBankingAdapter core = core(true);
        insertPendingRow("ACC-W", "TXN-W");
        AtomicInteger unconfirmedAtRelease = new AtomicInteger(-1);
        doAnswer(inv -> {
            unconfirmedAtRelease.set(unconfirmed("ACC-W"));
            return true;
        }).when(ledger).closeConfirmedAggregate("ACC-W");

        service(ledger, core).reconcile();

        verify(ledger, times(1)).closeConfirmedAggregate("ACC-W");
        // the confirmation was already committed when the release ran
        assertThat(unconfirmedAtRelease.get()).isZero();
    }

    @Test
    void coreDroppingMidReconciliationKeepsTheWindowOpen() {
        ShadowLedger ledger = ledger(true);
        when(ledger.hasOpenAggregate("ACC-W")).thenReturn(true);
        when(ledger.openAggregateAccountIds()).thenReturn(List.of("ACC-W"));
        CoreBankingAdapter core = mock(CoreBankingAdapter.class);
        when(core.getAvailableBalance(any())).thenReturn(BigDecimal.ZERO);
        // online for the pre-check, offline by the post-commit check and the sweep
        when(core.isCoreSystemAvailable()).thenReturn(true, false);
        insertPendingRow("ACC-W", "TXN-W");

        service(ledger, core).reconcile();

        verify(ledger, never()).closeConfirmedAggregate(anyString());
    }

    // ── Sweep ────────────────────────────────────────────────────────────────

    @Test
    void sweepReleasesWindowsWithNoUnconfirmedRowsWhenTheCoreIsOnline() {
        ShadowLedger ledger = ledger(true);
        when(ledger.openAggregateAccountIds()).thenReturn(List.of("ACC-SWEPT"));
        CoreBankingAdapter core = core(true);
        // no pending rows at all: the account can only be reached by the sweep

        service(ledger, core).reconcile();

        verify(ledger, times(1)).closeConfirmedAggregate("ACC-SWEPT");
        // the sweep releases sends only — it never reconciles balances
        verify(ledger, never()).reconcileIfUnchanged(anyString(), any(), any());
        verify(core, never()).getAvailableBalance(anyString());
    }

    @Test
    void sweepDoesNothingWhileTheCoreIsOffline() {
        ShadowLedger ledger = ledger(true);
        when(ledger.openAggregateAccountIds()).thenReturn(List.of("ACC-SWEPT"));
        CoreBankingAdapter core = core(false);

        service(ledger, core).reconcile();

        verify(ledger, never()).closeConfirmedAggregate(anyString());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static ReconciliationService service(ShadowLedger ledger, CoreBankingAdapter core) {
        return new ReconciliationService(ledger, jdbc, core, txManager, 100, ReconciliationLock.LOCAL_ONLY);
    }

    private static ShadowLedger ledger(boolean capEnabled) {
        ShadowLedger ledger = LedgerMocks.mockLedger();
        when(ledger.getAvailableBalance(any())).thenReturn(BigDecimal.ZERO);
        when(ledger.isAggregateCapEnabled()).thenReturn(capEnabled);
        return ledger;
    }

    private static CoreBankingAdapter core(boolean online) {
        CoreBankingAdapter core = mock(CoreBankingAdapter.class);
        when(core.getAvailableBalance(any())).thenReturn(BigDecimal.ZERO);
        when(core.isCoreSystemAvailable()).thenReturn(online);
        return core;
    }

    private static void insertPendingRow(String accountId, String transactionId) {
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES (?, ?, ?, 'DEBIT', 10.00, 100.00, 90.00, FALSE)
                """, transactionId, transactionId, accountId);
    }

    private static int unconfirmed(String accountId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log "
                        + "WHERE account_id = ? AND core_confirmed = FALSE",
                Integer.class, accountId);
        return n == null ? 0 : n;
    }
}
