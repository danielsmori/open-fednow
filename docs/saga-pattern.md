# Saga Pattern

OpenFedNow uses the Saga pattern to manage distributed transactions safely across FedNow, the Shadow Ledger, and the core banking system.

## Problem

A single FedNow payment touches multiple systems: the Shadow Ledger (balance reservation), the core banking system (actual posting), and FedNow itself (settlement confirmation). A traditional database transaction cannot span these systems. If any step fails after others have succeeded, the partially-completed state must be rolled back.

## Saga Steps (Inbound Payment)

| Step | Action | Compensation |
|------|--------|-------------|
| 1 | Reserve funds in Shadow Ledger | Release reservation |
| 2 | Submit to core banking system | Request reversal from core |
| 3 | Confirm acceptance to FedNow | Send return payment (pacs.004) |
| 4 | Reconcile Shadow Ledger with core confirmation | N/A (terminal step) |

## Failure Scenarios

- **Core rejects after Shadow Ledger reservation**: Compensation releases the Shadow Ledger reservation. No FedNow impact.
- **Core rejects after FedNow confirmation**: Compensation triggers a FedNow return (pacs.004) and releases the Shadow Ledger reservation.
- **Core timeout during SyncAsyncBridge**: Transaction enters PENDING state. Reconciliation service resolves on core return.

## Related classes

- `PaymentSaga.java` — saga state machine
- `SagaOrchestrator.java` — lifecycle management and compensation coordination
