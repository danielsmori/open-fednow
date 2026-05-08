package io.openfednow.gateway;

import io.openfednow.shadowledger.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative endpoints for operational control of the OpenFedNow framework.
 *
 * <p>These endpoints are intended for operations teams and integration demos.
 * In production they should be protected behind network-level access controls
 * (e.g., accessible only from the institution's internal network or a bastion host)
 * since they can trigger data-modifying operations.
 */
@RestController
@RequestMapping("/admin")
@Tag(
    name = "Admin",
    description = "Operational control endpoints. Restrict access in production."
)
public class AdminController {

    private final ReconciliationService reconciliationService;

    public AdminController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
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
     *
     * @return a {@link ReconciliationService.ReconciliationReport} with counts of
     *         replayed transactions and detected discrepancies
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
}
