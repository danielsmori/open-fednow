package io.openfednow.shadowledger;

import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import io.openfednow.shadowledger.ReconciliationService.ReconciliationAttempt;
import io.openfednow.shadowledger.ShadowLedger.BalanceSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Reconciliation must never erase a payment that lands while it runs, nor
 * confirm ledger rows the reconciled balance never included — issues
 * #142/#144.
 *
 * <p>Real Redis and PostgreSQL. The core adapter is a mock whose balance read
 * applies real Shadow Ledger writes before answering: the interleaving a live
 * system produces when payments are processed while reconciliation waits on
 * the core. Starting point for every test: $1,300 seeded, then a $300 send
 * ({@code TXN-BEFORE}) that the reconciliation covers — shadow $1,000.
 */
@TestPropertySource(properties = "openfednow.bridge-mode.max-aggregate-send-amount=500.00")
class ReconciliationLostUpdateIntegrationTest extends AbstractInfrastructureIntegrationTest {

    private static final String ACCOUNT = "ACC-LOST-UPDATE";

    @Autowired private ReconciliationService reconciliationService;
    @Autowired private ShadowLedger shadowLedger;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private CoreBankingAdapter coreBankingAdapter;

    @BeforeEach
    void setup() {
        deleteKeys("balance:*");
        deleteKeys("balance-version:*");
        deleteKeys("bridge:aggregate:*");
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
        jdbc.update("DELETE FROM reconciliation_run");
        when(coreBankingAdapter.isCoreSystemAvailable()).thenReturn(true);

        shadowLedger.seedBalance(ACCOUNT, new BigDecimal("1300.00"));
        shadowLedger.applyDebit(ACCOUNT, new BigDecimal("300.00"), "TXN-BEFORE");
    }

    // ── Payments landing during the core read ────────────────────────────────

    @Test
    void debitDuringTheCoreReadIsNotErasedWhenBalancesAgree() {
        // the core agrees with the $1,000 snapshot, but a $300 send lands
        // while it is read; a blind overwrite after the read would restore it
        coreAnswers(() -> {
            shadowLedger.applyDebit(ACCOUNT, new BigDecimal("300.00"), "TXN-DURING");
            return new BigDecimal("1000.00");
        }, () -> new BigDecimal("1000.00"));

        ReconciliationAttempt attempt = reconciliationService.attemptReconciliation();

        assertThat(shadowLedger.getAvailableBalance(ACCOUNT)).isEqualByComparingTo("700.00");
        assertThat(confirmed("TXN-BEFORE")).isTrue();
        assertThat(confirmed("TXN-DURING")).as("written after the cutoff").isFalse();
        assertThat(attempt.fullyReconciled()).isTrue();
    }

    @Test
    void writeBetweenSnapshotAndCorrectionIsNeverOverwritten() {
        // First read: a $300 send lands and the core (which has not posted it
        // yet) reports $950 — a $50 card authorization the shadow never saw.
        // Correcting to $950 now would erase the $300. The correction backs
        // off, is retried within the cycle, and the core now reports $650.
        coreAnswers(() -> {
            shadowLedger.applyDebit(ACCOUNT, new BigDecimal("300.00"), "TXN-DURING");
            return new BigDecimal("950.00");
        }, () -> new BigDecimal("650.00"));

        ReconciliationAttempt attempt = reconciliationService.attemptReconciliation();

        assertThat(shadowLedger.getAvailableBalance(ACCOUNT)).isEqualByComparingTo("650.00");
        assertThat(attempt.fullyReconciled()).isTrue();
        assertThat(confirmed("TXN-BEFORE")).isTrue();
        assertThat(confirmed("TXN-DURING")).as("written after the cutoff").isFalse();
        // the correction's own audit row is confirmed in the same cycle
        assertThat(reconciliationRows()).isEqualTo(1);
        assertThat(unconfirmedReconciliationRows()).isZero();
    }

