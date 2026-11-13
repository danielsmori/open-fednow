package io.openfednow.shadowledger;

import io.openfednow.acl.core.CoreBankingAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Layer 4 — Reconciliation Service
 *
 * <p>Ensures the core banking system remains the authoritative ledger by
 * reconciling the Shadow Ledger against it whenever the core comes back online.
 *
 * <p>Reconciliation process:
 * <ol>
 *   <li>Open a {@code reconciliation_run} audit record</li>
 *   <li>Find all accounts with unconfirmed Shadow Ledger entries</li>
 *   <li>Fetch the authoritative balance for each account from the core</li>
 *   <li>Compare and update the Shadow Ledger to match the confirmed state</li>
 *   <li>Mark transaction log entries as {@code core_confirmed = TRUE}</li>
 *   <li>Close the {@code reconciliation_run} record with a summary</li>
 * </ol>
 */
@Component
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ShadowLedger shadowLedger;
    private final JdbcTemplate jdbc;
    private final CoreBankingAdapter coreBankingAdapter;
    private final int batchSize;
    private final TransactionTemplate perAccountTx;
    /**
     * Guards against concurrent reconcile() invocations within the same JVM. The
     * realistic concern is "operator triggers /admin/reconcile while the scheduled
     * trigger is already running" — a non-fair lock with tryLock semantics surfaces
     * the collision immediately rather than queuing the second call behind a
     * potentially long-running cycle. Across instances, {@link #reconciliationLock}
     * provides the same guarantee when it is the PostgreSQL advisory lock; with
     * {@link ReconciliationLock#LOCAL_ONLY} (non-PostgreSQL databases) there is
     * no cross-instance guard.
     */
    private final ReentrantLock reconcileLock = new ReentrantLock();
    /**
     * Cross-instance guard (#68): at most one reconciliation cycle runs anywhere
     * at a time. Taken after the in-JVM lock and held for the whole cycle.
     */
    private final ReconciliationLock reconciliationLock;

    /**
     * How often a running cycle re-confirms it still holds the cross-instance
     * lock (between accounts, and always before the first account and before
     * the window sweep). This narrows, but does not fence, the time two cycles
     * could overlap if the lock is lost mid-cycle: the worst case is this
     * interval plus the slowest single account (one core call). It also keeps
     * the lock's database session from idling out.
     */
    static final long LOCK_CHECK_INTERVAL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(15);

    public ReconciliationService(ShadowLedger shadowLedger,
                                 JdbcTemplate jdbc,
                                 CoreBankingAdapter coreBankingAdapter,
                                 PlatformTransactionManager txManager,
                                 @Value("${openfednow.reconciliation.batch-size:500}") int batchSize,
                                 ReconciliationLock reconciliationLock) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "openfednow.reconciliation.batch-size must be positive");
        }
        this.shadowLedger = shadowLedger;
        this.jdbc = jdbc;
        this.coreBankingAdapter = coreBankingAdapter;
        this.batchSize = batchSize;
        this.reconciliationLock = reconciliationLock;
        // Per-account work runs inside its own transaction so a discrepancy correction
        // and the "mark unconfirmed rows confirmed" UPDATE commit together. If either
        // fails, neither persists — the account is reprocessed on the next cycle.
        // TransactionTemplate is used (rather than @Transactional) to avoid Spring's
        // self-invocation pitfall: the reconcile() loop calls a private helper, and
        // @Transactional only proxies cross-bean calls.
        this.perAccountTx = new TransactionTemplate(txManager);
    }

    /** Visible to tests. */
    int getBatchSize() {
        return batchSize;
    }

    /**
     * Initiates a full reconciliation cycle after the core system returns online.
     *
     * <p>Discovers all accounts with unconfirmed Shadow Ledger entries, retrieves
     * their authoritative balances from the core, and synchronizes the Shadow Ledger.
     *
     * @return a {@link ReconciliationReport} summarizing the results
     */
    public ReconciliationReport reconcile() {
        return attemptReconciliation().report();
    }

    /**
     * Runs a reconciliation cycle if no other cycle is running in this JVM or on
     * any other instance, and reports how it ended in more detail than
     * {@link #reconcile()} — whether it ran at all, and whether every account
     * was actually reconciled. {@link AutomaticReconciliationTrigger} uses this
     * to decide whether the pending marker can be cleared.
     */
    public ReconciliationAttempt attemptReconciliation() {
        // Same-JVM concurrency guard. A second concurrent call returns immediately
        // with a synthetic "skipped" report so a misconfigured cron or simultaneous
        // manual + scheduled trigger does not create overlapping reconciliation_run
        // rows and racing UPDATEs against shadow_ledger_transaction_log.
        if (!reconcileLock.tryLock()) {
            log.warn("Reconciliation cycle skipped — another invocation is already running");
            return ReconciliationAttempt.skipped(Outcome.SKIPPED_IN_PROGRESS,
                    "Skipped: another reconciliation cycle was already in progress");
        }
        try {
            // Cross-instance guard: the same refusal, across every instance.
            java.util.Optional<ReconciliationLock.Lease> lease;
            try {
                lease = reconciliationLock.tryAcquire();
            } catch (RuntimeException e) {
                log.error("Reconciliation cycle not started — cross-instance lock unavailable", e);
                return new ReconciliationAttempt(Outcome.ABORTED,
                        new ReconciliationReport(0, 0, false,
                                "Aborted: reconciliation lock unavailable: " + e.getMessage()),
                        0, 0, 0);
            }
            if (lease.isEmpty()) {
                log.info("Reconciliation cycle skipped — another instance is running one");
                return ReconciliationAttempt.skipped(Outcome.SKIPPED_ELSEWHERE,
                        "Skipped: another instance holds the reconciliation lock");
            }
            try (ReconciliationLock.Lease held = lease.get()) {
                return reconcileLocked(new LockWatch(held));
            }
        } finally {
            reconcileLock.unlock();
        }
    }

    /**
     * Re-confirms the cross-instance lock at most every
     * {@link #LOCK_CHECK_INTERVAL_NANOS}, and on demand before phases that
     * must not run unguarded. Throws if the lock is gone, which aborts the cycle.
     */
    private static final class LockWatch {
        private final ReconciliationLock.Lease lease;
        /** Starts "due", so the first account is checked before it is touched. */
        private long lastCheck = System.nanoTime() - LOCK_CHECK_INTERVAL_NANOS;

        LockWatch(ReconciliationLock.Lease lease) {
            this.lease = lease;
        }

        void verify(boolean force) {
            long now = System.nanoTime();
            if (!force && now - lastCheck < LOCK_CHECK_INTERVAL_NANOS) {
                return;
            }
            lastCheck = now;
            if (!lease.stillHeld()) {
                throw new IllegalStateException(
                        "Cross-instance reconciliation lock lost mid-cycle — stopping so another "
                                + "instance's cycle cannot overlap this one");
            }
        }
    }

    private ReconciliationAttempt reconcileLocked(LockWatch lockWatch) {
        log.info("Reconciliation cycle starting");

        // Correlate this run with the admin_audit_log entry that triggered it (if any).
        // Presence of a request_id also disambiguates SCHEDULED vs MANUAL: a scheduled
        // job runs outside any HTTP request and has no MDC context; an operator hitting
        // /admin/reconcile has a per-request UUID from CorrelationFilter.
        final String requestId = org.slf4j.MDC.get(io.openfednow.gateway.CorrelationFilter.MDC_REQUEST_ID);
        final String triggeredBy = requestId != null ? "MANUAL" : "SCHEDULED";

        // Open the run audit record
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO reconciliation_run (triggered_by, request_id) VALUES (?, ?)",
                    new String[]{"id"});
            ps.setString(1, triggeredBy);
            ps.setString(2, requestId);
            return ps;
        }, keyHolder);
        Long runId = keyHolder.getKey().longValue();

        int transactionsReplayed = 0;
        int discrepanciesDetected = 0;
        // Not part of the public report: they tell the automatic trigger whether
        // every account was actually reconciled (#68).
        int failedAccounts = 0;
        int deferredAccounts = 0;
        int openWindowsRemaining = 0;

        try {
            // Process accounts in keyset-paginated batches so a large institution's
            // tens-of-thousands of pending accounts never load into a single JVM list.
            // Keyset (account_id > lastSeen) is robust against per-account failures:
            // a failed account stays unconfirmed but is skipped on the next iteration
            // because we always advance lastSeen past the batch we just processed,
            // so the loop terminates after one pass even if every account fails.
            String lastSeen = null;
            int totalAccounts = 0;

            while (true) {
                List<String> batch = fetchNextAccountBatch(lastSeen);
                if (batch.isEmpty()) {
                    break;
                }
                totalAccounts += batch.size();

                for (String accountId : batch) {
                    lockWatch.verify(false);
                    try {
                        AccountOutcome outcome = reconcileOneAccount(accountId);
                        transactionsReplayed += outcome.confirmed();
                        discrepanciesDetected += outcome.discrepancy();
                        if (outcome.deferred()) deferredAccounts++;
                        if (outcome.windowReleaseFailed()) failedAccounts++;
                    } catch (Exception e) {
                        log.error("Reconciliation failed for account={}", accountId, e);
                        discrepanciesDetected++;
                        failedAccounts++;
                    }
                }

                // Advance the cursor past this batch even if some accounts failed —
                // we still want to make progress on the rest of the work.
                lastSeen = batch.get(batch.size() - 1);
            }

            log.info("Reconciliation processed {} account(s) across {}-row batches",
                    totalAccounts, batchSize);

            // Bridge-mode aggregate windows (#68) are normally drained above, as
            // part of reconciling an account with unconfirmed rows. A window can
            // still hold sends that have become releasable since — a previous
            // cycle confirmed them but failed to release them, or a send whose
            // rail outcome was unresolved has since completed — and such an
            // account may never appear in the batches again. Sweep the open
            // windows and release what the core has confirmed. The sweep only
            // releases sends; it never touches balances. Only with the core
            // online: an offline core must not lift the cap. Skipped entirely
            // when the cap is disabled — the SCAN walks the whole keyspace, and
            // an unenforced window has nothing to protect.
            //
            // A window still open after the sweep holds sends that are not
            // releasable yet (rail outcome unresolved, or counted after the
            // confirmation). It is reported so the cycle does not count as
            // settled: the owed reconciliation stays pending until it closes.
            lockWatch.verify(true);
            List<String> openWindows = shadowLedger.isAggregateCapEnabled()
                    ? shadowLedger.openAggregateAccountIds()
                    : List.of();
            if (!openWindows.isEmpty()) {
                if (coreBankingAdapter.isCoreSystemAvailable()) {
                    for (String accountId : openWindows) {
                        lockWatch.verify(false);
                        try {
                            if (!shadowLedger.closeConfirmedAggregate(accountId)) {
                                openWindowsRemaining++;
                            }
                        } catch (Exception e) {
                            log.error("Failed to release confirmed sends from bridge aggregate window "
                                            + "account={} — cap stays in force, next cycle retries",
                                    io.openfednow.security.pii.PiiRedactor.maskAccount(accountId), e);
                            failedAccounts++;
                        }
                    }
                } else {
                    log.info("Core offline — {} bridge aggregate window(s) stay open", openWindows.size());
                    deferredAccounts += openWindows.size();
                }
            }

        } catch (Exception e) {
            log.error("Reconciliation cycle failed", e);
            closeRunRecord(runId, transactionsReplayed, discrepanciesDetected, false,
                    "Reconciliation aborted: " + e.getMessage());
            return new ReconciliationAttempt(Outcome.ABORTED,
                    new ReconciliationReport(transactionsReplayed, discrepanciesDetected, false,
                            "Aborted: " + e.getMessage()),
                    failedAccounts, deferredAccounts, openWindowsRemaining);
        }

        boolean successful = discrepanciesDetected == 0;
        String summary = successful
                ? String.format("Clean reconciliation: %d entries confirmed across all accounts",
                        transactionsReplayed)
                : String.format("%d discrepancies detected across accounts; manual review required",
                        discrepanciesDetected);

        closeRunRecord(runId, transactionsReplayed, discrepanciesDetected, successful, summary);
        log.info("Reconciliation cycle complete replayed={} discrepancies={} success={} "
                        + "failedAccounts={} deferredAccounts={} openWindowsRemaining={}",
                transactionsReplayed, discrepanciesDetected, successful, failedAccounts, deferredAccounts,
                openWindowsRemaining);

        return new ReconciliationAttempt(Outcome.COMPLETED,
                new ReconciliationReport(transactionsReplayed, discrepanciesDetected, successful, summary),
                failedAccounts, deferredAccounts, openWindowsRemaining);
    }

    /**
     * Replays a specific list of queued transactions, marking them as confirmed.
     *
     * <p>Used during targeted reconciliation of a maintenance window's backlog.
     * The Shadow Ledger is already up-to-date from the bridge-mode processing;
     * this method marks those entries as core-confirmed once the core has
     * acknowledged them.
     *
     * @param transactionIds ordered list of transaction IDs to confirm
     */
    public void replayTransactions(List<String> transactionIds) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            return;
        }

        log.info("Replaying {} transactions against core", transactionIds.size());

        for (String transactionId : transactionIds) {
            try {
                int updated = jdbc.update(
                        "UPDATE shadow_ledger_transaction_log " +
                        "SET core_confirmed = TRUE " +
                        "WHERE transaction_id = ? AND core_confirmed = FALSE",
                        transactionId);

                if (updated > 0) {
                    log.debug("Transaction confirmed transactionId={}", transactionId);
                } else {
                    log.debug("Transaction already confirmed or not found transactionId={}", transactionId);
                }
            } catch (Exception e) {
                log.error("Failed to confirm transaction transactionId={}", transactionId, e);
            }
        }
    }

    /**
     * Returns the most recent reconciliation runs in newest-first order.
     *
     * <p>Backed by {@code idx_recon_started_at}. Used by the admin audit endpoint
     * to surface operational history without exposing direct DB access.
     *
     * @param limit  maximum number of runs to return; callers should clamp this
     *               at the controller boundary (typical default 50, ceiling 200)
     * @param offset number of rows to skip from the most-recent end; used for
     *               page-by-page navigation
     */
    public List<ReconciliationRunSummary> listRecentRuns(int limit, int offset) {
        return jdbc.query(
                """
                SELECT id, started_at, completed_at, transactions_replayed,
                       discrepancies_detected, successful, summary, triggered_by,
                       request_id
                FROM reconciliation_run
                ORDER BY started_at DESC, id DESC
                LIMIT ? OFFSET ?
                """,
                this::mapRunSummary,
                limit, offset);
    }

    /**
     * Looks up a single reconciliation run by its primary key.
     *
     * @return the run summary, or {@link Optional#empty()} if no row exists
     */
    public Optional<ReconciliationRunSummary> findRunById(long runId) {
        return jdbc.query(
                """
                SELECT id, started_at, completed_at, transactions_replayed,
                       discrepancies_detected, successful, summary, triggered_by,
                       request_id
                FROM reconciliation_run
                WHERE id = ?
                """,
                this::mapRunSummary,
                runId).stream().findFirst();
    }

    private ReconciliationRunSummary mapRunSummary(ResultSet rs, int rowNum) throws SQLException {
        Timestamp startedAt = rs.getTimestamp("started_at");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        Boolean successful = rs.getObject("successful") == null ? null : rs.getBoolean("successful");
        return new ReconciliationRunSummary(
                rs.getLong("id"),
                startedAt != null ? startedAt.toInstant() : null,
                completedAt != null ? completedAt.toInstant() : null,
                rs.getInt("transactions_replayed"),
                rs.getInt("discrepancies_detected"),
                successful,
                rs.getString("summary"),
                rs.getString("triggered_by"),
                rs.getString("request_id"));
    }

    /**
     * Reconciles a single account inside its own transaction.
     *
     * <p>The Postgres-side writes — the optional RECONCILIATION audit row from
     * {@link ShadowLedger#reconcile} and the "mark unconfirmed rows confirmed"
     * UPDATE — commit or roll back together. The Redis update inside
     * {@code shadowLedger.reconcile} is outside this transaction (different
     * system); if Postgres rolls back after Redis has been updated, the
     * subsequent cycle will detect the residual discrepancy and write the
     * correction afresh.
     *
     * <p><strong>Bridge-mode aggregate windows (#68).</strong> An account with
     * an open aggregate window is reconciled only while the core is online;
     * otherwise it is deferred untouched, so its bridge-mode debits are never
     * marked confirmed against an offline core (which would later let the
     * cap lift without a real confirmation). After the transaction has
     * <em>committed</em> — never inside it, so a rollback cannot lift the cap —
     * the sends the core has now confirmed are released from the window via
     * {@link ShadowLedger#closeConfirmedAggregate}. Accounts without a window
     * take exactly the pre-existing path.
     *
     * @return how the account was handled
     */
    private AccountOutcome reconcileOneAccount(String accountId) {
        boolean windowOpen = shadowLedger.hasOpenAggregate(accountId);
        if (windowOpen && !coreBankingAdapter.isCoreSystemAvailable()) {
            log.warn("Core offline — deferring reconciliation of account with open bridge "
                            + "aggregate window account={}",
                    io.openfednow.security.pii.PiiRedactor.maskAccount(accountId));
            return new AccountOutcome(0, 0, true, false);
        }
        int[] outcome = reconcileOneAccountInTx(accountId);
        boolean releaseFailed = windowOpen && !releaseConfirmedSends(accountId);
        return new AccountOutcome(outcome[0], outcome[1], false, releaseFailed);
    }

    /** Per-account result of {@link #reconcileOneAccount}. */
    private record AccountOutcome(int confirmed, int discrepancy, boolean deferred,
                                  boolean windowReleaseFailed) { }

    /**
     * Releases the sends the just-committed reconciliation confirmed, if the
     * core is still online. A failure is logged and left for the open-window
     * sweep in {@link #reconcile()}: the sends stay counted (fail-closed).
     *
     * @return {@code false} if the release did not happen (core offline, error)
     */
    private boolean releaseConfirmedSends(String accountId) {
        try {
            if (!coreBankingAdapter.isCoreSystemAvailable()) {
                log.warn("Core went offline during reconciliation — bridge aggregate window "
                                + "stays open account={}",
                        io.openfednow.security.pii.PiiRedactor.maskAccount(accountId));
                return false;
            }
            shadowLedger.closeConfirmedAggregate(accountId);
            return true;
        } catch (Exception e) {
            log.error("Failed to release confirmed sends from bridge aggregate window account={} "
                            + "— cap stays in force, next reconciliation cycle retries",
                    io.openfednow.security.pii.PiiRedactor.maskAccount(accountId), e);
            return false;
        }
    }

    private int[] reconcileOneAccountInTx(String accountId) {
        return perAccountTx.execute(status -> {
            int confirmed = 0;
            int discrepancyDetected = 0;
            BigDecimal coreBalance = coreBankingAdapter.getAvailableBalance(accountId);
            BigDecimal shadowBalance = shadowLedger.getAvailableBalance(accountId);

            if (coreBalance.compareTo(shadowBalance) != 0) {
                discrepancyDetected = 1;
                log.warn("Balance discrepancy account={} shadow={} core={}",
                        io.openfednow.security.pii.PiiRedactor.maskAccount(accountId),
                        shadowBalance, coreBalance);
                shadowLedger.reconcile(accountId, coreBalance);
            }

            confirmed = jdbc.update(
                    "UPDATE shadow_ledger_transaction_log " +
                    "SET core_confirmed = TRUE " +
                    "WHERE account_id = ? AND core_confirmed = FALSE",
                    accountId);
            return new int[] { confirmed, discrepancyDetected };
        });
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    /**
     * Fetches the next page of distinct account IDs with unconfirmed entries.
     *
     * <p>Keyset pagination on {@code account_id > lastSeen} — robust against
     * concurrent inserts and per-account processing failures. Bounded by
     * {@link #batchSize} so memory stays flat regardless of institution scale.
     *
     * @param lastSeen the highest account_id processed so far, or null on the first iteration
     * @return up to {@code batchSize} distinct account IDs in ascending order
     */
    private List<String> fetchNextAccountBatch(String lastSeen) {
        if (lastSeen == null) {
            return jdbc.queryForList(
                    "SELECT DISTINCT account_id FROM shadow_ledger_transaction_log " +
                    "WHERE core_confirmed = FALSE " +
                    "ORDER BY account_id LIMIT ?",
                    String.class, batchSize);
        }
        return jdbc.queryForList(
                "SELECT DISTINCT account_id FROM shadow_ledger_transaction_log " +
                "WHERE core_confirmed = FALSE AND account_id > ? " +
                "ORDER BY account_id LIMIT ?",
                String.class, lastSeen, batchSize);
    }

    private void closeRunRecord(Long runId, int replayed, int discrepancies,
                                boolean successful, String summary) {
        jdbc.update("""
                UPDATE reconciliation_run
                SET completed_at = ?, transactions_replayed = ?,
                    discrepancies_detected = ?, successful = ?, summary = ?
                WHERE id = ?
                """,
                OffsetDateTime.now(), replayed, discrepancies, successful, summary, runId);
    }

    /**
     * Summarizes the outcome of a reconciliation run.
     */
    public record ReconciliationReport(
        int transactionsReplayed,
        int discrepanciesDetected,
        boolean reconciliationSuccessful,
        String summary
    ) {}

    /** How an {@link #attemptReconciliation()} call ended. */
    public enum Outcome {
        /** The cycle ran to the end (accounts may still have failed or been deferred). */
        COMPLETED,
        /** The cycle could not start or stopped early. */
        ABORTED,
        /** Another cycle is already running in this JVM. */
        SKIPPED_IN_PROGRESS,
        /** Another instance holds the cross-instance lock. */
        SKIPPED_ELSEWHERE
    }

    /**
     * Detailed result of {@link #attemptReconciliation()}.
     *
     * @param failedAccounts   accounts whose reconciliation, or the release of
     *                         their confirmed bridge-mode sends, failed this cycle
     * @param deferredAccounts accounts left for a later cycle because the core
     *                         was offline
     * @param openWindowsRemaining bridge-mode aggregate windows (#68) still open
     *                         after the cycle — they hold sends not releasable
     *                         yet, so the accounts stay capped
     */
    public record ReconciliationAttempt(
        Outcome outcome,
        ReconciliationReport report,
        int failedAccounts,
        int deferredAccounts,
        int openWindowsRemaining
    ) {
        static ReconciliationAttempt skipped(Outcome outcome, String summary) {
            return new ReconciliationAttempt(outcome, new ReconciliationReport(0, 0, true, summary), 0, 0, 0);
        }

        /**
         * Whether the cycle ran to the end, reconciled every account it found,
         * and left no account capped — the only result that settles a pending
         * reconciliation.
         */
        public boolean fullyReconciled() {
            return outcome == Outcome.COMPLETED && failedAccounts == 0 && deferredAccounts == 0
                    && openWindowsRemaining == 0;
        }
    }
}
