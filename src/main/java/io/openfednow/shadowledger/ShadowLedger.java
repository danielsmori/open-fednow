package io.openfednow.shadowledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
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

    /**
     * Redis key prefix for the per-account bridge-mode aggregate send window
     * (issue #68). The value is a hash: {@code total} holds the counted
     * aggregate in integer cents, {@code opened_at} the epoch second the window
     * opened, and one {@code txn:<transactionId>} field per counted send holds
     * that send's amount in cents so a reversal can release exactly what was
     * counted. The key carries <strong>no TTL</strong> — the cap fails closed
     * and only {@link #clearAggregate(String)} (called by reconciliation once
     * the core has confirmed the account's balance) removes it.
     */
    static final String AGGREGATE_KEY_PREFIX = "bridge:aggregate:";

    /** Hash field holding the counted aggregate total in integer cents. */
    static final String AGGREGATE_TOTAL_FIELD = "total";

    /** Hash field holding the epoch second the aggregate window opened. */
    static final String AGGREGATE_OPENED_AT_FIELD = "opened_at";

    /** Prefix of the per-send hash fields ({@code txn:<transactionId>} → cents). */
    static final String AGGREGATE_TXN_FIELD_PREFIX = "txn:";

    /** Maximum retry attempts when an optimistic lock conflict is detected. */
    private static final int MAX_RETRY_ATTEMPTS = 3;

    /**
     * Atomic, idempotent release of one counted send from the aggregate window.
     * KEYS[1] = aggregate hash key, ARGV[1] = the send's {@code txn:<id>} field,
     * ARGV[2] = the {@code total} field name.
     *
     * <p>The per-send field is the idempotency token: the first call deletes it
     * and decrements {@code total} by the amount that was counted; any repeat —
     * a duplicate compensation, the retry sweep, or a reversal arriving after
     * reconciliation already cleared the window and a new one opened — finds no
     * field and does nothing. A late reversal therefore can never decrement a
     * newer window's total.
     */
    private static final RedisScript<Long> AGGREGATE_RELEASE_SCRIPT = new DefaultRedisScript<>(
            "local amt = redis.call('HGET', KEYS[1], ARGV[1])\n" +
                    "if amt then\n" +
                    "  redis.call('HDEL', KEYS[1], ARGV[1])\n" +
                    "  redis.call('HINCRBY', KEYS[1], ARGV[2], -tonumber(amt))\n" +
                    "  return tonumber(amt)\n" +
                    "end\n" +
                    "return nil",
            Long.class);

    /** Outcome of {@link #applyDebitCapped}. */
    public enum CappedDebitResult {
        /** Balance debited and the amount counted toward the aggregate window. */
        APPLIED,
        /** Rejected: the Shadow Ledger balance is insufficient. Nothing changed. */
        INSUFFICIENT_FUNDS,
        /** Rejected: the aggregate window would exceed the configured cap. Nothing changed. */
        AGGREGATE_CAP_EXCEEDED
    }

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
     * Returns a richer operator-facing snapshot of an account's Shadow Ledger state.
     *
     * <p>Combines the live Redis balance with the unconfirmed-DEBIT total from the
     * transaction log and the most-recent {@code applied_at} timestamp. Backed by
     * {@code idx_sltl_account_id} so cost scales with rows for the account, not
     * with the size of the log.
     *
     * @param accountId institution-internal account identifier
     * @return a {@link AccountBalanceView} — fields are zero / null if the account
     *         has not been seeded or has no transaction history
     */
    public AccountBalanceView getBalanceView(String accountId) {
        BigDecimal available = getAvailableBalance(accountId);

        BigDecimal reservedPendingCore = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(amount), 0)
                FROM shadow_ledger_transaction_log
                WHERE account_id = ?
                  AND transaction_type = 'DEBIT'
                  AND core_confirmed = FALSE
                """,
                BigDecimal.class,
                accountId);

        java.sql.Timestamp lastApplied = jdbc.queryForObject(
                """
                SELECT MAX(applied_at)
                FROM shadow_ledger_transaction_log
                WHERE account_id = ?
                """,
                java.sql.Timestamp.class,
                accountId);

        return new AccountBalanceView(
                accountId,
                available,
                reservedPendingCore != null ? reservedPendingCore : BigDecimal.ZERO,
                lastApplied != null ? lastApplied.toInstant() : null);
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

            if (txResult != null && !txResult.isEmpty()) {
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
     * Applies a debit and counts it toward the account's bridge-mode aggregate
     * send window, enforcing the aggregate cap — issue #68.
     *
     * <p>Both checks (sufficient balance, aggregate within cap) and both writes
     * (balance decrement, aggregate increment plus the per-send field) run in a
     * single Redis WATCH/MULTI/EXEC transaction over the balance key and the
     * aggregate hash, extending the optimistic-locking approach of ADR-0001.
     * Either everything commits or nothing does; a send can never be debited
     * without being counted, and never counted without being debited.
     *
     * <p>The caller decides <em>when</em> a send must be counted (in bridge
     * mode, or while a not-yet-reconciled window is still open); whenever this
     * method is invoked the send is counted. Rejections are returned as values,
     * not exceptions, because the caller must map them to a clean ISO 20022
     * {@code AM04} response rather than an error path.
     *
     * @param aggregateCapCents the configured ceiling in integer cents
     *                          ({@code BridgeAggregateCapPolicy.getMaxAggregateSendCents()})
     * @return the outcome; on {@link CappedDebitResult#APPLIED} a DEBIT audit
     *         row has been written like {@link #applyDebit}
     * @throws IllegalStateException if optimistic lock conflicts persist after retries
     */
    public CappedDebitResult applyDebitCapped(String accountId, BigDecimal amount,
                                              String transactionId, long aggregateCapCents) {
        String balanceKey = BALANCE_KEY_PREFIX + accountId;
        String aggregateKey = AGGREGATE_KEY_PREFIX + accountId;
        String txnField = AGGREGATE_TXN_FIELD_PREFIX + transactionId;
        long amountCents = dollarsToCents(amount);

        for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
            final long[] capturedBalance = new long[1];
            final long[] capturedAggregate = new long[1];
            final CappedDebitResult[] rejection = new CappedDebitResult[1];

            List<Object> txResult = redis.execute(new SessionCallback<List<Object>>() {
                @Override
                @SuppressWarnings("unchecked")
                public List<Object> execute(RedisOperations ops) throws DataAccessException {
                    ops.watch(List.of(balanceKey, aggregateKey));

                    String rawBalance = (String) ops.opsForValue().get(balanceKey);
                    long currentCents = rawBalance != null ? Long.parseLong(rawBalance) : 0L;
                    capturedBalance[0] = currentCents;

                    Object rawTotal = ops.opsForHash().get(aggregateKey, AGGREGATE_TOTAL_FIELD);
                    long aggregateCents = rawTotal != null ? Long.parseLong((String) rawTotal) : 0L;
                    capturedAggregate[0] = aggregateCents;

                    if (currentCents < amountCents) {
                        ops.unwatch();
                        rejection[0] = CappedDebitResult.INSUFFICIENT_FUNDS;
                        return null;
                    }
                    if (aggregateCents + amountCents > aggregateCapCents) {
                        ops.unwatch();
                        rejection[0] = CappedDebitResult.AGGREGATE_CAP_EXCEEDED;
                        return null;
                    }

                    ops.multi();
                    ops.opsForValue().set(balanceKey, String.valueOf(currentCents - amountCents));
                    ops.opsForHash().increment(aggregateKey, AGGREGATE_TOTAL_FIELD, amountCents);
                    ops.opsForHash().putIfAbsent(aggregateKey, AGGREGATE_OPENED_AT_FIELD,
                            String.valueOf(Instant.now().getEpochSecond()));
                    ops.opsForHash().put(aggregateKey, txnField, String.valueOf(amountCents));
                    return ops.exec();
                }
            });

            if (rejection[0] == CappedDebitResult.INSUFFICIENT_FUNDS) {
                log.info("Shadow Ledger capped debit rejected: insufficient funds account={} "
                                + "balance={} requested={}",
                        io.openfednow.security.pii.PiiRedactor.maskAccount(accountId),
                        centsToDollars(capturedBalance[0]), amount);
                return rejection[0];
            }
            if (rejection[0] == CappedDebitResult.AGGREGATE_CAP_EXCEEDED) {
                log.warn("Shadow Ledger capped debit rejected: aggregate cap would be exceeded "
                                + "account={} aggregate={} requested={} cap={}",
                        io.openfednow.security.pii.PiiRedactor.maskAccount(accountId),
                        centsToDollars(capturedAggregate[0]), amount,
                        centsToDollars(aggregateCapCents));
                return rejection[0];
            }

            if (txResult != null && !txResult.isEmpty()) {
                long balanceBeforeCents = capturedBalance[0];
                long balanceAfterCents = balanceBeforeCents - amountCents;
                logTransaction(transactionId, transactionId, accountId, "DEBIT",
                        amount, centsToDollars(balanceBeforeCents), centsToDollars(balanceAfterCents));
                log.info("Shadow Ledger capped debit applied account={} amount={} balanceAfter={} "
                                + "aggregateAfter={}",
                        io.openfednow.security.pii.PiiRedactor.maskAccount(accountId), amount,
                        centsToDollars(balanceAfterCents),
                        centsToDollars(capturedAggregate[0] + amountCents));
                return CappedDebitResult.APPLIED;
            }

            log.debug("Shadow Ledger capped debit conflict, retrying attempt={} account={}",
                    attempt + 1, accountId);
        }

        throw new IllegalStateException(
                "Shadow Ledger capped debit failed after " + MAX_RETRY_ATTEMPTS
                        + " retries: account=" + accountId);
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
     *
     * <p><strong>Idempotent.</strong> If a {@code REVERSAL} row already exists for this
     * {@code transactionId} the method is a no-op. This makes the operation safe to
     * retry — saga recovery, timeout-driven compensation, and per-saga compensation
     * paths can all invoke it more than once without double-crediting the account.
     */
    public void reverseDebit(String transactionId) {
        if (reversalAlreadyExists(transactionId)) {
            log.debug("Shadow Ledger reversal already recorded — skipping transactionId={}",
                    transactionId);
            return;
        }

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

        // Release the send from the bridge-mode aggregate window last, so a
        // crash between the balance credit and this release leaves the window
        // overcounted — the fail-closed direction for a risk cap (#68). The
        // script no-ops when the send was never counted (online-mode debit) or
        // was already released (duplicate compensation, cleared window).
        releaseFromAggregate(accountId, transactionId);
    }

    /**
     * Releases a reversed send from the account's aggregate window via
     * {@link #AGGREGATE_RELEASE_SCRIPT}. Idempotent and window-scoped; see the
     * script's documentation.
     */
    private void releaseFromAggregate(String accountId, String transactionId) {
        String aggregateKey = AGGREGATE_KEY_PREFIX + accountId;
        Long releasedCents = redis.execute(AGGREGATE_RELEASE_SCRIPT,
                List.of(aggregateKey),
                AGGREGATE_TXN_FIELD_PREFIX + transactionId, AGGREGATE_TOTAL_FIELD);
        if (releasedCents != null) {
            log.info("Bridge aggregate released account={} transactionId={} amount={}",
                    io.openfednow.security.pii.PiiRedactor.maskAccount(accountId),
                    transactionId, centsToDollars(releasedCents));
        } else {
            log.debug("Bridge aggregate release no-op (not counted or already released) "
                    + "transactionId={}", transactionId);
        }
    }

    /**
     * Returns true if any REVERSAL row already exists for the given transactionId.
     * Used by {@link #reverseDebit(String)} and {@link #reverseCredit(String)} to
     * guarantee idempotency under retry.
     */
    private boolean reversalAlreadyExists(String transactionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log " +
                "WHERE transaction_id = ? AND transaction_type = 'REVERSAL'",
                Integer.class, transactionId);
        return count != null && count > 0;
    }

    /**
     * Reverses a previously applied credit by looking up the original CREDIT entry
     * in the transaction log and debiting the same amount back.
     *
     * <p>Mirror of {@link #reverseDebit(String)} for the inbound-payment cancellation
     * path: an inbound credit was applied to the creditor's Shadow Ledger balance,
     * and a downstream cancellation requires that credit to be undone.
     *
     * <p>The reversal is recorded as a {@code REVERSAL} row in the audit log so the
     * reconciliation cycle can match the action against the core's view of the
     * cancelled transaction.
     *
     * <p><strong>Idempotent.</strong> If a {@code REVERSAL} row already exists for
     * this {@code transactionId} the method is a no-op — safe to retry from
     * recovery, timeout, or per-saga compensation paths without double-debiting.
     */
    public void reverseCredit(String transactionId) {
        if (reversalAlreadyExists(transactionId)) {
            log.debug("Shadow Ledger reversal already recorded — skipping transactionId={}",
                    transactionId);
            return;
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT account_id, amount FROM shadow_ledger_transaction_log " +
                "WHERE transaction_id = ? AND transaction_type = 'CREDIT' " +
                "ORDER BY applied_at LIMIT 1",
                transactionId);

        if (rows.isEmpty()) {
            log.warn("Shadow Ledger: no CREDIT found for reversal transactionId={}", transactionId);
            return;
        }

        String accountId = (String) rows.get(0).get("account_id");
        BigDecimal amount = (BigDecimal) rows.get(0).get("amount");
        String key = BALANCE_KEY_PREFIX + accountId;
        long amountCents = dollarsToCents(amount);

        Long newCents = redis.opsForValue().increment(key, -amountCents);
        if (newCents == null) {
            log.warn("Shadow Ledger reversal decrement returned null for account={}", accountId);
            return;
        }

        long balanceBeforeCents = newCents + amountCents;
        logTransaction(transactionId, transactionId, accountId, "REVERSAL",
                amount, centsToDollars(balanceBeforeCents), centsToDollars(newCents));
        log.info("Shadow Ledger credit reversal applied account={} amount={} balanceAfter={}",
                accountId, amount, centsToDollars(newCents));
    }

    /**
     * Seeds the Shadow Ledger balance only when Redis has no entry for the account.
     *
     * <p>Used at application startup by {@link BalanceSeedService} so a fresh deployment
     * (or one whose Redis cache was evicted / restarted) does not begin life with
     * every account reading zero. Uses Redis {@code SETNX} semantics so a live
     * balance is never clobbered by the startup seed.
     *
     * @return {@code true} if the value was written, {@code false} if a balance already
     *         existed for the account
     */
    public boolean seedBalanceIfAbsent(String accountId, BigDecimal balance) {
        long cents = dollarsToCents(balance);
        Boolean written = redis.opsForValue()
                .setIfAbsent(BALANCE_KEY_PREFIX + accountId, String.valueOf(cents));
        boolean seeded = Boolean.TRUE.equals(written);
        if (seeded) {
            log.info("Shadow Ledger seeded account={} balance={}", accountId, balance);
        } else {
            log.debug("Shadow Ledger seed skipped (already present) account={}", accountId);
        }
        return seeded;
    }

    /**
     * Unconditionally overwrites the Shadow Ledger balance with the supplied value.
     *
     * <p>Used by the on-demand admin seed endpoint, where the operator has
     * explicitly asked for the balance to be replaced with the core's current view.
     * Distinct from {@link #reconcile(String, BigDecimal)} in that this is a
     * one-shot operator action and does not log a RECONCILIATION audit row —
     * those rows belong to the scheduled reconciliation cycle.
     */
    public void seedBalance(String accountId, BigDecimal balance) {
        long cents = dollarsToCents(balance);
        redis.opsForValue().set(BALANCE_KEY_PREFIX + accountId, String.valueOf(cents));
        log.info("Shadow Ledger force-seeded account={} balance={}", accountId, balance);
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
                    io.openfednow.security.pii.PiiRedactor.maskAccount(accountId),
                    centsToDollars(shadowCents), confirmedBalance);
        } else {
            log.debug("Shadow Ledger reconciliation: balances match account={}",
                    io.openfednow.security.pii.PiiRedactor.maskAccount(accountId));
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

    /**
     * Closes an account's bridge-mode aggregate send window — issue #68.
     *
     * <p>Must be called only after the core is back online <em>and</em> the
     * account's authoritative balance has been confirmed (the reconciliation
     * cycle calls this after its per-account database transaction commits).
     * Nothing else closes a window: there is no TTL, so an unexpectedly long
     * maintenance window keeps the cap in force rather than silently resetting
     * it.
     *
     * @return {@code true} if a window existed and was cleared
     */
    public boolean clearAggregate(String accountId) {
        Boolean deleted = redis.delete(AGGREGATE_KEY_PREFIX + accountId);
        boolean cleared = Boolean.TRUE.equals(deleted);
        if (cleared) {
            log.info("Bridge aggregate window cleared account={}",
                    io.openfednow.security.pii.PiiRedactor.maskAccount(accountId));
        }
        return cleared;
    }

    /**
     * Returns the account's open aggregate send window, or {@code null} when no
     * window is open. Used by the outbound guard (a window still open after the
     * core returns keeps the cap in force until reconciliation clears it) and
     * by the aggregate metrics.
     */
    public BridgeAggregateView getAggregateView(String accountId) {
        Map<Object, Object> entries = redis.opsForHash()
                .entries(AGGREGATE_KEY_PREFIX + accountId);
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        Object rawTotal = entries.get(AGGREGATE_TOTAL_FIELD);
        Object rawOpenedAt = entries.get(AGGREGATE_OPENED_AT_FIELD);
        long countedSends = entries.keySet().stream()
                .filter(k -> ((String) k).startsWith(AGGREGATE_TXN_FIELD_PREFIX))
                .count();
        return new BridgeAggregateView(
                accountId,
                rawTotal != null ? centsToDollars(Long.parseLong((String) rawTotal)) : BigDecimal.ZERO,
                rawOpenedAt != null ? Instant.ofEpochSecond(Long.parseLong((String) rawOpenedAt)) : null,
                countedSends);
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
