package io.openfednow.shadowledger;

import java.time.Instant;

/**
 * Read-only projection of a row in {@code reconciliation_run}.
 *
 * <p>{@link ReconciliationService.ReconciliationReport} is the in-process return
 * value of a single {@link ReconciliationService#reconcile()} invocation;
 * this record is the durable audit projection returned by admin query endpoints
 * and dashboards. It carries the same counts plus the surrounding metadata —
 * run identifier, start / completion timestamps, success flag, and how the
 * run was triggered ({@code SCHEDULED} or {@code MANUAL}).
 *
 * @param id                     surrogate primary key from the {@code id} column
 * @param startedAt              when the run began
 * @param completedAt            when the run finished; null while in progress
 * @param transactionsReplayed   count of transactions confirmed against the core
 * @param discrepanciesDetected  count of balance discrepancies detected; any
 *                               value &gt; 0 is a critical alert
 * @param successful             true on a clean reconciliation, false on any
 *                               discrepancy or error; null while in progress
 * @param summary                free-text summary (error details, discrepancy info)
 * @param triggeredBy            {@code SCHEDULED} or {@code MANUAL}
 * @param requestId              correlation id from {@code CorrelationFilter} on the
 *                               HTTP request that triggered the run; joins this row
 *                               to its {@code admin_audit_log} entry. Null for
 *                               scheduled runs (which have no HTTP context).
 */
public record ReconciliationRunSummary(
        long id,
        Instant startedAt,
        Instant completedAt,
        int transactionsReplayed,
        int discrepanciesDetected,
        Boolean successful,
        String summary,
        String triggeredBy,
        String requestId
) {
    /** True if the run has not yet finished — {@code completed_at} is still null. */
    public boolean inProgress() {
        return completedAt == null;
    }
}
