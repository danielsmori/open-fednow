package io.openfednow.shadowledger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link ShadowLedger#reverseDebit} and
 * {@link ShadowLedger#reverseCredit} are idempotent under retry — calling them
 * twice for the same {@code transactionId} writes one {@code REVERSAL} row, not
 * two, and touches Redis exactly once.
 *
 * <p>Without this property, saga recovery, timeout-driven compensation, and
 * per-saga compensation paths could all stack reversals on the same payment
 * and double-credit (or double-debit) the account. The test runs against a
 * real H2 database with the Flyway migrations applied so the SQL guard is
 * exercised exactly as it would be in PostgreSQL.
 */
class ShadowLedgerReversalIdempotencyTest {

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void runMigrations() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:reversal_idempotency;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
    }

    // ── reverseDebit ────────────────────────────────────────────────────────

    @Test
    void firstReverseDebitInsertsReversalRow() {
        seedDebit("TXN-D1", "ACC-X", new BigDecimal("100.00"));
        ShadowLedger ledger = newLedger();

        ledger.reverseDebit("TXN-D1");

        assertThat(countReversals("TXN-D1")).isEqualTo(1);
    }

    @Test
    void secondReverseDebitForSameTransactionIsNoOp() {
        seedDebit("TXN-D2", "ACC-X", new BigDecimal("100.00"));

        StringRedisTemplate redis = newRedisMock();
        ShadowLedger ledger = new ShadowLedger(redis, jdbc);

        ledger.reverseDebit("TXN-D2");
        ledger.reverseDebit("TXN-D2");

        // Exactly one REVERSAL row, not two
        assertThat(countReversals("TXN-D2")).isEqualTo(1);
        // Redis increment was called exactly once — second call short-circuited
        verify(redis.opsForValue(), times(1)).increment(any(String.class), anyLong());
    }

    @Test
    void manyConsecutiveReverseDebitsAreSafe() {
        seedDebit("TXN-D3", "ACC-X", new BigDecimal("100.00"));
        ShadowLedger ledger = newLedger();

        for (int i = 0; i < 10; i++) {
            ledger.reverseDebit("TXN-D3");
        }

        assertThat(countReversals("TXN-D3")).isEqualTo(1);
    }

    @Test
    void reverseDebitDoesNotAffectADifferentTransaction() {
        seedDebit("TXN-D4-A", "ACC-A", new BigDecimal("100.00"));
        seedDebit("TXN-D4-B", "ACC-B", new BigDecimal("200.00"));
        ShadowLedger ledger = newLedger();

        ledger.reverseDebit("TXN-D4-A");
        // Reversing A must not record a REVERSAL against B
        ledger.reverseDebit("TXN-D4-B");

        assertThat(countReversals("TXN-D4-A")).isEqualTo(1);
        assertThat(countReversals("TXN-D4-B")).isEqualTo(1);
    }

    // ── reverseCredit ───────────────────────────────────────────────────────

    @Test
    void firstReverseCreditInsertsReversalRow() {
        seedCredit("TXN-C1", "ACC-Y", new BigDecimal("50.00"));
        ShadowLedger ledger = newLedger();

        ledger.reverseCredit("TXN-C1");

        assertThat(countReversals("TXN-C1")).isEqualTo(1);
    }

    @Test
    void secondReverseCreditForSameTransactionIsNoOp() {
        seedCredit("TXN-C2", "ACC-Y", new BigDecimal("50.00"));

        StringRedisTemplate redis = newRedisMock();
        ShadowLedger ledger = new ShadowLedger(redis, jdbc);

        ledger.reverseCredit("TXN-C2");
        ledger.reverseCredit("TXN-C2");

        assertThat(countReversals("TXN-C2")).isEqualTo(1);
        verify(redis.opsForValue(), times(1)).increment(any(String.class), anyLong());
    }

    @Test
    void reverseDebitAndReverseCreditDoNotInterfere() {
        // A REVERSAL row from reverseDebit must not be mistaken for a prior reversal
        // by reverseCredit on a different transaction (or vice versa).
        seedDebit("TXN-MIX-D", "ACC-D", new BigDecimal("100.00"));
        seedCredit("TXN-MIX-C", "ACC-C", new BigDecimal("50.00"));
        ShadowLedger ledger = newLedger();

        ledger.reverseDebit("TXN-MIX-D");
        ledger.reverseCredit("TXN-MIX-C");

        assertThat(countReversals("TXN-MIX-D")).isEqualTo(1);
        assertThat(countReversals("TXN-MIX-C")).isEqualTo(1);
    }

    // ── Missing original row ────────────────────────────────────────────────

    @Test
    void reverseDebitWithNoOriginalRowIsNoOp() {
        // Neither a DEBIT nor a REVERSAL exists. The method warns and returns
        // without inserting anything spurious, and never touches Redis.
        StringRedisTemplate redis = newRedisMock();
        ShadowLedger ledger = new ShadowLedger(redis, jdbc);

        ledger.reverseDebit("TXN-GHOST");

        assertThat(countReversals("TXN-GHOST")).isZero();
        verify(redis.opsForValue(), never()).increment(any(String.class), anyLong());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private StringRedisTemplate newRedisMock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        // increment() returns the post-increment value; any non-null value is fine
        when(ops.increment(any(String.class), anyLong())).thenReturn(0L);
        return redis;
    }

    private ShadowLedger newLedger() {
        return new ShadowLedger(newRedisMock(), jdbc);
    }

    private void seedDebit(String transactionId, String accountId, BigDecimal amount) {
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES (?, ?, ?, 'DEBIT', ?, 1000.00, 900.00, FALSE)
                """,
                transactionId, "E2E-" + transactionId, accountId, amount);
    }

    private void seedCredit(String transactionId, String accountId, BigDecimal amount) {
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES (?, ?, ?, 'CREDIT', ?, 1000.00, 1100.00, FALSE)
                """,
                transactionId, "E2E-" + transactionId, accountId, amount);
    }

    private int countReversals(String transactionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log " +
                "WHERE transaction_id = ? AND transaction_type = 'REVERSAL'",
                Integer.class, transactionId);
        return count == null ? 0 : count;
    }
}
