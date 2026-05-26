package io.openfednow.processing.saga;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.openfednow.shadowledger.ShadowLedger;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link CompensationRetryService} — audit item #24.
 *
 * <p>The SQL filter is the interesting part — "FAILED saga with an original
 * DEBIT/CREDIT but no REVERSAL". The test runs against a real H2 database
 * with the production Flyway migrations applied, with a mocked {@link ShadowLedger}
 * so the test is fast and doesn't require Redis.
 */
class CompensationRetryServiceTest {

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void runMigrations() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:compensation_retry_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
        jdbc.update("DELETE FROM saga_state");
    }

    // ── Construction ─────────────────────────────────────────────────────────

    @Test
    void zeroBatchSizeIsRejected() {
        ShadowLedger ledger = mock(ShadowLedger.class);
        assertThatThrownBy(() -> new CompensationRetryService(
                jdbc, ledger, new SimpleMeterRegistry(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-size");
    }

    @Test
    void negativeBatchSizeIsRejected() {
        ShadowLedger ledger = mock(ShadowLedger.class);
        assertThatThrownBy(() -> new CompensationRetryService(
                jdbc, ledger, new SimpleMeterRegistry(), -5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configuredBatchSizeIsExposed() {
        CompensationRetryService service = newService(mock(ShadowLedger.class), 25);
        assertThat(service.getBatchSize()).isEqualTo(25);
    }

    // ── Sweep semantics ──────────────────────────────────────────────────────

    @Test
    void noFailedSagasReturnsZero() {
        ShadowLedger ledger = mock(ShadowLedger.class);
        CompensationRetryService service = newService(ledger, 100);

        int retried = service.retryFailedCompensations();

        assertThat(retried).isZero();
        verify(ledger, never()).reverseDebit(org.mockito.ArgumentMatchers.any());
        verify(ledger, never()).reverseCredit(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failedSagaWithDebitButNoReversalIsRetried() {
        ShadowLedger ledger = mock(ShadowLedger.class);
        insertFailedSagaWithDebit("SAGA-A", "TXN-A");

        int retried = newService(ledger, 100).retryFailedCompensations();

        assertThat(retried).isEqualTo(1);
        verify(ledger).reverseDebit("TXN-A");
        verify(ledger).reverseCredit("TXN-A");  // Both called; cheap because idempotent
    }

    @Test
    void failedSagaWithCreditButNoReversalIsRetried() {
        ShadowLedger ledger = mock(ShadowLedger.class);
        insertFailedSagaWithCredit("SAGA-B", "TXN-B");

        int retried = newService(ledger, 100).retryFailedCompensations();

        assertThat(retried).isEqualTo(1);
        verify(ledger).reverseDebit("TXN-B");
        verify(ledger).reverseCredit("TXN-B");
    }

    @Test
    void failedSagaWithExistingReversalIsSkipped() {
        ShadowLedger ledger = mock(ShadowLedger.class);
        // Saga has both DEBIT and REVERSAL — already compensated successfully,
        // must not be retried
        insertFailedSagaWithDebit("SAGA-DONE", "TXN-DONE");
        insertReversalRow("TXN-DONE", "ACC-DONE", new BigDecimal("100.00"));

        int retried = newService(ledger, 100).retryFailedCompensations();

        assertThat(retried).isZero();
        verify(ledger, never()).reverseDebit("TXN-DONE");
        verify(ledger, never()).reverseCredit("TXN-DONE");
    }

    @Test
    void completedSagaIsNotRetried() {
        ShadowLedger ledger = mock(ShadowLedger.class);
        // COMPLETED is a success path — no reversal expected, must not be retried
        insertCompletedSagaWithDebit("SAGA-COMPLETED", "TXN-COMPLETED");

        int retried = newService(ledger, 100).retryFailedCompensations();

        assertThat(retried).isZero();
        verify(ledger, never()).reverseDebit(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void compensatingSagaIsNotRetried() {
        ShadowLedger ledger = mock(ShadowLedger.class);
        // COMPENSATING is in progress — wait for it to finalize before retrying
        insertCompensatingSagaWithDebit("SAGA-COMP", "TXN-COMP");

        int retried = newService(ledger, 100).retryFailedCompensations();

        assertThat(retried).isZero();
        verify(ledger, never()).reverseDebit(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failedSagaWithNoOriginalRowIsNotRetried() {
        ShadowLedger ledger = mock(ShadowLedger.class);
        // Saga reached FAILED via INITIATED → COMPENSATING → FAILED (no funds reserved).
        // Nothing to reverse; sweep must not waste cycles on it.
        insertFailedSaga("SAGA-NOOP", "TXN-NOOP");

        int retried = newService(ledger, 100).retryFailedCompensations();

        assertThat(retried).isZero();
        verify(ledger, never()).reverseDebit(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void batchSizeCapsTheNumberOfCandidates() {
        ShadowLedger ledger = mock(ShadowLedger.class);
        for (int i = 0; i < 10; i++) {
            insertFailedSagaWithDebit("SAGA-%03d".formatted(i), "TXN-%03d".formatted(i));
        }

        int retried = newService(ledger, 3).retryFailedCompensations();

        assertThat(retried).isEqualTo(3);
        verify(ledger, times(3)).reverseDebit(org.mockito.ArgumentMatchers.any());
    }

    // ── Counter accuracy ─────────────────────────────────────────────────────

    @Test
    void successCounterAdvancesOnSuccessfulRetry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ShadowLedger ledger = mock(ShadowLedger.class);
        insertFailedSagaWithDebit("SAGA-SUCCESS", "TXN-SUCCESS");

        new CompensationRetryService(jdbc, ledger, registry, 100)
                .retryFailedCompensations();

        assertThat(registry.counter(CompensationRetryService.RETRY_SUCCESS_METRIC).count())
                .isEqualTo(1.0);
        assertThat(registry.counter(CompensationRetryService.RETRY_FAILURE_METRIC).count())
                .isZero();
    }

    @Test
    void failureCounterAdvancesWhenLedgerThrows() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ShadowLedger ledger = mock(ShadowLedger.class);
        insertFailedSagaWithDebit("SAGA-FAIL", "TXN-FAIL");
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(ledger).reverseDebit("TXN-FAIL");

        new CompensationRetryService(jdbc, ledger, registry, 100)
                .retryFailedCompensations();

        assertThat(registry.counter(CompensationRetryService.RETRY_FAILURE_METRIC).count())
                .isEqualTo(1.0);
        assertThat(registry.counter(CompensationRetryService.RETRY_SUCCESS_METRIC).count())
                .isZero();
    }

    @Test
    void failureOnOneSagaDoesNotBlockOthers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ShadowLedger ledger = mock(ShadowLedger.class);
        insertFailedSagaWithDebit("SAGA-FAIL", "TXN-FAIL");
        insertFailedSagaWithDebit("SAGA-OK", "TXN-OK");
        org.mockito.Mockito.doThrow(new RuntimeException("simulated"))
                .when(ledger).reverseDebit("TXN-FAIL");

        new CompensationRetryService(jdbc, ledger, registry, 100)
                .retryFailedCompensations();

        // The other saga still got its retry
        verify(ledger).reverseDebit("TXN-OK");
        assertThat(registry.counter(CompensationRetryService.RETRY_SUCCESS_METRIC).count())
                .isEqualTo(1.0);
        assertThat(registry.counter(CompensationRetryService.RETRY_FAILURE_METRIC).count())
                .isEqualTo(1.0);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private CompensationRetryService newService(ShadowLedger ledger, int batchSize) {
        return new CompensationRetryService(jdbc, ledger, new SimpleMeterRegistry(), batchSize);
    }

    private void insertFailedSaga(String sagaId, String txnId) {
        jdbc.update("""
                INSERT INTO saga_state (saga_id, transaction_id, end_to_end_id, state, source_rail)
                VALUES (?, ?, ?, 'FAILED', 'FEDNOW')
                """,
                sagaId, txnId, "E2E-" + txnId);
    }

    private void insertFailedSagaWithDebit(String sagaId, String txnId) {
        insertFailedSaga(sagaId, txnId);
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES (?, ?, ?, 'DEBIT', 100.00, 1000.00, 900.00, FALSE)
                """,
                txnId, "E2E-" + txnId, "ACC-" + txnId);
    }

    private void insertFailedSagaWithCredit(String sagaId, String txnId) {
        insertFailedSaga(sagaId, txnId);
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES (?, ?, ?, 'CREDIT', 100.00, 1000.00, 1100.00, FALSE)
                """,
                txnId, "E2E-" + txnId, "ACC-" + txnId);
    }

    private void insertCompletedSagaWithDebit(String sagaId, String txnId) {
        jdbc.update("""
                INSERT INTO saga_state (saga_id, transaction_id, end_to_end_id, state, source_rail)
                VALUES (?, ?, ?, 'COMPLETED', 'FEDNOW')
                """,
                sagaId, txnId, "E2E-" + txnId);
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES (?, ?, ?, 'DEBIT', 100.00, 1000.00, 900.00, TRUE)
                """,
                txnId, "E2E-" + txnId, "ACC-" + txnId);
    }

    private void insertCompensatingSagaWithDebit(String sagaId, String txnId) {
        jdbc.update("""
                INSERT INTO saga_state (saga_id, transaction_id, end_to_end_id, state, source_rail)
                VALUES (?, ?, ?, 'COMPENSATING', 'FEDNOW')
                """,
                sagaId, txnId, "E2E-" + txnId);
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES (?, ?, ?, 'DEBIT', 100.00, 1000.00, 900.00, FALSE)
                """,
                txnId, "E2E-" + txnId, "ACC-" + txnId);
    }

    private void insertReversalRow(String txnId, String accountId, BigDecimal amount) {
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES (?, ?, ?, 'REVERSAL', ?, 900.00, 1000.00, FALSE)
                """,
                txnId, "E2E-" + txnId, accountId, amount);
    }
}
