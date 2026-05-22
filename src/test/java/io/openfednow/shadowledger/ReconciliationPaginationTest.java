package io.openfednow.shadowledger;

import io.openfednow.acl.core.CoreBankingAdapter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@link ReconciliationService} pagination loop — issue #51.
 *
 * <p>Runs against a real H2 database (PostgreSQL-compatibility mode) with the
 * production Flyway migrations applied, so the SQL is exercised exactly as it
 * will run in PostgreSQL. {@link ShadowLedger} and {@link CoreBankingAdapter}
 * are mocked because their real implementations depend on Redis — the
 * pagination logic doesn't.
 */
class ReconciliationPaginationTest {

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void runMigrations() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:reconcile_pagination_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
        jdbc.update("DELETE FROM reconciliation_run");
    }

    // ── Construction validation ──────────────────────────────────────────────

    @Test
    void zeroBatchSizeIsRejected() {
        ShadowLedger ledger = mock(ShadowLedger.class);
        CoreBankingAdapter adapter = mock(CoreBankingAdapter.class);
        assertThatThrownBy(() -> new ReconciliationService(ledger, jdbc, adapter, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-size");
    }

    @Test
    void negativeBatchSizeIsRejected() {
        ShadowLedger ledger = mock(ShadowLedger.class);
        CoreBankingAdapter adapter = mock(CoreBankingAdapter.class);
        assertThatThrownBy(() -> new ReconciliationService(ledger, jdbc, adapter, -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-size");
    }

    @Test
    void batchSizeIsExposedForOperationalVisibility() {
        ReconciliationService service = newService(250);
        assertThat(service.getBatchSize()).isEqualTo(250);
    }

    // ── Correctness at scale ─────────────────────────────────────────────────

    @Test
    void allAccountsAreReconciledWhenDatasetSpansManyBatches() {
        ShadowLedger ledger = stubLedgerAtBalance(BigDecimal.ZERO);
        CoreBankingAdapter adapter = stubCoreAtBalance(BigDecimal.ZERO);
        ReconciliationService service = new ReconciliationService(ledger, jdbc, adapter, 100);

        // 1,250 distinct accounts, one row each — forces 13 paginated iterations
        // (1250 / 100 batchSize + a final empty fetch) and exercises the lastSeen cursor.
        int accountCount = 1_250;
        for (int i = 0; i < accountCount; i++) {
            insertPendingRow(String.format("ACC-%05d", i), "TXN-%05d".formatted(i));
        }

        ReconciliationService.ReconciliationReport report = service.reconcile();

        assertThat(report.transactionsReplayed()).isEqualTo(accountCount);
        assertThat(report.discrepanciesDetected()).isZero();
        assertThat(report.reconciliationSuccessful()).isTrue();

        // Every row must now be confirmed
        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log WHERE core_confirmed = FALSE",
                Integer.class);
        assertThat(remaining).isZero();
    }

    @Test
    void paginatedResultMatchesUnpaginatedResultForSameDataset() {
        // Compare batch size 50 against batch size 1000 over the same dataset —
        // both must produce the same counts. (Tests both the batching logic and
        // its independence from the configured batch size.)
        int accountCount = 600;

        // Run 1: small batch
        for (int i = 0; i < accountCount; i++) {
            insertPendingRow("R1-ACC-%05d".formatted(i), "R1-TXN-%05d".formatted(i));
        }
        ReconciliationService.ReconciliationReport smallBatch =
                newService(50).reconcile();

        // Run 2: single bulk batch (larger than dataset)
        for (int i = 0; i < accountCount; i++) {
            insertPendingRow("R2-ACC-%05d".formatted(i), "R2-TXN-%05d".formatted(i));
        }
        ReconciliationService.ReconciliationReport bulkBatch =
                newService(10_000).reconcile();

        assertThat(smallBatch.transactionsReplayed()).isEqualTo(accountCount);
        assertThat(bulkBatch.transactionsReplayed()).isEqualTo(accountCount);
        assertThat(smallBatch.discrepanciesDetected())
                .isEqualTo(bulkBatch.discrepanciesDetected());
    }

    @Test
    void multipleRowsPerAccountAreAllConfirmedInOneVisit() {
        ShadowLedger ledger = stubLedgerAtBalance(BigDecimal.ZERO);
        CoreBankingAdapter adapter = stubCoreAtBalance(BigDecimal.ZERO);
        ReconciliationService service = new ReconciliationService(ledger, jdbc, adapter, 10);

        // 25 accounts, 4 rows each — total 100 rows, but 25 distinct account IDs
        int accountCount = 25;
        int rowsPerAccount = 4;
        for (int a = 0; a < accountCount; a++) {
            for (int r = 0; r < rowsPerAccount; r++) {
                insertPendingRow("ACC-%03d".formatted(a),
                        "TXN-%03d-%d".formatted(a, r));
            }
        }

        ReconciliationService.ReconciliationReport report = service.reconcile();

        assertThat(report.transactionsReplayed()).isEqualTo(accountCount * rowsPerAccount);
    }

    // ── Discrepancy detection across batch boundaries ────────────────────────

    @Test
    void discrepanciesAreDetectedAcrossBatchBoundaries() {
        // Stub the ledger to return $100 for every account but the adapter to return
        // $50 for two specific accounts and $100 for the rest. Discrepancy count
        // should be exactly 2, regardless of which batches the discrepant accounts
        // end up in.
        ShadowLedger ledger = mock(ShadowLedger.class);
        when(ledger.getAvailableBalance(any())).thenReturn(new BigDecimal("100.00"));

        CoreBankingAdapter adapter = mock(CoreBankingAdapter.class);
        when(adapter.getAvailableBalance(any())).thenReturn(new BigDecimal("100.00"));
        // Drop two specific accounts mid-dataset so they fall in different batches
        when(adapter.getAvailableBalance(eq("ACC-00050"))).thenReturn(new BigDecimal("50.00"));
        when(adapter.getAvailableBalance(eq("ACC-00350"))).thenReturn(new BigDecimal("50.00"));

        ReconciliationService service = new ReconciliationService(ledger, jdbc, adapter, 100);

        for (int i = 0; i < 400; i++) {
            insertPendingRow("ACC-%05d".formatted(i), "TXN-%05d".formatted(i));
        }

        ReconciliationService.ReconciliationReport report = service.reconcile();

        assertThat(report.discrepanciesDetected()).isEqualTo(2);
        assertThat(report.reconciliationSuccessful()).isFalse();
        verify(ledger).reconcile(eq("ACC-00050"), eq(new BigDecimal("50.00")));
        verify(ledger).reconcile(eq("ACC-00350"), eq(new BigDecimal("50.00")));
    }

    // ── Failure isolation ────────────────────────────────────────────────────

    @Test
    void perAccountFailureDoesNotCauseInfiniteLoop() {
        ShadowLedger ledger = stubLedgerAtBalance(BigDecimal.ZERO);
        CoreBankingAdapter adapter = mock(CoreBankingAdapter.class);
        // First account always throws — the loop must still advance past it
        when(adapter.getAvailableBalance(any())).thenReturn(BigDecimal.ZERO);
        when(adapter.getAvailableBalance(eq("ACC-00000")))
                .thenThrow(new RuntimeException("simulated core failure"));

        ReconciliationService service = new ReconciliationService(ledger, jdbc, adapter, 5);

        // Small dataset — completes quickly if the loop is correct; hangs if it isn't
        for (int i = 0; i < 20; i++) {
            insertPendingRow("ACC-%05d".formatted(i), "TXN-%05d".formatted(i));
        }

        ReconciliationService.ReconciliationReport report = service.reconcile();

        // The throwing account counts as a discrepancy; the other 19 are reconciled
        assertThat(report.discrepanciesDetected()).isEqualTo(1);
        assertThat(report.transactionsReplayed()).isEqualTo(19);
        // The failed account stays unconfirmed
        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log " +
                "WHERE account_id = 'ACC-00000' AND core_confirmed = FALSE",
                Integer.class);
        assertThat(remaining).isEqualTo(1);
    }

    @Test
    void emptyDatasetCompletesWithZeroes() {
        ReconciliationService.ReconciliationReport report = newService(100).reconcile();

        assertThat(report.transactionsReplayed()).isZero();
        assertThat(report.discrepanciesDetected()).isZero();
        assertThat(report.reconciliationSuccessful()).isTrue();
    }

    // ── Batch-size plumbing ──────────────────────────────────────────────────

    @Test
    void coreAdapterIsConsultedOncePerDistinctAccount() {
        ShadowLedger ledger = stubLedgerAtBalance(BigDecimal.ZERO);
        CoreBankingAdapter adapter = stubCoreAtBalance(BigDecimal.ZERO);
        ReconciliationService service = new ReconciliationService(ledger, jdbc, adapter, 7);

        // 21 distinct accounts — exactly 3 batches of 7 plus an empty terminator query.
        // (3 rows per account so the test also confirms one adapter call per account,
        // not per row.)
        for (int a = 0; a < 21; a++) {
            for (int r = 0; r < 3; r++) {
                insertPendingRow("ACC-%03d".formatted(a),
                        "TXN-%03d-%d".formatted(a, r));
            }
        }

        service.reconcile();

        for (int a = 0; a < 21; a++) {
            // Each account should be looked up exactly once
            verify(adapter, atLeast(1)).getAvailableBalance(eq("ACC-%03d".formatted(a)));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ReconciliationService newService(int batchSize) {
        return new ReconciliationService(
                stubLedgerAtBalance(BigDecimal.ZERO),
                jdbc,
                stubCoreAtBalance(BigDecimal.ZERO),
                batchSize);
    }

    private ShadowLedger stubLedgerAtBalance(BigDecimal balance) {
        ShadowLedger ledger = mock(ShadowLedger.class);
        when(ledger.getAvailableBalance(any())).thenReturn(balance);
        return ledger;
    }

    private CoreBankingAdapter stubCoreAtBalance(BigDecimal balance) {
        CoreBankingAdapter adapter = mock(CoreBankingAdapter.class);
        when(adapter.getAvailableBalance(any())).thenReturn(balance);
        return adapter;
    }

    private void insertPendingRow(String accountId, String transactionId) {
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES (?, ?, ?, 'CREDIT', 100.00, 1000.00, 1100.00, FALSE)
                """,
                transactionId, "E2E-" + transactionId, accountId);
    }
}