    @Test
    void equalAndOppositeWritesDuringTheCoreReadStillDeferTheCorrection() {
        // A +$100 credit and a -$100 send land during the read, leaving the
        // balance value where the snapshot saw it. The core has posted only the
        // credit (and a $50 card authorization): $1,050. A value comparison
        // would accept the snapshot and erase the $100 send; the version
        // comparison does not, and the retry reads the core again ($950).
        coreAnswers(() -> {
            shadowLedger.applyCredit(ACCOUNT, new BigDecimal("100.00"), "TXN-CREDIT");
            shadowLedger.applyDebit(ACCOUNT, new BigDecimal("100.00"), "TXN-SEND");
            return new BigDecimal("1050.00");
        }, () -> new BigDecimal("950.00"));

        reconciliationService.attemptReconciliation();

        assertThat(shadowLedger.getAvailableBalance(ACCOUNT)).isEqualByComparingTo("950.00");
    }

    @Test
    void hotAccountIsDeferredRatherThanOverwritten() {
        // a payment lands during every one of the cycle's attempts
        AtomicInteger n = new AtomicInteger();
        when(coreBankingAdapter.getAvailableBalance(anyString())).thenAnswer(inv -> {
            shadowLedger.applyDebit(ACCOUNT, new BigDecimal("100.00"), "TXN-HOT-" + n.incrementAndGet());
            return new BigDecimal("950.00");
        });

        ReconciliationAttempt first = reconciliationService.attemptReconciliation();

        assertThat(n.get()).isEqualTo(ReconciliationService.BUSY_ACCOUNT_ATTEMPTS);
        assertThat(shadowLedger.getAvailableBalance(ACCOUNT)).as("nothing overwritten").isEqualByComparingTo("700.00");
        assertThat(confirmed("TXN-BEFORE")).isFalse();
        assertThat(first.deferredAccounts()).isEqualTo(1);
        assertThat(first.report().discrepanciesDetected()).isZero();
        assertThat(first.fullyReconciled()).isFalse();
        assertThat(reconciliationRows()).isZero();

        // the account quiets down: the next cycle settles it
        when(coreBankingAdapter.getAvailableBalance(anyString())).thenReturn(new BigDecimal("650.00"));
        ReconciliationAttempt second = reconciliationService.attemptReconciliation();

        assertThat(shadowLedger.getAvailableBalance(ACCOUNT)).isEqualByComparingTo("650.00");
        assertThat(confirmed("TXN-BEFORE")).isTrue();
        assertThat(confirmed("TXN-HOT-1")).isTrue();
        assertThat(second.fullyReconciled()).isTrue();
    }

    // ── Versioned compare-and-set ────────────────────────────────────────────

    @Test
    void everyBalanceWriteBumpsTheVersion() {
        long v = version();
        shadowLedger.applyCredit(ACCOUNT, new BigDecimal("1.00"), "TXN-V-CREDIT");
        assertThat(version()).isEqualTo(v += 1);
        shadowLedger.applyDebit(ACCOUNT, new BigDecimal("1.00"), "TXN-V-DEBIT");
        assertThat(version()).isEqualTo(v += 1);
        shadowLedger.reverseDebit("TXN-V-DEBIT");
        assertThat(version()).isEqualTo(v += 1);
        shadowLedger.reverseCredit("TXN-V-CREDIT");
        assertThat(version()).isEqualTo(v += 1);
        shadowLedger.applyDebitCapped(ACCOUNT, new BigDecimal("1.00"), "TXN-V-CAPPED");
        assertThat(version()).isEqualTo(v += 1);
        shadowLedger.seedBalance(ACCOUNT, new BigDecimal("500.00"));
        assertThat(version()).isEqualTo(v += 1);
        assertThat(shadowLedger.seedBalanceIfAbsent(ACCOUNT, new BigDecimal("1.00"))).isFalse();
        assertThat(version()).as("no write, no bump").isEqualTo(v);
        assertThat(shadowLedger.reconcileIfUnchanged(ACCOUNT, shadowLedger.snapshotBalance(ACCOUNT),
                new BigDecimal("400.00"))).isTrue();
        assertThat(version()).isEqualTo(v + 1);
    }

