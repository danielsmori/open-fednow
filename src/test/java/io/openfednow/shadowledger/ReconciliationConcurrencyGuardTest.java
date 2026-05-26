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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the same-JVM concurrency guard on {@link ReconciliationService#reconcile()}
 * — audit item #23.
 *
 * <p>Two threads invoke reconcile() simultaneously; exactly one runs the full cycle
 * and the other returns immediately with a "skipped" report. The cycle is forced
 * to take measurable time via a Mockito slow-down on {@link CoreBankingAdapter}.
 */
class ReconciliationConcurrencyGuardTest {

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager txManager;

    @BeforeAll
    static void runMigrations() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:reconcile_concurrency;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        txManager = new DataSourceTransactionManager(dataSource);
    }

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
        jdbc.update("DELETE FROM reconciliation_run");
    }

    @Test
    void concurrentReconcileInvocationsResultInExactlyOneRun() throws Exception {
        // Seed a single account so reconcile has actual work to do
        insertPendingRow("ACC-CONC-001", "TXN-CONC-001");

        // Adapter sleeps 500ms on the first balance lookup so the second invocation
        // overlaps the first while the lock is held.
        AtomicInteger callCount = new AtomicInteger();
        ShadowLedger ledger = mock(ShadowLedger.class);
        when(ledger.getAvailableBalance(any())).thenReturn(BigDecimal.ZERO);

        CoreBankingAdapter adapter = mock(CoreBankingAdapter.class);
        when(adapter.getAvailableBalance(any())).thenAnswer(invocation -> {
            callCount.incrementAndGet();
            Thread.sleep(500);
            return BigDecimal.ZERO;
        });

        ReconciliationService service = new ReconciliationService(
                ledger, jdbc, adapter, txManager, 100);

        // Two threads, gated on a latch so they fire as close to simultaneously as possible
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<ReconciliationService.ReconciliationReport> first = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            return service.reconcile();
        });
        Future<ReconciliationService.ReconciliationReport> second = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            // Tiny delay so the first thread is guaranteed to grab the lock first.
            // Without this, on a fast machine the two threads can race and either
            // can win — we want the test to deterministically exercise the
            // "second call sees the lock held" branch.
            Thread.sleep(50);
            return service.reconcile();
        });

        readyLatch.await();
        startLatch.countDown();

        ReconciliationService.ReconciliationReport r1 = first.get(10, TimeUnit.SECONDS);
        ReconciliationService.ReconciliationReport r2 = second.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        // Exactly one report did real work; one was skipped
        long skippedCount = (r1.summary().startsWith("Skipped") ? 1 : 0)
                + (r2.summary().startsWith("Skipped") ? 1 : 0);
        long ranCount = (r1.summary().startsWith("Skipped") ? 0 : 1)
                + (r2.summary().startsWith("Skipped") ? 0 : 1);
        assertThat(skippedCount).isEqualTo(1);
        assertThat(ranCount).isEqualTo(1);

        // The core adapter was only consulted by the run that actually executed
        assertThat(callCount.get()).isEqualTo(1);

        // Exactly one reconciliation_run row was created
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_run", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void sequentialReconcileInvocationsBothRun() {
        insertPendingRow("ACC-SEQ-001", "TXN-SEQ-001");

        ShadowLedger ledger = mock(ShadowLedger.class);
        when(ledger.getAvailableBalance(any())).thenReturn(BigDecimal.ZERO);
        CoreBankingAdapter adapter = mock(CoreBankingAdapter.class);
        when(adapter.getAvailableBalance(any())).thenReturn(BigDecimal.ZERO);

        ReconciliationService service = new ReconciliationService(
                ledger, jdbc, adapter, txManager, 100);

        // Run twice sequentially — both must execute, the lock releases between calls
        ReconciliationService.ReconciliationReport first = service.reconcile();
        ReconciliationService.ReconciliationReport second = service.reconcile();

        assertThat(first.summary()).doesNotStartWith("Skipped");
        assertThat(second.summary()).doesNotStartWith("Skipped");

        // Re-seed because the first reconcile marked the only row confirmed
        insertPendingRow("ACC-SEQ-002", "TXN-SEQ-002");
        ReconciliationService.ReconciliationReport third = service.reconcile();
        assertThat(third.summary()).doesNotStartWith("Skipped");
        assertThat(third.transactionsReplayed()).isEqualTo(1);
    }

    @Test
    void skippedReportReportsZeroCountsAndSuccessfulTrue() throws Exception {
        // ReentrantLock is reentrant — a same-thread tryLock() succeeds even when
        // the lock is held by that thread. To exercise the skip branch we must
        // hold the lock on a different thread while the main thread calls reconcile().
        ReconciliationService service = new ReconciliationService(
                mock(ShadowLedger.class), jdbc, mock(CoreBankingAdapter.class),
                txManager, 100);

        java.util.concurrent.locks.ReentrantLock lock = readLock(service);

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                lock.lock();
                lockHeld.countDown();
                try {
                    releaseLock.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                lock.unlock();
                return null;
            });

            lockHeld.await();
            ReconciliationService.ReconciliationReport skipped = service.reconcile();

            assertThat(skipped.transactionsReplayed()).isZero();
            assertThat(skipped.discrepanciesDetected()).isZero();
            // A "skipped" cycle is not itself a failure — successful=true so monitoring
            // alerting doesn't treat the skip as a real reconciliation failure.
            assertThat(skipped.reconciliationSuccessful()).isTrue();
            assertThat(skipped.summary()).contains("Skipped");
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void insertPendingRow(String accountId, String transactionId) {
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES (?, ?, ?, 'CREDIT', 100.00, 1000.00, 1100.00, FALSE)
                """,
                transactionId, "E2E-" + transactionId, accountId);
    }

    /** Reflection access to the private reconcileLock so the test can hold it. */
    private static java.util.concurrent.locks.ReentrantLock readLock(ReconciliationService service) {
        try {
            java.lang.reflect.Field f = ReconciliationService.class.getDeclaredField("reconcileLock");
            f.setAccessible(true);
            return (java.util.concurrent.locks.ReentrantLock) f.get(service);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
