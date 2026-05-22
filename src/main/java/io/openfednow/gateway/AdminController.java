package io.openfednow.gateway;

import io.openfednow.processing.saga.SagaOrchestrator;
import io.openfednow.processing.saga.SagaSnapshot;
import io.openfednow.shadowledger.AccountBalanceView;
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

    public AdminController(ReconciliationService reconciliationService,
                           SagaOrchestrator sagaOrchestrator,
                           ShadowLedger shadowLedger) {
        this.reconciliationService = reconciliationService;
        this.sagaOrchestrator = sagaOrchestrator;
        this.shadowLedger = shadowLedger;
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
}
