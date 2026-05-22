package io.openfednow.shadowledger;

import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ShadowLedger#getBalanceView(String)} — issue #42.
 *
 * <p>Exercises the SQL aggregation against a real PostgreSQL container and
 * confirms that the {@code reservedPendingCore} sum tracks {@code core_confirmed}
 * correctly.
 */
class ShadowLedgerBalanceViewIntegrationTest extends AbstractInfrastructureIntegrationTest {

    @Autowired private ShadowLedger shadowLedger;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        redis.delete(redis.keys("balance:*"));
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
    }

    @Test
    void unseededAccountReturnsZerosAndNullTimestamp() {
        AccountBalanceView view = shadowLedger.getBalanceView("ACC-EMPTY");

        assertThat(view.accountId()).isEqualTo("ACC-EMPTY");
        assertThat(view.available()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(view.reservedPendingCore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(view.lastTransactionAt()).isNull();
    }

    @Test
    void availableBalanceReadsFromRedis() {
        redis.opsForValue().set("balance:ACC-LIVE", "123456"); // $1234.56

        AccountBalanceView view = shadowLedger.getBalanceView("ACC-LIVE");

        assertThat(view.available()).isEqualByComparingTo(new BigDecimal("1234.56"));
    }

    @Test
    void reservedPendingCoreSumsUnconfirmedDebits() {
        redis.opsForValue().set("balance:ACC-RES", "100000"); // $1000

        // Two unconfirmed DEBITs, one confirmed DEBIT, one unconfirmed CREDIT (must be excluded)
        insertLedgerRow("ACC-RES", "DEBIT", "100.00", false, "TXN-D1");
        insertLedgerRow("ACC-RES", "DEBIT", "50.00", false, "TXN-D2");
        insertLedgerRow("ACC-RES", "DEBIT", "200.00", true, "TXN-D3"); // confirmed — excluded
        insertLedgerRow("ACC-RES", "CREDIT", "75.00", false, "TXN-C1"); // CREDIT — excluded

        AccountBalanceView view = shadowLedger.getBalanceView("ACC-RES");

        // Only the two unconfirmed DEBITs count: $100 + $50
        assertThat(view.reservedPendingCore()).isEqualByComparingTo(new BigDecimal("150.00"));
    }

    @Test
    void lastTransactionAtReturnsMostRecentRow() {
        insertLedgerRowAt("ACC-TIME", "CREDIT", "100.00", true, "TXN-T1",
                "NOW() - INTERVAL '2' HOUR");
        insertLedgerRowAt("ACC-TIME", "DEBIT", "50.00", false, "TXN-T2",
                "NOW() - INTERVAL '1' HOUR");

        AccountBalanceView view = shadowLedger.getBalanceView("ACC-TIME");

        java.sql.Timestamp expected = jdbc.queryForObject(
                "SELECT MAX(applied_at) FROM shadow_ledger_transaction_log WHERE account_id = ?",
                java.sql.Timestamp.class, "ACC-TIME");
        assertThat(view.lastTransactionAt()).isEqualTo(expected.toInstant());
    }

    private void insertLedgerRow(String accountId, String type, String amount,
                                 boolean coreConfirmed, String transactionId) {
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES (?, ?, ?, ?, ?, 1000.00, 950.00, ?)
                """,
                transactionId, "E2E-" + transactionId, accountId, type,
                new BigDecimal(amount), coreConfirmed);
    }

    private void insertLedgerRowAt(String accountId, String type, String amount,
                                   boolean coreConfirmed, String transactionId,
                                   String appliedAtExpr) {
        jdbc.update(String.format("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed, applied_at)
                VALUES (?, ?, ?, ?, ?, 1000.00, 950.00, ?, %s)
                """, appliedAtExpr),
                transactionId, "E2E-" + transactionId, accountId, type,
                new BigDecimal(amount), coreConfirmed);
    }
}
