package io.openfednow.shadowledger;

import io.openfednow.acl.core.CoreBankingAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

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

    public ReconciliationService(ShadowLedger shadowLedger,
                                 JdbcTemplate jdbc,
                                 CoreBankingAdapter coreBankingAdapter) {
        this.shadowLedger = shadowLedger;
        this.jdbc = jdbc;
        this.coreBankingAdapter = coreBankingAdapter;
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
        log.info("Reconciliation cycle starting");

        // Open the run audit record
        Long runId = jdbc.queryForObject(
                "INSERT INTO reconciliation_run (triggered_by) VALUES ('SCHEDULED') RETURNING id",
                Long.class);

        int transactionsReplayed = 0;
        int discrepanciesDetected = 0;

        try {
            // Find distinct accounts with unconfirmed entries
            List<String> accounts = jdbc.queryForList(
                    "SELECT DISTINCT account_id FROM shadow_ledger_transaction_log " +
                    "WHERE core_confirmed = FALSE ORDER BY account_id",
                    String.class);

            log.info("Reconciliation found {} accounts with pending entries", accounts.size());

            for (String accountId : accounts) {
                try {
                    BigDecimal coreBalance = coreBankingAdapter.getAvailableBalance(accountId);
                    BigDecimal shadowBalance = shadowLedger.getAvailableBalance(accountId);

                    if (coreBalance.compareTo(shadowBalance) != 0) {
                        discrepanciesDetected++;
                        log.warn("Balance discrepancy account={} shadow={} core={}",
                                accountId, shadowBalance, coreBalance);
                        shadowLedger.reconcile(accountId, coreBalance);
                    }

                    // Mark this account's unconfirmed entries as confirmed
                    int confirmed = jdbc.update(
                            "UPDATE shadow_ledger_transaction_log " +
                            "SET core_confirmed = TRUE " +
                            "WHERE account_id = ? AND core_confirmed = FALSE",
                            accountId);
                    transactionsReplayed += confirmed;

                } catch (Exception e) {
                    log.error("Reconciliation failed for account={}", accountId, e);
                    discrepanciesDetected++;
                }
            }

        } catch (Exception e) {
            log.error("Reconciliation cycle failed", e);
            closeRunRecord(runId, transactionsReplayed, discrepanciesDetected, false,
                    "Reconciliation aborted: " + e.getMessage());
            return new ReconciliationReport(transactionsReplayed, discrepanciesDetected, false,
                    "Aborted: " + e.getMessage());
        }

        boolean successful = discrepanciesDetected == 0;
        String summary = successful
                ? String.format("Clean reconciliation: %d entries confirmed across all accounts",
                        transactionsReplayed)
                : String.format("%d discrepancies detected across accounts; manual review required",
                        discrepanciesDetected);

        closeRunRecord(runId, transactionsReplayed, discrepanciesDetected, successful, summary);
        log.info("Reconciliation cycle complete replayed={} discrepancies={} success={}",
                transactionsReplayed, discrepanciesDetected, successful);

        return new ReconciliationReport(transactionsReplayed, discrepanciesDetected, successful, summary);
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

    // ── Internal helpers ───────────────────────────────────────────────────────

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
}
