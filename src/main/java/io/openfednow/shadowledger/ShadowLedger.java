package io.openfednow.shadowledger;

import io.openfednow.bridgemode.BridgeAggregateCapPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * Redis key prefix for each account's balance <em>version</em>: a counter
     * every balance write increments in the same atomic step. Reconciliation
     * compares versions, not values, so any write between its snapshot and its
     * correction — even an equal-and-opposite pair that leaves the value
     * unchanged — makes the correction back off (#142/#144). A separate prefix
     * (not {@code balance:ver:}) so no account id can collide with it.
     */
    static final String VERSION_KEY_PREFIX = "balance-version:";

    /** INCRBY the balance and bump its version. KEYS: balance, version; ARGV[1]: delta cents. */
    private static final RedisScript<Long> BALANCE_INCRBY_SCRIPT = new DefaultRedisScript<>(
            "local n = redis.call('INCRBY', KEYS[1], ARGV[1])\n" +
                    "redis.call('INCR', KEYS[2])\n" +
                    "return n",
            Long.class);

    /** SET the balance and bump its version. KEYS: balance, version; ARGV[1]: cents. */
    private static final RedisScript<Long> BALANCE_SET_SCRIPT = new DefaultRedisScript<>(
            "redis.call('SET', KEYS[1], ARGV[1])\n" +
                    "redis.call('INCR', KEYS[2])\n" +
                    "return 1",
            Long.class);

    /** SETNX the balance; bump the version only if it was written. Returns 1 if written. */
    private static final RedisScript<Long> BALANCE_SETNX_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('SETNX', KEYS[1], ARGV[1]) == 1 then\n" +
                    "  redis.call('INCR', KEYS[2])\n" +
                    "  return 1\n" +
                    "end\n" +
                    "return 0",
            Long.class);

    /**
     * Redis key prefix for the per-account bridge-mode aggregate send window
     * (issue #68). The value is a hash: {@code total} holds the counted
     * aggregate in integer cents, {@code opened_at} the epoch second the window
     * opened, and one {@code txn:<transactionId>} field per counted send holds
     * that send's amount in cents so a reversal can release exactly what was
     * counted. The key carries <strong>no TTL</strong> — the cap fails closed.
     * Sends leave the window only by reversal ({@link #reverseDebit}) or by
     * core confirmation ({@link #closeConfirmedAggregate}, called by
     * reconciliation); the key disappears once no counted send remains.
     */
    static final String AGGREGATE_KEY_PREFIX = "bridge:aggregate:";

    /** Hash field holding the counted aggregate total in integer cents. */
    static final String AGGREGATE_TOTAL_FIELD = "total";

    /** Hash field holding the epoch second the aggregate window opened. */
    static final String AGGREGATE_OPENED_AT_FIELD = "opened_at";

    /** Prefix of the per-send hash fields ({@code txn:<transactionId>} → cents). */
    static final String AGGREGATE_TXN_FIELD_PREFIX = "txn:";

    /** Transaction ids per release-eligibility query (see {@link #confirmedAndResolved}). */
    static final int RELEASE_QUERY_BATCH = 500;

    /** Maximum retry attempts when an optimistic lock conflict is detected. */
    private static final int MAX_RETRY_ATTEMPTS = 3;

    /**
     * Atomic, idempotent release of one counted send from the aggregate window.
     * KEYS[1] = aggregate hash key, ARGV[1] = the send's {@code txn:<id>} field,
     * ARGV[2] = the {@code total} field name, ARGV[3] = the {@code txn:} prefix.
     *
     * <p>The total is decremented <em>before</em> the field is deleted: Redis
     * does not roll a script back on error, so the reverse order could delete
     * the field yet leave the total counted. A window left with no counted
     * send is deleted — it holds no exposure, and keeping it would keep the
     * account's later online sends on the capped path for nothing.
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
                    "if not amt then return nil end\n" +
                    "if tonumber(amt) ~= 0 then redis.call('HINCRBY', KEYS[1], ARGV[2], '-' .. amt) end\n" +
                    "redis.call('HDEL', KEYS[1], ARGV[1])\n" +
                    "local prefixLen = string.len(ARGV[3])\n" +
                    "for _, k in ipairs(redis.call('HKEYS', KEYS[1])) do\n" +
                    "  if string.sub(k, 1, prefixLen) == ARGV[3] then return tonumber(amt) end\n" +
                    "end\n" +
                    "redis.call('DEL', KEYS[1])\n" +
                    "return tonumber(amt)",
            Long.class);

    /**
     * Undoes a capped debit whose DEBIT audit row could not be written, so no
     * send is ever counted (and debited) without the row every release path
     * keys on. KEYS[1] = balance key, KEYS[2] = aggregate hash, KEYS[3] =
     * balance version; ARGV[1] = the send's {@code txn:} field, ARGV[2] =
     * {@code total} field, ARGV[3] = the {@code txn:} prefix, ARGV[4] = the
     * debited cents. Always credits the debited amount back (the debit just
     * happened, whatever became of the window since — e.g. an operator
     * cleared it), then releases the send if it is still counted and drops the
     * window if it is left empty. Returns the cents credited back.
     */
    private static final RedisScript<Long> UNDO_CAPPED_DEBIT_SCRIPT = new DefaultRedisScript<>(
            "redis.call('INCRBY', KEYS[1], ARGV[4])\n" +
                    "redis.call('INCR', KEYS[3])\n" +
                    "local amt = redis.call('HGET', KEYS[2], ARGV[1])\n" +
                    "if not amt then return tonumber(ARGV[4]) end\n" +
                    "if tonumber(amt) ~= 0 then redis.call('HINCRBY', KEYS[2], ARGV[2], '-' .. amt) end\n" +
                    "redis.call('HDEL', KEYS[2], ARGV[1])\n" +
                    "local prefixLen = string.len(ARGV[3])\n" +
                    "for _, k in ipairs(redis.call('HKEYS', KEYS[2])) do\n" +
                    "  if string.sub(k, 1, prefixLen) == ARGV[3] then return tonumber(ARGV[4]) end\n" +
                    "end\n" +
                    "redis.call('DEL', KEYS[2])\n" +
                    "return tonumber(ARGV[4])",
            Long.class);

    /** {@link #CAPPED_DEBIT_SCRIPT} result code: insufficient funds. */
    private static final long CAPPED_DEBIT_INSUFFICIENT_FUNDS = -1L;
    /** {@link #CAPPED_DEBIT_SCRIPT} result code: aggregate cap would be exceeded. */
    private static final long CAPPED_DEBIT_CAP_EXCEEDED = -2L;

    /**
     * Atomic capped debit. KEYS[1] = balance key, KEYS[2] = aggregate hash,
     * KEYS[3] = balance version (bumped when applied).
     * ARGV: [1] amount cents, [2] cap cents, [3] the send's {@code txn:<id>}
     * field, [4] {@code total} field, [5] {@code opened_at} field, [6] now
     * (epoch seconds).
     *
     * <p>Returns {@code {-1, balance}} on insufficient funds,
     * {@code {-2, aggregate}} when the cap would be exceeded, and
     * {@code {0, balanceBefore, aggregateAfter, openedWindow}} when applied
     * ({@code openedWindow} is 1 when this send created the window). Amounts are
     * formatted with {@code %d} so large values never switch to exponent
     * notation.
     */
    private static final RedisScript<List> CAPPED_DEBIT_SCRIPT = new DefaultRedisScript<>(
            "local bal = tonumber(redis.call('GET', KEYS[1]) or '0')\n" +
                    "local amt = tonumber(ARGV[1])\n" +
                    "if bal < amt then return {-1, bal} end\n" +
                    "local total = tonumber(redis.call('HGET', KEYS[2], ARGV[4]) or '0')\n" +
                    "if total + amt > tonumber(ARGV[2]) then return {-2, total} end\n" +
                    "redis.call('SET', KEYS[1], string.format('%d', bal - amt))\n" +
                    "redis.call('INCR', KEYS[3])\n" +
                    "redis.call('HINCRBY', KEYS[2], ARGV[4], ARGV[1])\n" +
                    "local opened = redis.call('HSETNX', KEYS[2], ARGV[5], ARGV[6])\n" +
                    "redis.call('HSET', KEYS[2], ARGV[3], ARGV[1])\n" +
                    "return {0, bal, total + amt, opened}",
            List.class);

    /**
     * Releases the given counted sends from an aggregate window and closes the
     * window if no counted send remains. KEYS[1] = aggregate hash. ARGV:
     * [1] {@code total} field, [2] the {@code txn:} prefix, [3..] the send
     * fields to release.
     *
     * <p>Returns 1 if the window was closed (deleted), 0 if sends that were not
     * released keep it open — those were counted after the confirmation or
     * belong to unresolved sends, and stay counted (fail-closed). A window
     * left open keeps its original {@code opened_at}, so a window held open
     * indefinitely (e.g. by an unresolved send) keeps aging and surfaces in
     * the overdue-window alert instead of looking fresh after every cycle.
     */
    private static final RedisScript<Long> AGGREGATE_CLOSE_SCRIPT = new DefaultRedisScript<>(
            "for i = 3, #ARGV do\n" +
                    "  local amt = redis.call('HGET', KEYS[1], ARGV[i])\n" +
                    "  if amt then\n" +
                    "    if tonumber(amt) ~= 0 then redis.call('HINCRBY', KEYS[1], ARGV[1], '-' .. amt) end\n" +
                    "    redis.call('HDEL', KEYS[1], ARGV[i])\n" +
                    "  end\n" +
                    "end\n" +
                    "local prefixLen = string.len(ARGV[2])\n" +
                    "for _, k in ipairs(redis.call('HKEYS', KEYS[1])) do\n" +
                    "  if string.sub(k, 1, prefixLen) == ARGV[2] then\n" +
                    "    return 0\n" +
                    "  end\n" +
                    "end\n" +
                    "redis.call('DEL', KEYS[1])\n" +
                    "return 1",
            Long.class);

    /**
     * Compare-and-set for {@link #reconcileIfUnchanged}. KEYS[1] = balance key,
     * KEYS[2] = balance version; ARGV[1] = the version seen in the snapshot
     * (absent = 0), ARGV[2] = confirmed cents. Returns 1 if set, 0 if any
     * balance write happened since the snapshot.
     */
    private static final RedisScript<Long> RECONCILE_IF_UNCHANGED_SCRIPT = new DefaultRedisScript<>(
            "local v = tonumber(redis.call('GET', KEYS[2]) or '0')\n" +
                    "if v ~= tonumber(ARGV[1]) then return 0 end\n" +
                    "redis.call('SET', KEYS[1], ARGV[2])\n" +
                    "redis.call('INCR', KEYS[2])\n" +
                    "return 1",
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
    private final BridgeAggregateCapPolicy aggregateCapPolicy;

    public ShadowLedger(StringRedisTemplate redis, JdbcTemplate jdbc,
                        BridgeAggregateCapPolicy aggregateCapPolicy) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.aggregateCapPolicy = aggregateCapPolicy;
    }

    /**
     * Whether a bridge-mode aggregate send cap is configured (#68). Callers use
     * this to decide whether to route a send through {@link #applyDebitCapped}.
     */
    public boolean isAggregateCapEnabled() {
        return aggregateCapPolicy.isEnabled();
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
                    ops.opsForValue().increment(VERSION_KEY_PREFIX + accountId);
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
     * (balance decrement, aggregate increment plus the per-send field) run in
     * one Lua script, which Redis executes atomically. Either everything
     * applies or nothing does: a send can never be debited without being
     * counted, or counted without being debited. A script is used instead of
     * the WATCH/MULTI/EXEC retry loop of {@link #applyDebit} because it cannot
     * run out of retries under contention — losing a race yields a clean
     * rejection value, never an exception after the saga already exists.
     * Concurrent {@link #applyDebit} calls still see the script's write to the
     * balance key through their WATCH. Both keys must live on the same node;
     * this ledger targets a single Redis primary (not Redis Cluster).
     *
     * <p>The caller decides <em>when</em> a send must be counted (in bridge
     * mode, or while a not-yet-reconciled window is still open); whenever this
     * method is invoked the send is counted. Rejections are returned as values,
     * not exceptions, because the caller must map them to a clean ISO 20022
     * {@code AM04} response rather than an error path.
     *
     * <p>The ceiling comes from {@link BridgeAggregateCapPolicy}; the ledger
     * does not accept a caller-supplied cap, so no call site can enforce a
     * different number than the one the operator configured.
     *
     * @return the outcome; on {@link CappedDebitResult#APPLIED} a DEBIT audit
     *         row has been written like {@link #applyDebit}
     * @throws IllegalStateException if the cap is disabled (check
     *         {@link #isAggregateCapEnabled()} first)
     */
    public CappedDebitResult applyDebitCapped(String accountId, BigDecimal amount,
                                              String transactionId) {
        return applyDebitCappedDetailed(accountId, amount, transactionId).result();
    }

    /**
     * Outcome of {@link #applyDebitCappedDetailed}.
     *
     * @param openedWindow {@code true} when this send created the account's
     *                     aggregate window (there was none before it) — the
     *                     caller must then record that a reconciliation is owed
     * @param pctOfCap     the window's counted total after this send, as a
     *                     percentage of the configured cap (0 on rejection) —
     *                     for the {@code bridge_mode.aggregate.pct_of_cap}
     *                     distribution, so operators see the aggregate trending
     *                     before sends start being rejected
     */
    public record CappedDebit(CappedDebitResult result, boolean openedWindow, double pctOfCap) { }

    /**
     * {@link #applyDebitCapped}, also reporting whether the send opened a new
     * aggregate window. A window can be opened outside bridge mode: a send that
     * passed the "window open" check just as reconciliation closed the window
     * re-creates it holding only itself, and that window is owed a
     * reconciliation like any other (#68).
     */
    public CappedDebit applyDebitCappedDetailed(String accountId, BigDecimal amount,
                                                String transactionId) {
        // Throws IllegalStateException when the cap is disabled.
        long aggregateCapCents = aggregateCapPolicy.getMaxAggregateSendCents();
        long amountCents = dollarsToCents(amount);

        List<?> result = redis.execute(CAPPED_DEBIT_SCRIPT,
                List.of(BALANCE_KEY_PREFIX + accountId, AGGREGATE_KEY_PREFIX + accountId,
                        VERSION_KEY_PREFIX + accountId),
                String.valueOf(amountCents),
                String.valueOf(aggregateCapCents),
                AGGREGATE_TXN_FIELD_PREFIX + transactionId,
                AGGREGATE_TOTAL_FIELD,
                AGGREGATE_OPENED_AT_FIELD,
                String.valueOf(Instant.now().getEpochSecond()));
        if (result == null || result.isEmpty()) {
            throw new IllegalStateException(
                    "Shadow Ledger capped debit returned no result: account="
                            + io.openfednow.security.pii.PiiRedactor.maskAccount(accountId));
        }
        long code = ((Number) result.get(0)).longValue();
        long observed = result.size() > 1 ? ((Number) result.get(1)).longValue() : 0L;

        if (code == CAPPED_DEBIT_INSUFFICIENT_FUNDS) {
            log.info("Shadow Ledger capped debit rejected: insufficient funds account={} "
                            + "balance={} requested={}",
                    io.openfednow.security.pii.PiiRedactor.maskAccount(accountId),
                    centsToDollars(observed), amount);
            return new CappedDebit(CappedDebitResult.INSUFFICIENT_FUNDS, false, 0.0);
        }
        if (code == CAPPED_DEBIT_CAP_EXCEEDED) {
            log.warn("Shadow Ledger capped debit rejected: aggregate cap would be exceeded "
                            + "account={} aggregate={} requested={} cap={}",
                    io.openfednow.security.pii.PiiRedactor.maskAccount(accountId),
                    centsToDollars(observed), amount, centsToDollars(aggregateCapCents));
            return new CappedDebit(CappedDebitResult.AGGREGATE_CAP_EXCEEDED, false, 0.0);
        }

        long balanceBeforeCents = observed;
        long balanceAfterCents = balanceBeforeCents - amountCents;
        long aggregateAfterCents = ((Number) result.get(2)).longValue();
        boolean openedWindow = result.size() > 3 && ((Number) result.get(3)).longValue() == 1L;
        try {
            logTransaction(transactionId, transactionId, accountId, "DEBIT",
                    amount, centsToDollars(balanceBeforeCents), centsToDollars(balanceAfterCents));
        } catch (RuntimeException e) {
            // Every release path (reversal, reconciliation) keys on the DEBIT
            // row. Without it the send would stay counted — and debited —
            // forever, with only the admin override able to lift the cap.
            undoCappedDebit(accountId, transactionId, amountCents);
            throw e;
        }
        log.info("Shadow Ledger capped debit applied account={} amount={} balanceAfter={} "
                        + "aggregateAfter={}",
                io.openfednow.security.pii.PiiRedactor.maskAccount(accountId), amount,
                centsToDollars(balanceAfterCents), centsToDollars(aggregateAfterCents));
        return new CappedDebit(CappedDebitResult.APPLIED, openedWindow,
                aggregateAfterCents * 100.0 / aggregateCapCents);
    }

    /**
     * Applies a credit to an account atomically using Redis INCRBY.
     */
    public void applyCredit(String accountId, BigDecimal amount, String transactionId) {
        String key = BALANCE_KEY_PREFIX + accountId;
        long amountCents = dollarsToCents(amount);

        Long newCents = incrementBalance(accountId, amountCents);
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

        Long newCents = incrementBalance(accountId, amountCents);
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
        // crash — or a Redis error — between the balance credit and this
        // release leaves the window overcounted: the fail-closed direction for
        // a risk cap (#68). Retries will not re-attempt it (the REVERSAL row
        // already exists); the send stays counted until reconciliation
        // confirms it. The script no-ops when the send was never counted
        // (online-mode debit) or was already released (duplicate compensation,
        // closed window).
        //
        // Skipped while the cap is disabled (nothing is counted or enforced),
        // and a release error is logged rather than thrown: the reversal itself
        // has succeeded, and reporting it as failed would mislead the
        // compensation paths.
        if (aggregateCapPolicy.isEnabled()) {
            try {
                releaseFromAggregate(accountId, transactionId);
            } catch (RuntimeException e) {
                log.error("Reversal applied but releasing it from the bridge aggregate window failed "
                                + "account={} transactionId={} — the send stays counted until reconciliation",
                        io.openfednow.security.pii.PiiRedactor.maskAccount(accountId), transactionId, e);
            }
        }
    }

    /**
     * Reverts a capped debit whose audit row failed to insert. If the undo
     * itself fails the send stays counted (fail-closed) and the error says how
     * to recover it.
     */
    /* package-private for tests */ void undoCappedDebit(String accountId, String transactionId, long amountCents) {
        try {
            Long undone = redis.execute(UNDO_CAPPED_DEBIT_SCRIPT,
                    List.of(BALANCE_KEY_PREFIX + accountId, AGGREGATE_KEY_PREFIX + accountId,
                            VERSION_KEY_PREFIX + accountId),
                    AGGREGATE_TXN_FIELD_PREFIX + transactionId, AGGREGATE_TOTAL_FIELD,
                    AGGREGATE_TXN_FIELD_PREFIX, String.valueOf(amountCents));
            log.warn("Shadow Ledger capped debit undone after its audit row failed account={} "
                            + "transactionId={} amount={}",
                    io.openfednow.security.pii.PiiRedactor.maskAccount(accountId), transactionId,
                    undone == null ? null : centsToDollars(undone));
        } catch (RuntimeException undoFailure) {
            log.error("Could not undo capped debit after its audit row failed account={} transactionId={} "
                            + "— the send stays counted; clear it via DELETE "
                            + "/admin/accounts/{accountId}/bridge-aggregate once the position is established",
                    io.openfednow.security.pii.PiiRedactor.maskAccount(accountId), transactionId, undoFailure);
        }
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
                AGGREGATE_TXN_FIELD_PREFIX + transactionId, AGGREGATE_TOTAL_FIELD,
                AGGREGATE_TXN_FIELD_PREFIX);
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

        Long newCents = incrementBalance(accountId, -amountCents);
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
        Long written = redis.execute(BALANCE_SETNX_SCRIPT,
                List.of(BALANCE_KEY_PREFIX + accountId, VERSION_KEY_PREFIX + accountId),
                String.valueOf(cents));
        boolean seeded = written != null && written == 1L;
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
     * Distinct from {@link #reconcileIfUnchanged} in that this is a
     * one-shot operator action and does not log a RECONCILIATION audit row —
     * those rows belong to the scheduled reconciliation cycle.
     */
    public void seedBalance(String accountId, BigDecimal balance) {
        long cents = dollarsToCents(balance);
        redis.execute(BALANCE_SET_SCRIPT,
                List.of(BALANCE_KEY_PREFIX + accountId, VERSION_KEY_PREFIX + accountId),
                String.valueOf(cents));
        log.info("Shadow Ledger force-seeded account={} balance={}", accountId, balance);
    }

    /** A balance read together with its version (see {@link #VERSION_KEY_PREFIX}). */
    public record BalanceSnapshot(BigDecimal balance, long version) { }

    /**
     * Reads an account's balance and its version in one atomic {@code MGET},
     * for {@link #reconcileIfUnchanged}. An unseeded account reads as zero,
     * version 0.
     */
    public BalanceSnapshot snapshotBalance(String accountId) {
        List<String> values = redis.opsForValue().multiGet(
                List.of(BALANCE_KEY_PREFIX + accountId, VERSION_KEY_PREFIX + accountId));
        String rawBalance = values == null ? null : values.get(0);
        String rawVersion = values == null ? null : values.get(1);
        return new BalanceSnapshot(
                rawBalance == null ? BigDecimal.ZERO : centsToDollars(Long.parseLong(rawBalance)),
                rawVersion == null ? 0L : Long.parseLong(rawVersion));
    }

    /**
     * Sets the Shadow Ledger balance to the core's confirmed balance — but only
     * if no balance write happened since {@code snapshot} was taken (#142/#144).
     *
     * <p>Reconciliation snapshots the shadow balance, reads the core, then
     * corrects the shadow balance. Payments keep flowing meanwhile: a write in
     * between would be silently erased by a plain overwrite, making spent money
     * spendable again (or a credit vanish). This method checks the account's
     * balance version and writes in one atomic Redis script: the correction
     * applies only if the version is unchanged — comparing versions rather than
     * values also catches an equal-and-opposite pair of writes that leaves the
     * value where it was. Otherwise nothing is written and the caller retries.
     *
     * <p>When applied and the balances differed, a {@code RECONCILIATION} audit
     * row records the correction, as before.
     *
     * @return {@code true} if the balance now equals {@code confirmedBalance};
     *         {@code false} if a concurrent write happened since the snapshot
     *         (nothing written)
     */
    public boolean reconcileIfUnchanged(String accountId, BalanceSnapshot snapshot,
                                        BigDecimal confirmedBalance) {
        long expectedCents = dollarsToCents(snapshot.balance());
        long confirmedCents = dollarsToCents(confirmedBalance);
        Long applied = redis.execute(RECONCILE_IF_UNCHANGED_SCRIPT,
                List.of(BALANCE_KEY_PREFIX + accountId, VERSION_KEY_PREFIX + accountId),
                String.valueOf(snapshot.version()), String.valueOf(confirmedCents));
        if (applied == null || applied != 1L) {
            log.info("Shadow Ledger reconciliation deferred — balance changed since it was read "
                            + "account={}",
                    io.openfednow.security.pii.PiiRedactor.maskAccount(accountId));
            return false;
        }
        if (expectedCents != confirmedCents) {
            log.warn("Shadow Ledger discrepancy corrected account={} shadow={} confirmed={}",
                    io.openfednow.security.pii.PiiRedactor.maskAccount(accountId),
                    centsToDollars(expectedCents), confirmedBalance);
            BigDecimal diff = confirmedBalance.subtract(centsToDollars(expectedCents)).abs();
            // transaction_id / end_to_end_id are VARCHAR(35). The previous
            // "RECONCILE-<account>-<millis>" overflowed for any account id longer
            // than 11 characters, so the audit row failed to insert and the
            // correction went unrecorded. "RCN" + 32 hex digits is exactly 35;
            // the account is in its own column.
            String syntheticTxnId = "RCN" + java.util.UUID.randomUUID().toString().replace("-", "");
            logTransaction(syntheticTxnId, syntheticTxnId, accountId, "RECONCILIATION",
                    diff, centsToDollars(expectedCents), confirmedBalance);
        }
        log.info("Shadow Ledger reconciled account={} confirmedBalance={}",
                io.openfednow.security.pii.PiiRedactor.maskAccount(accountId), confirmedBalance);
        return true;
    }

    /**
     * Unconditionally deletes an account's aggregate send window, discarding
     * every counted send — an operator-level primitive, issue #68.
     *
     * <p>Reconciliation does <em>not</em> use this: it calls
     * {@link #closeConfirmedAggregate}, which only releases sends the core has
     * confirmed and so can never erase a send counted concurrently. Use this
     * only when an operator has independently established the account's
     * position.
     *
     * @return {@code true} if a window existed and was deleted
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
     * Releases every counted send the core has confirmed and closes the
     * account's aggregate window if nothing else remains counted — issue #68.
     * This is what reconciliation calls; unlike {@link #clearAggregate} it
     * never discards a send it has not verified.
     *
     * <p>A counted send is released here only when both hold:
     * <ul>
     *   <li>its DEBIT row is {@code core_confirmed} — reconciliation confirmed
     *       it against the core; and</li>
     *   <li>its payment saga, if one exists, is final ({@code COMPLETED} or
     *       {@code FAILED}). Sends whose rail outcome is still unresolved
     *       ({@code SUBMITTING}, {@code OUTCOME_UNKNOWN}, or any other
     *       in-flight state) stay counted until they resolve.</li>
     * </ul>
     *
     * <p>Sends counted after this method reads the window, or whose DEBIT row
     * is not written or confirmed yet, are not touched and keep the window
     * open. The release itself is one atomic script, so a send counted
     * concurrently can never be erased. The caller is responsible for only
     * invoking this once the core is online.
     *
     * @return {@code true} if the account has no open window afterwards
     */
    public boolean closeConfirmedAggregate(String accountId) {
        String aggregateKey = AGGREGATE_KEY_PREFIX + accountId;
        Set<Object> fields = redis.opsForHash().keys(aggregateKey);
        if (fields == null || fields.isEmpty()) {
            return true;
        }
        List<String> countedTxnIds = fields.stream()
                .map(String.class::cast)
                .filter(f -> f.startsWith(AGGREGATE_TXN_FIELD_PREFIX))
                .map(f -> f.substring(AGGREGATE_TXN_FIELD_PREFIX.length()))
                .toList();
        List<String> releasable = countedTxnIds.isEmpty()
                ? List.of()
                : confirmedAndResolved(accountId, countedTxnIds);

        List<String> args = new ArrayList<>();
        args.add(AGGREGATE_TOTAL_FIELD);
        args.add(AGGREGATE_TXN_FIELD_PREFIX);
        releasable.forEach(id -> args.add(AGGREGATE_TXN_FIELD_PREFIX + id));

        Long closed = redis.execute(AGGREGATE_CLOSE_SCRIPT, List.of(aggregateKey), args.toArray());
        boolean windowClosed = closed != null && closed == 1L;
        if (windowClosed) {
            log.info("Bridge aggregate window closed after core confirmation account={} released={}",
                    io.openfednow.security.pii.PiiRedactor.maskAccount(accountId), releasable.size());
        } else {
            log.info("Bridge aggregate window stays open account={} released={} stillCounted={}",
                    io.openfednow.security.pii.PiiRedactor.maskAccount(accountId), releasable.size(),
                    countedTxnIds.size() - releasable.size());
        }
        return windowClosed;
    }

    /**
     * Of the given counted transaction IDs, those whose DEBIT row is
     * core-confirmed and whose saga (if any) is final.
     */
    private List<String> confirmedAndResolved(String accountId, List<String> transactionIds) {
        // Bounded bind-parameter lists: a pathological window with thousands of
        // counted sends must not make every reconciliation cycle fail.
        if (transactionIds.size() > RELEASE_QUERY_BATCH) {
            List<String> all = new ArrayList<>();
            for (int i = 0; i < transactionIds.size(); i += RELEASE_QUERY_BATCH) {
                all.addAll(confirmedAndResolved(accountId,
                        transactionIds.subList(i, Math.min(i + RELEASE_QUERY_BATCH, transactionIds.size()))));
            }
            return all;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(transactionIds.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(accountId);
        params.addAll(transactionIds);
        return jdbc.queryForList(
                "SELECT DISTINCT l.transaction_id FROM shadow_ledger_transaction_log l "
                        + "WHERE l.account_id = ? AND l.transaction_type = 'DEBIT' "
                        + "AND l.core_confirmed = TRUE "
                        + "AND l.transaction_id IN (" + placeholders + ") "
                        + "AND NOT EXISTS (SELECT 1 FROM saga_state s "
                        + "WHERE s.transaction_id = l.transaction_id "
                        + "AND s.state NOT IN ('COMPLETED', 'FAILED'))",
                String.class, params.toArray());
    }

    /**
     * Whether the account has an open aggregate send window. A window stays
     * open after the core returns until reconciliation confirms the balance,
     * and sends during that interval stay subject to the cap (#68).
     */
    public boolean hasOpenAggregate(String accountId) {
        return Boolean.TRUE.equals(redis.hasKey(AGGREGATE_KEY_PREFIX + accountId));
    }

    /**
     * Non-atomic pre-check: would counting {@code amount} push the account's
     * aggregate window over the configured cap? Lets the dispatch path reject
     * the common case before any saga or debit exists. It is advisory only —
     * {@link #applyDebitCapped} re-checks atomically and is the enforcement
     * point. Uses the same cents rounding as the ledger. Returns {@code false}
     * when the cap is disabled.
     */
    public boolean wouldExceedAggregateCap(String accountId, BigDecimal amount) {
        if (!aggregateCapPolicy.isEnabled()) {
            return false;
        }
        Object rawTotal = redis.opsForHash()
                .get(AGGREGATE_KEY_PREFIX + accountId, AGGREGATE_TOTAL_FIELD);
        long countedCents = rawTotal != null ? Long.parseLong((String) rawTotal) : 0L;
        return countedCents + dollarsToCents(amount) > aggregateCapPolicy.getMaxAggregateSendCents();
    }

    /**
     * Account IDs with an open aggregate window, found by an incremental
     * {@code SCAN} (never {@code KEYS}). Reconciliation uses this to close
     * windows whose accounts no longer have unconfirmed ledger rows — for
     * example when a previous cycle committed the confirmation but failed to
     * clear the window — so no window is left open indefinitely.
     */
    public List<String> openAggregateAccountIds() {
        // SCAN may return a key more than once; de-duplicate, keep scan order.
        Set<String> accountIds = new LinkedHashSet<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(AGGREGATE_KEY_PREFIX + "*")
                .count(500)
                .build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                accountIds.add(cursor.next().substring(AGGREGATE_KEY_PREFIX.length()));
            }
        }
        return new ArrayList<>(accountIds);
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

    /** INCRBY an account's balance and bump its version atomically. */
    private Long incrementBalance(String accountId, long deltaCents) {
        return redis.execute(BALANCE_INCRBY_SCRIPT,
                List.of(BALANCE_KEY_PREFIX + accountId, VERSION_KEY_PREFIX + accountId),
                String.valueOf(deltaCents));
    }

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
