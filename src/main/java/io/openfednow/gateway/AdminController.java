package io.openfednow.gateway;

import io.openfednow.processing.saga.SagaOrchestrator;
import io.openfednow.processing.saga.SagaSnapshot;
import io.openfednow.security.audit.AdminAuditEntry;
import io.openfednow.security.audit.AdminAuditLogService;
import io.openfednow.shadowledger.AccountBalanceView;
import io.openfednow.shadowledger.ReconciliationRunSummary;
import io.openfednow.shadowledger.ReconciliationService;
import io.openfednow.shadowledger.ShadowLedger;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * Administrative endpoints for operational control of the OpenFedNow framework.
 *
 * <p>All endpoints under {@code /admin/**} require HTTP Basic authentication with
 * the {@code ADMIN} role (configured in {@code SecurityConfig}). In production
 * they should additionally be restricted to the institution's internal network
 * or a bastion host since they expose operational state and trigger
 * data-modifying operations.
 */
@RestController
@RequestMapping("/admin")
@Tag(
    name = "Admin",
    description = "Operational query and control endpoints. Restrict access in production."
)
public class AdminController {

    private final ReconciliationService reconciliationService;
    private final SagaOrchestrator sagaOrchestrator;
    private final ShadowLedger shadowLedger;
    private final AdminAuditLogService adminAuditLogService;

    public AdminController(ReconciliationService reconciliationService,
                           SagaOrchestrator sagaOrchestrator,
                           ShadowLedger shadowLedger,
                           AdminAuditLogService adminAuditLogService) {
        this.reconciliationService = reconciliationService;
        this.sagaOrchestrator = sagaOrchestrator;
        this.shadowLedger = shadowLedger;
        this.adminAuditLogService = adminAuditLogService;
    }

    /**
     * Triggers a full reconciliation cycle against the core banking system.
     *
     * <p>Finds all accounts with unconfirmed Shadow Ledger entries, fetches their
     * authoritative balances from the core, corrects any discrepancies, and marks
     * all pending transactions as {@code core_confirmed = TRUE}.
     *
     * <p>Normally triggered automatically when the core transitions from OFFLINE
     * to ONLINE. This endpoint allows manual triggering for demos, testing, or
     * when the automatic detection is bypassed.
     */
    @PostMapping("/reconcile")
    @Operation(
        summary = "Trigger reconciliation",
        description = """
            Runs a full Shadow Ledger reconciliation cycle. Finds all accounts with \
            unconfirmed entries, fetches authoritative balances from the core, corrects \
            discrepancies, and marks transactions as core_confirmed. Returns a report \
            with counts of replayed transactions and detected discrepancies."""
    )
    public ResponseEntity<ReconciliationService.ReconciliationReport> reconcile() {
        ReconciliationService.ReconciliationReport report = reconciliationService.reconcile();
        return ResponseEntity.ok(report);
    }

    /**
     * Lists all sagas that have not yet reached a terminal state.
     *
     * <p>Returns sagas in {@code INITIATED}, {@code FUNDS_RESERVED},
     * {@code CORE_SUBMITTED}, {@code FEDNOW_CONFIRMED}, or {@code COMPENSATING}
     * — i.e., the work an operator might need to act on. Ordered oldest first
     * so long-running sagas surface at the top.
     */
    @GetMapping("/sagas")
    @Operation(
        summary = "List in-flight sagas",
        description = """
            Returns all sagas whose state is not COMPLETED or FAILED, ordered oldest first. \
            Each entry includes the saga ID, ISO 20022 transaction and end-to-end IDs, \
            current state, source rail (FEDNOW / RTP), and timestamps. Use this to surface \
            stuck or long-running payments during operational review."""
    )
    @ApiResponse(responseCode = "200", description = "List of in-flight saga snapshots",
        content = @Content(mediaType = "application/json",
                           array = @io.swagger.v3.oas.annotations.media.ArraySchema(
                                   schema = @Schema(implementation = SagaSnapshot.class))))
    public ResponseEntity<List<SagaSnapshot>> listInflightSagas() {
        return ResponseEntity.ok(sagaOrchestrator.listInflight());
    }

    /**
     * Returns the full saga snapshot for a given ISO 20022 transaction ID.
     *
     * <p>Returns 404 if no saga exists for that transaction. This is the most
     * common diagnostic lookup during incident response.
     */
    @GetMapping("/sagas/{transactionId}")
    @Operation(
        summary = "Look up a saga by ISO 20022 transaction ID",
        description = """
            Returns the full saga snapshot — saga ID, ISO 20022 identifiers, current \
            state, source rail, return reason code and failure description (set when \
            compensation was triggered), and creation / last-update timestamps. \
            Returns 404 if no saga is recorded for the given transaction ID."""
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Saga snapshot",
            content = @Content(mediaType = "application/json",
                               schema = @Schema(implementation = SagaSnapshot.class))),
        @ApiResponse(responseCode = "404",
            description = "No saga recorded for the given transaction ID")
    })
    public ResponseEntity<SagaSnapshot> getSagaByTransactionId(
            @Parameter(description = "ISO 20022 TransactionId from the originating pacs.008",
                       example = "TXN-20260520-001")
            @PathVariable String transactionId) {
        Optional<SagaSnapshot> snapshot = sagaOrchestrator.findByTransactionId(transactionId);
        return snapshot.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Returns the Shadow Ledger balance view for an account.
     *
     * <p>Reports the live Redis balance ({@code available}) and the sum of DEBITs
     * that have hit the Shadow Ledger but are not yet confirmed by the core
     * ({@code reservedPendingCore}). The latter is the amount the next
     * reconciliation cycle is expected to validate against the core ledger.
     */
    @GetMapping("/accounts/{accountId}/balance")
    @Operation(
        summary = "Get Shadow Ledger balance for an account",
        description = """
            Returns the operator-facing Shadow Ledger snapshot for an account: \
            the live Redis balance (available), the sum of unconfirmed DEBITs \
            awaiting core confirmation (reservedPendingCore), and the timestamp of \
            the most recent transaction. Available is zero for accounts that have \
            never been seeded; reservedPendingCore is zero when the core has \
            confirmed every applied DEBIT."""
    )
    @ApiResponse(responseCode = "200", description = "Account balance snapshot",
        content = @Content(mediaType = "application/json",
                           schema = @Schema(implementation = AccountBalanceView.class)))
    public ResponseEntity<AccountBalanceView> getAccountBalance(
            @Parameter(description = "Institution-internal account identifier",
                       example = "ACC-001")
            @PathVariable String accountId) {
        return ResponseEntity.ok(shadowLedger.getBalanceView(accountId));
    }

    // ── Reconciliation audit (issue #41) ──────────────────────────────────────

    /** Hard ceiling on a single page of reconciliation runs. */
    static final int RECON_RUNS_MAX_LIMIT = 200;
    /** Default page size when {@code limit} is omitted. */
    static final int RECON_RUNS_DEFAULT_LIMIT = 50;

    /**
     * Lists past reconciliation runs in newest-first order, paginated.
     *
     * <p>Each entry includes the run identifier, start and completion timestamps,
     * counts of replayed transactions and detected discrepancies, the
     * success flag, the summary text, and whether the run was triggered
     * by the scheduler or manually.
     */
    @GetMapping("/reconciliation-runs")
    @Operation(
        summary = "List reconciliation runs",
        description = """
            Returns reconciliation runs ordered newest first. \
            limit defaults to 50 and is capped at 200; offset defaults to 0. \
            Each entry has the run id, started_at, completed_at, replay and \
            discrepancy counts, success flag, summary, and triggered_by \
            (SCHEDULED or MANUAL)."""
    )
    @ApiResponse(responseCode = "200", description = "Paginated reconciliation history",
        content = @Content(mediaType = "application/json",
                           array = @io.swagger.v3.oas.annotations.media.ArraySchema(
                                   schema = @Schema(implementation = ReconciliationRunSummary.class))))
    public ResponseEntity<List<ReconciliationRunSummary>> listReconciliationRuns(
            @Parameter(description = "Max rows to return (default 50, max 200)")
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @Parameter(description = "Rows to skip from the newest end")
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        int boundedLimit = Math.max(1, Math.min(limit, RECON_RUNS_MAX_LIMIT));
        int boundedOffset = Math.max(0, offset);
        return ResponseEntity.ok(reconciliationService.listRecentRuns(boundedLimit, boundedOffset));
    }

    /**
     * Returns a single reconciliation run by its surrogate id.
     *
     * <p>404 if the id does not match any row. The schema does not store
     * individual per-transaction discrepancy records — only the count and a
     * free-text summary — so the returned payload mirrors what the list
     * endpoint surfaces, scoped to one run.
     */
    @GetMapping("/reconciliation-runs/{runId}")
    @Operation(
        summary = "Look up a reconciliation run by id",
        description = """
            Returns the full run summary for a reconciliation_run row, or 404 \
            if no row matches. Use this after spotting an interesting run in the \
            list endpoint to inspect its summary text and timestamps."""
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Reconciliation run summary",
            content = @Content(mediaType = "application/json",
                               schema = @Schema(implementation = ReconciliationRunSummary.class))),
        @ApiResponse(responseCode = "404",
            description = "No reconciliation run exists with the given id")
    })
    public ResponseEntity<ReconciliationRunSummary> getReconciliationRun(
            @Parameter(description = "Surrogate id from the reconciliation_run table",
                       example = "42")
            @PathVariable long runId) {
        return reconciliationService.findRunById(runId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Triggers a reconciliation cycle on demand.
     *
     * <p>Resource-style alias for {@link #reconcile()}. Both endpoints invoke
     * the same {@link ReconciliationService#reconcile()} method; both return
     * a {@link ReconciliationService.ReconciliationReport} summarizing the run
     * just performed.
     */
    @PostMapping("/reconciliation-runs")
    @Operation(
        summary = "Trigger a reconciliation run",
        description = """
            Starts a synchronous reconciliation cycle and returns its report. \
            Behaviorally identical to POST /admin/reconcile — both invoke \
            ReconciliationService.reconcile(). This resource-style variant is \
            offered alongside the legacy verb-style endpoint so REST clients \
            can use whichever convention they prefer."""
    )
    public ResponseEntity<ReconciliationService.ReconciliationReport> triggerReconciliationRun() {
        return ResponseEntity.ok(reconciliationService.reconcile());
    }

    // ── Admin audit log (issue #50) ───────────────────────────────────────────

    /** Hard ceiling on a single page of audit entries. */
    static final int AUDIT_LOG_MAX_LIMIT = 500;

    /**
     * Lists recent {@code /admin/**} access attempts in newest-first order.
     *
     * <p>Every request to the admin namespace — whether GRANTED, DENIED,
     * REJECTED, or ERROR — is recorded by {@code AdminAccessAuditFilter}.
     * This endpoint is the operator-facing view of that log.
     */
    @GetMapping("/audit-log")
    @Operation(
        summary = "List admin endpoint access history",
        description = """
            Returns audit entries for /admin/** access in newest-first order. \
            Every request — successful or failed — is recorded with principal, \
            method, path, status code, and result classification. limit defaults \
            to 100 and is capped at 500; offset defaults to 0."""
    )
    @ApiResponse(responseCode = "200", description = "Paginated admin access history",
        content = @Content(mediaType = "application/json",
                           array = @io.swagger.v3.oas.annotations.media.ArraySchema(
                                   schema = @Schema(implementation = AdminAuditEntry.class))))
    public ResponseEntity<List<AdminAuditEntry>> listAuditLog(
            @Parameter(description = "Max rows to return (default 100, max 500)")
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @Parameter(description = "Rows to skip from the newest end")
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        int boundedLimit = Math.max(1, Math.min(limit, AUDIT_LOG_MAX_LIMIT));
        int boundedOffset = Math.max(0, offset);
        return ResponseEntity.ok(adminAuditLogService.listRecent(boundedLimit, boundedOffset));
    }
}
