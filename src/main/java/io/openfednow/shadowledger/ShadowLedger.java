package io.openfednow.shadowledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Layer 4 — Shadow Ledger
 *
 * <p>Maintains a real-time view of account balances that is independent of
 * the core banking system. This is the key component that allows a financial
 * institution to participate in FedNow 24/7 even when its core banking
 * system is offline for scheduled maintenance.
 *
 * <p>Balances are stored in Redis as integer cents to avoid floating-point
 * precision issues. Debits use optimistic locking (WATCH/MULTI/EXEC) with
 * retry to prevent race conditions on concurrent payment requests. Every
 * operation is appended to {@code shadow_ledger_transaction_log} in
 * PostgreSQL as an immutable audit trail.
 *
 * @see ReconciliationService
 * @see AvailabilityBridge
 */
@Component
public class ShadowLedger {

    private static final Logger log = LoggerFactory.getLogger(ShadowLedger.class);

    /** Redis key prefix for account balance cache (stored as integer cents). */
    static final String BALANCE_KEY_PREFIX = "balance:";

    /** Maximum retry attempts when an optimistic lock conflict is detected. */
    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    public ShadowLedger(StringRedisTemplate redis, JdbcTemplate jdbc) {
        this.redis = redis;
        this.jdbc = jdbc;
    }

    /**
     * Returns the current available balance for an account from the Shadow Ledger.
     * Returns {@code ZERO} if the account has not been seeded (no balance in Redis).
     *
     * @param accountId the institution's internal account identifier
     * @return available balance in USD
     */
    public BigDecimal getAvailableBalance(String accountId) {
        String raw = redis.opsForValue().get(BALANCE_KEY_PREFIX + accountId);
        if (raw == null) {
            log.debug("Shadow Ledger balance not seeded for account={}, returning 0", accountId);
            return BigDecimal.ZERO;
        }
        return centsToDollars(Long.parseLong(raw));
    }

    /**
     * Applies a debit to an account using optimistic locking.
     *
     * <p>Uses Redis WATCH/MULTI/EXEC to atomically check the balance and
     * apply the debit. If a concurrent update is detected (EXEC returns null),
     * the operation is retried up to {@code MAX_RETRY_ATTEMPTS} times.
     *
     * @throws IllegalArgumentException if the account balance is insufficient
     * @throws IllegalStateException    if optimistic lock conflicts persist after retries
     */
    public void applyDebit(String accountId, BigDecimal amount, String transactionId) {
        String key = BALANCE_KEY_PREFIX + accountId;
        long amountCents = dollarsToCents(amount);

        for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
            final long[] capturedBalance = new long[1];

            List<Object> txResult = redis.execute(new SessionCallback<List<Object>>() {
                @Override
                @SuppressWarnings("unchecked")
                public List<Object> execute(RedisOperations ops) throws DataAccessException {
                    ops.watch(key);
                    String raw = (String) ops.opsForValue().get(key);
                    long currentCents = raw != null ? Long.parseLong(raw) : 0L;
                    capturedBalance[0] = currentCents;

                    if (currentCents < amountCents) {
                        ops.unwatch();
                        throw new IllegalArgumentException(
                                String.format("Insufficient funds: account=%s balance=%s requested=%s",
                                        accountId, centsToDollars(currentCents), amount));
                    }

                    ops.multi();
                    ops.opsForValue().set(key, String.valueOf(currentCents - amountCents));
                    return ops.exec();
                }
            });

            if (txResult != null) {
                long balanceBeforeCents = capturedBalance[0];
                long balanceAfterCents = balanceBeforeCents - amountCents;
                logTransaction(transactionId, transactionId, accountId, "DEBIT",
                        amount, centsToDollars(balanceBeforeCents), centsToDollars(balanceAfterCents));
                log.info("Shadow Ledger debit applied account={} amount={} balanceAfter={}",
                        accountId, amount, centsToDollars(balanceAfterCents));
                return;
            }

            log.debug("Shadow Ledger debit conflict, retrying attempt={} account={}", attempt + 1, accountId);
        }

