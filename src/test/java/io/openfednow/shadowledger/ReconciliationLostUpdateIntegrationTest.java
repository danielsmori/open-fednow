package io.openfednow.shadowledger;

import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import io.openfednow.shadowledger.ReconciliationService.ReconciliationAttempt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Reconciliation must never erase a payment that lands while it runs, nor
 * confirm ledger rows the reconciled balance never included — issues
 * #142/#144.
 *
 * <p>Real Redis and PostgreSQL. The core adapter is a mock whose balance read
 * applies a real Shadow Ledger debit before answering: exactly the
 * interleaving a live system produces when a send is processed while
 * reconciliation is waiting on the core.
 */
class ReconciliationLostUpdateIntegrationTest extends AbstractInfrastructureIntegrationTest {

    private static final String ACCOUNT = "ACC-LOST-UPDATE";

    @Autowired private ReconciliationService reconciliationService;
    @Autowired private ShadowLedger shadowLedger;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private CoreBankingAdapter coreBankingAdapter;

    @BeforeEach
    void setup() {
        Set<String> keys = redis.keys("balance:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
        jdbc.update("DELETE FROM reconciliation_run");
        when(coreBankingAdapter.isCoreSystemAvailable()).thenReturn(true);

        // $1,300 seeded, then a $300 send that the next reconciliation covers
        redis.opsForValue().set("balance:" + ACCOUNT, "130000");
        shadowLedger.applyDebit(ACCOUNT, new BigDecimal("300.00"), "TXN-BEFORE");
    }

    @Test
    void debitDuringTheCoreReadIsNotErasedWhenBalancesAgree() {
        // The core agrees with the $1,000 snapshot — but while it is being read
        // another $300 send lands. A blind overwrite after the read would
        // restore the $300.
        coreAnswersAfterADebitLands(new BigDecimal("1000.00"));

        ReconciliationAttempt attempt = reconciliationService.attemptReconciliation();

        assertThat(shadowLedger.getAvailableBalance(ACCOUNT)).isEqualByComparingTo("700.00");
        // the row the reconciled balance covered is confirmed; the one written
        // during the core read is not
        assertThat(confirmed("TXN-BEFORE")).isTrue();
        assertThat(confirmed("TXN-DURING")).isFalse();
        assertThat(attempt.fullyReconciled()).isTrue();
    }

    @Test
    void correctionIsDeferredRatherThanOverwritingAConcurrentDebit() {
        // The core reports $950 (e.g. a $50 card authorization the Shadow
        // Ledger never saw), and a $300 send lands during the read.
        coreAnswersAfterADebitLands(new BigDecimal("950.00"));

        ReconciliationAttempt first = reconciliationService.attemptReconciliation();

        // nothing overwritten, nothing confirmed, the account is retried
        assertThat(shadowLedger.getAvailableBalance(ACCOUNT)).isEqualByComparingTo("700.00");
        assertThat(confirmed("TXN-BEFORE")).isFalse();
        assertThat(confirmed("TXN-DURING")).isFalse();
        assertThat(first.deferredAccounts()).isEqualTo(1);
        assertThat(first.fullyReconciled()).isFalse();
        assertThat(reconciliationRows()).isZero();

        // next cycle, quiet account: the core now reflects both sends
        when(coreBankingAdapter.getAvailableBalance(anyString())).thenReturn(new BigDecimal("650.00"));
        ReconciliationAttempt second = reconciliationService.attemptReconciliation();

        assertThat(shadowLedger.getAvailableBalance(ACCOUNT)).isEqualByComparingTo("650.00");
        assertThat(confirmed("TXN-BEFORE")).isTrue();
        assertThat(confirmed("TXN-DURING")).isTrue();
        assertThat(second.fullyReconciled()).isTrue();
        assertThat(reconciliationRows()).isEqualTo(1);
    }

    // ── reconcileIfUnchanged ─────────────────────────────────────────────────

    @Test
    void compareAndSetAppliesOnlyWhenTheBalanceIsUnchanged() {
        assertThat(shadowLedger.reconcileIfUnchanged(ACCOUNT, new BigDecimal("999.99"), new BigDecimal("1.00")))
                .isFalse();
        assertThat(shadowLedger.getAvailableBalance(ACCOUNT)).isEqualByComparingTo("1000.00");
        assertThat(reconciliationRows()).isZero();

        assertThat(shadowLedger.reconcileIfUnchanged(ACCOUNT, new BigDecimal("1000.00"), new BigDecimal("950.00")))
                .isTrue();
        assertThat(shadowLedger.getAvailableBalance(ACCOUNT)).isEqualByComparingTo("950.00");
        assertThat(reconciliationRows()).isEqualTo(1);
    }

    @Test
    void compareAndSetTreatsAnUnseededAccountAsZero() {
        assertThat(shadowLedger.reconcileIfUnchanged("ACC-NEVER-SEEDED", BigDecimal.ZERO, new BigDecimal("25.00")))
                .isTrue();
        assertThat(shadowLedger.getAvailableBalance("ACC-NEVER-SEEDED")).isEqualByComparingTo("25.00");
    }

    @Test
    void correctionAuditRowFitsTheLongestAccountId() {
        // account_id is VARCHAR(34); the audit row's synthetic transaction id
        // (VARCHAR(35)) used to embed it and overflowed above 11 characters
        String longest = "1234567890123456789012345678901234";
        assertThat(longest).hasSize(34);

        assertThat(shadowLedger.reconcileIfUnchanged(longest, BigDecimal.ZERO, new BigDecimal("10.00"))).isTrue();

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log "
                        + "WHERE account_id = ? AND transaction_type = 'RECONCILIATION'",
                Integer.class, longest);
        assertThat(rows).isEqualTo(1);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** The core read lets one more $300 send land, then answers {@code balance}. */
    private void coreAnswersAfterADebitLands(BigDecimal balance) {
        AtomicInteger calls = new AtomicInteger();
        when(coreBankingAdapter.getAvailableBalance(anyString())).thenAnswer(inv -> {
            if (calls.getAndIncrement() == 0) {
                shadowLedger.applyDebit(ACCOUNT, new BigDecimal("300.00"), "TXN-DURING");
            }
            return balance;
        });
    }

    private boolean confirmed(String transactionId) {
        Boolean c = jdbc.queryForObject(
                "SELECT core_confirmed FROM shadow_ledger_transaction_log "
                        + "WHERE transaction_id = ? AND transaction_type = 'DEBIT'",
                Boolean.class, transactionId);
        return Boolean.TRUE.equals(c);
    }

    private int reconciliationRows() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log WHERE transaction_type = 'RECONCILIATION'",
                Integer.class);
        return n == null ? 0 : n;
    }
}
