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
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cross-instance behavior of {@link ReconciliationService#reconcile()} via the
 * {@link ReconciliationLease} — issue #68, PR 3.
 *
 * <p>With automatic reconciliation on core recovery, several instances try to
 * reconcile at the same moment. Exactly one may run; a lease lost mid-cycle
 * must abort rather than reconcile concurrently with the new holder.
 */
class ReconciliationCrossInstanceTest {

    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager txManager;

    @BeforeAll
    static void runMigrations() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:reconcile_cross_instance;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        txManager = new DataSourceTransactionManager(dataSource);
    }

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
        jdbc.update("DELETE FROM reconciliation_run");
        jdbc.update("UPDATE reconciliation_lease SET holder = NULL, leased_until = NULL WHERE id = 1");
    }

    @Test
    void reconcileSkipsWhileAnotherInstanceHoldsTheLease() {
        insertPendingRow("ACC-SKIP", "TXN-SKIP");
        ReconciliationLease otherInstance = new ReconciliationLease(jdbc, 900, "other-pod");
        assertThat(otherInstance.tryAcquire()).isTrue();

        ReconciliationService.ReconciliationReport report =
                service(okLedger(), okCore(), new ReconciliationLease(jdbc, 900, "this-pod"))
                        .reconcile();

        assertThat(report.summary()).contains("another instance");
        assertThat(report.transactionsReplayed()).isZero();
        // nothing ran: the account is untouched and no run record was opened
        assertThat(unconfirmed("ACC-SKIP")).isEqualTo(1);
        Integer runs = jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_run", Integer.class);
        assertThat(runs).isZero();
    }

    @Test
    void leaseIsReleasedAfterACycleSoOthersCanReconcile() {
        insertPendingRow("ACC-REL", "TXN-REL");

        service(okLedger(), okCore(), new ReconciliationLease(jdbc, 900, "this-pod")).reconcile();

        assertThat(new ReconciliationLease(jdbc, 900, "other-pod").tryAcquire()).isTrue();
    }

    @Test
    void leaseLostMidCycleAbortsTheRunAndLeavesRemainingAccountsUnconfirmed() {
        // Three accounts, batch size 1 — the lease is renewed after each batch.
        insertPendingRow("ACC-0001", "TXN-0001");
        insertPendingRow("ACC-0002", "TXN-0002");
        insertPendingRow("ACC-0003", "TXN-0003");

        CoreBankingAdapter core = okCore();
        // While the second account is being reconciled, the lease expires and
        // another instance takes it over.
        doAnswer(inv -> {
            jdbc.update("UPDATE reconciliation_lease SET holder = 'usurper-pod', leased_until = ? "
                            + "WHERE id = 1",
                    Timestamp.from(Instant.now().plusSeconds(900)));
            return BigDecimal.ZERO;
        }).when(core).getAvailableBalance(eq("ACC-0002"));

        ReconciliationService.ReconciliationReport report =
                service(okLedger(), core, new ReconciliationLease(jdbc, 900, "this-pod"), 1)
                        .reconcile();

        assertThat(report.reconciliationSuccessful()).isFalse();
        assertThat(report.summary()).contains("lease lost");
        // the third account was never touched
        assertThat(unconfirmed("ACC-0003")).isEqualTo(1);
        // the usurper keeps its lease — the aborting instance must not release it
        String holder = jdbc.queryForObject(
                "SELECT holder FROM reconciliation_lease WHERE id = 1", String.class);
        assertThat(holder).isEqualTo("usurper-pod");
        // the aborted run record is closed as unsuccessful
        Boolean successful = jdbc.queryForObject(
                "SELECT successful FROM reconciliation_run ORDER BY id DESC LIMIT 1", Boolean.class);
        assertThat(successful).isFalse();
    }

    @Test
    void renewalIsPerAccountNotPerBatch() {
        // Batch size 3, takeover while the FIRST account is being reconciled.
        // Per-account renewal must abort before touching the other two; a
        // regression to per-batch renewal would reconcile all three first.
        insertPendingRow("ACC-0001", "TXN-0001");
        insertPendingRow("ACC-0002", "TXN-0002");
        insertPendingRow("ACC-0003", "TXN-0003");

        CoreBankingAdapter core = okCore();
        doAnswer(inv -> {
            jdbc.update("UPDATE reconciliation_lease SET holder = 'usurper-pod', leased_until = ? "
                            + "WHERE id = 1",
                    Timestamp.from(Instant.now().plusSeconds(900)));
            return BigDecimal.ZERO;
        }).when(core).getAvailableBalance(eq("ACC-0001"));

        ReconciliationService.ReconciliationReport report =
                service(okLedger(), core, new ReconciliationLease(jdbc, 900, "this-pod"), 3)
                        .reconcile();

        assertThat(report.reconciliationSuccessful()).isFalse();
        assertThat(unconfirmed("ACC-0002")).isEqualTo(1);
        assertThat(unconfirmed("ACC-0003")).isEqualTo(1);
    }

    @Test
    void leaseLostDuringTheOpenWindowSweepAbortsTheRun() {
        // No pending rows: only the open-window sweep runs. The takeover
        // happens inside the sweep's release call; the renewal right after it
        // must abort the cycle.
        ShadowLedger ledger = okLedger();
        when(ledger.isAggregateCapEnabled()).thenReturn(true);
        when(ledger.openAggregateAccountIds())
                .thenReturn(java.util.List.of("ACC-SWEEP-1", "ACC-SWEEP-2"));
        doAnswer(inv -> {
            jdbc.update("UPDATE reconciliation_lease SET holder = 'usurper-pod', leased_until = ? "
                            + "WHERE id = 1",
                    Timestamp.from(Instant.now().plusSeconds(900)));
            return true;
        }).when(ledger).closeConfirmedAggregate("ACC-SWEEP-1");

        ReconciliationService.ReconciliationReport report =
                service(ledger, okCore(), new ReconciliationLease(jdbc, 900, "this-pod"))
                        .reconcile();

        assertThat(report.reconciliationSuccessful()).isFalse();
        assertThat(report.summary()).contains("sweep");
        org.mockito.Mockito.verify(ledger, org.mockito.Mockito.never())
                .closeConfirmedAggregate("ACC-SWEEP-2");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static ReconciliationService service(ShadowLedger ledger, CoreBankingAdapter core,
                                                 ReconciliationLease lease) {
        return service(ledger, core, lease, 100);
    }

    private static ReconciliationService service(ShadowLedger ledger, CoreBankingAdapter core,
                                                 ReconciliationLease lease, int batchSize) {
        return new ReconciliationService(ledger, jdbc, core, txManager, lease, batchSize);
    }

    private static ShadowLedger okLedger() {
        ShadowLedger ledger = mock(ShadowLedger.class);
        when(ledger.getAvailableBalance(any())).thenReturn(BigDecimal.ZERO);
        return ledger;
    }

    private static CoreBankingAdapter okCore() {
        CoreBankingAdapter core = mock(CoreBankingAdapter.class);
        when(core.getAvailableBalance(any())).thenReturn(BigDecimal.ZERO);
        when(core.isCoreSystemAvailable()).thenReturn(true);
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