        throw new IllegalStateException(
                "Shadow Ledger debit failed after " + MAX_RETRY_ATTEMPTS + " retries: account=" + accountId);
    }

    /**
     * Applies a credit to an account atomically using Redis INCRBY.
     */
    public void applyCredit(String accountId, BigDecimal amount, String transactionId) {
        String key = BALANCE_KEY_PREFIX + accountId;
        long amountCents = dollarsToCents(amount);

        Long newCents = redis.opsForValue().increment(key, amountCents);
        if (newCents == null) {
            log.warn("Shadow Ledger credit increment returned null for account={}", accountId);
            return;
        }

        long balanceBeforeCents = newCents - amountCents;
        logTransaction(transactionId, transactionId, accountId, "CREDIT",
                amount, centsToDollars(balanceBeforeCents), centsToDollars(newCents));
        log.info("Shadow Ledger credit applied account={} amount={} balanceAfter={}",
                accountId, amount, centsToDollars(newCents));
    }

    /**
     * Reverses a previously applied debit by looking up the original DEBIT entry
     * in the transaction log and crediting the same amount back.
     */
    public void reverseDebit(String transactionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT account_id, amount FROM shadow_ledger_transaction_log " +
                "WHERE transaction_id = ? AND transaction_type = 'DEBIT' " +
                "ORDER BY applied_at LIMIT 1",
                transactionId);

        if (rows.isEmpty()) {
            log.warn("Shadow Ledger: no DEBIT found for reversal transactionId={}", transactionId);
            return;
        }

        String accountId = (String) rows.get(0).get("account_id");
        BigDecimal amount = (BigDecimal) rows.get(0).get("amount");
        String key = BALANCE_KEY_PREFIX + accountId;
        long amountCents = dollarsToCents(amount);

        Long newCents = redis.opsForValue().increment(key, amountCents);
        if (newCents == null) {
            log.warn("Shadow Ledger reversal increment returned null for account={}", accountId);
            return;
        }

        long balanceBeforeCents = newCents - amountCents;
        logTransaction(transactionId, transactionId, accountId, "REVERSAL",
                amount, centsToDollars(balanceBeforeCents), centsToDollars(newCents));
        log.info("Shadow Ledger reversal applied account={} amount={} balanceAfter={}",
                accountId, amount, centsToDollars(newCents));
    }

    /**
     * Overwrites the Shadow Ledger balance with the core system's confirmed balance.
     * Logs the reconciliation with discrepancy detection. No-ops when balances match.
     */
    public void reconcile(String accountId, BigDecimal confirmedBalance) {
        String key = BALANCE_KEY_PREFIX + accountId;
        long confirmedCents = dollarsToCents(confirmedBalance);

        String raw = redis.opsForValue().get(key);
        long shadowCents = raw != null ? Long.parseLong(raw) : 0L;

        if (shadowCents != confirmedCents) {
            log.warn("Shadow Ledger discrepancy detected account={} shadow={} confirmed={}",
                    accountId, centsToDollars(shadowCents), confirmedBalance);
        } else {
            log.debug("Shadow Ledger reconciliation: balances match account={}", accountId);
        }

        redis.opsForValue().set(key, String.valueOf(confirmedCents));

        BigDecimal diff = confirmedBalance.subtract(centsToDollars(shadowCents)).abs();
        if (diff.compareTo(BigDecimal.ZERO) > 0) {
            String syntheticTxnId = "RECONCILE-" + accountId + "-" + System.currentTimeMillis();
            logTransaction(syntheticTxnId, syntheticTxnId, accountId, "RECONCILIATION",
                    diff, centsToDollars(shadowCents), confirmedBalance);
        }

        log.info("Shadow Ledger reconciled account={} confirmedBalance={}", accountId, confirmedBalance);
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    /** Converts a USD dollar amount to integer cents for Redis storage. */
    static long dollarsToCents(BigDecimal dollars) {
        return dollars.setScale(2, RoundingMode.HALF_UP)
                      .multiply(BigDecimal.valueOf(100))
                      .longValueExact();
    }

    /** Converts integer cents from Redis to a USD dollar amount. */
    static BigDecimal centsToDollars(long cents) {
        return BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /** Appends an immutable entry to the shadow ledger audit log. */
    private void logTransaction(String transactionId, String endToEndId, String accountId,
                                String type, BigDecimal amount,
                                BigDecimal balanceBefore, BigDecimal balanceAfter) {
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES (?, ?, ?, ?, ?, ?, ?, FALSE)
                """,
                transactionId, endToEndId, accountId, type, amount, balanceBefore, balanceAfter);
    }
}