    @Test
    void compareAndSetAppliesOnlyWhenNothingWasWrittenSinceTheSnapshot() {
        BalanceSnapshot stale = shadowLedger.snapshotBalance(ACCOUNT);
        shadowLedger.applyCredit(ACCOUNT, new BigDecimal("0.01"), "TXN-CAS");

        assertThat(shadowLedger.reconcileIfUnchanged(ACCOUNT, stale, new BigDecimal("1.00"))).isFalse();
        assertThat(shadowLedger.getAvailableBalance(ACCOUNT)).isEqualByComparingTo("1000.01");
        assertThat(reconciliationRows()).isZero();

        assertThat(shadowLedger.reconcileIfUnchanged(ACCOUNT, shadowLedger.snapshotBalance(ACCOUNT),
                new BigDecimal("950.00"))).isTrue();
        assertThat(shadowLedger.getAvailableBalance(ACCOUNT)).isEqualByComparingTo("950.00");
        assertThat(reconciliationRows()).isEqualTo(1);
    }

    @Test
    void compareAndSetTreatsAnUnseededAccountAsZero() {
        BalanceSnapshot snapshot = shadowLedger.snapshotBalance("ACC-NEVER-SEEDED");
        assertThat(snapshot.balance()).isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(shadowLedger.reconcileIfUnchanged("ACC-NEVER-SEEDED", snapshot, new BigDecimal("25.00")))
                .isTrue();
        assertThat(shadowLedger.getAvailableBalance("ACC-NEVER-SEEDED")).isEqualByComparingTo("25.00");
    }

    @Test
    void correctionAuditRowFitsTheLongestAccountId() {
        // account_id is VARCHAR(34); the audit row's synthetic transaction id
        // (VARCHAR(35)) used to embed it and overflowed above 11 characters
        String longest = "1234567890123456789012345678901234";
        assertThat(longest).hasSize(34);

        assertThat(shadowLedger.reconcileIfUnchanged(longest, shadowLedger.snapshotBalance(longest),
                new BigDecimal("10.00"))).isTrue();

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log "
                        + "WHERE account_id = ? AND transaction_type = 'RECONCILIATION'",
                Integer.class, longest);
        assertThat(rows).isEqualTo(1);
    }

    // ── Capped-debit undo ────────────────────────────────────────────────────

    @Test
    void undoCreditsTheDebitBackEvenIfTheWindowWasClearedMeanwhile() {
        shadowLedger.applyDebitCapped(ACCOUNT, new BigDecimal("100.00"), "TXN-UNDO-CLEARED");
        assertThat(shadowLedger.getAvailableBalance(ACCOUNT)).isEqualByComparingTo("900.00");
        shadowLedger.clearAggregate(ACCOUNT); // operator override, before the undo

        shadowLedger.undoCappedDebit(ACCOUNT, "TXN-UNDO-CLEARED", 10_000L);

        assertThat(shadowLedger.getAvailableBalance(ACCOUNT)).isEqualByComparingTo("1000.00");
        assertThat(shadowLedger.hasOpenAggregate(ACCOUNT)).isFalse();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** First core read runs {@code first} (which may write to the ledger); later reads {@code later}. */
    private void coreAnswers(Supplier<BigDecimal> first, Supplier<BigDecimal> later) {
        AtomicInteger calls = new AtomicInteger();
        when(coreBankingAdapter.getAvailableBalance(anyString()))
                .thenAnswer(inv -> calls.getAndIncrement() == 0 ? first.get() : later.get());
    }

    private long version() {
        String raw = redis.opsForValue().get("balance-version:" + ACCOUNT);
        return raw == null ? 0L : Long.parseLong(raw);
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

    private int unconfirmedReconciliationRows() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log "
                        + "WHERE transaction_type = 'RECONCILIATION' AND core_confirmed = FALSE",
                Integer.class);
        return n == null ? 0 : n;
    }

    private void deleteKeys(String pattern) {
        Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
}
