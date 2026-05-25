# ADR-0007: camt.056 / camt.029 cancellation lifecycle

## Status

Accepted

## Context

The ISO 20022 message classes for payment cancellation (`Camt056Message`, `Camt029Message`) were modeled but not wired into any endpoint or service. The framework receives inbound `pacs.008` credit transfers and applies them to the Shadow Ledger; when a sending institution subsequently asks to cancel one of those transfers via `camt.056`, the framework must decide whether the cancellation can still be honored and respond with the appropriate `camt.029` resolution.

The decision is not symmetric across saga states. A payment that has only just arrived can be undone cleanly. A payment that has already been confirmed to FedNow cannot — the settlement is committed and the originator must fall back to a `pacs.004` return. In between are states where the outcome genuinely cannot yet be determined (the core call is in flight; compensation is already running for an unrelated reason).

Three approaches were considered:

1. **Best-effort cancel with reversal**: every state attempts a reversal; non-cancellable states log a warning. Simple, but loses operational signal and creates ledger drift when settlement has already happened.
2. **State-keyed decision matrix returning one of CNCL / RJCR / PDCR**: each saga state maps deterministically to one camt.029 outcome. The matrix is explicit, testable, and produces a clean operator audit trail.
3. **Synchronous-ack-then-async-resolution**: respond `PDCR` immediately for every request and resolve later in a background job. ISO 20022 supports this — the standard explicitly allows a follow-up camt.029 — but the framework does not yet track pending cancellations for asynchronous resolution.

## Decision

Implement the **state-keyed decision matrix** in `CancellationService`. Every inbound `camt.056` produces a deterministic `camt.029` outcome based on the saga's current state. Cancellation side effects (Shadow Ledger credit reversal, saga transition to `FAILED`) occur only on `CNCL` outcomes.

### Matrix

| Saga state at receipt | camt.029 outcome | Side effect |
|---|---|---|
| _(no saga found)_ | RJCR / NOOR | none |
| INITIATED | CNCL | mark saga FAILED — no funds reserved yet |
| FUNDS_RESERVED | CNCL | reverse Shadow Ledger credit; mark saga FAILED |
| CORE_SUBMITTED | PDCR | none — outcome unknown until core / reconciliation |
| FEDNOW_CONFIRMED | RJCR / ARDT | none — settlement confirmed; use pacs.004 instead |
| COMPLETED | RJCR / ARDT | none — settlement confirmed; use pacs.004 instead |
| COMPENSATING | PDCR | none — compensation already in progress |
| FAILED | RJCR / NOOR | none — original transaction is no longer live |

### Implementation

- `ShadowLedger.reverseCredit(transactionId)` — mirror of `reverseDebit` for the inbound case. Looks up the original `CREDIT` row, decrements the Redis balance by the same amount, writes a `REVERSAL` audit row. Symmetric pair so both inbound and outbound cancellations have a clean primitive.
- `SagaOrchestrator.cancelInboundSaga(sagaId, reasonCode)` — distinct from `compensate()`, which assumes a debit reversal. The cancellation path knows it's an inbound saga and calls `reverseCredit`. Guards against re-cancellation of terminal sagas.
- `CancellationService.handleCancellationRequest(camt056)` — looks up the saga via `SagaOrchestrator.findByTransactionId`, applies the matrix above, and returns the `Camt029Message`.
- `FedNowGateway.receiveCancellation` and `RtpGateway.receiveCancellation` — rail-aware certificate validation; identical downstream handling. The same `CancellationService` instance serves both rails because the cancellation lifecycle is rail-agnostic, just like the credit transfer lifecycle (ADR-0005).

The response is always synchronous — the camt.029 returned in the HTTP response body is the resolution. This matches the existing pacs.008 → pacs.002 flow.

## Alternatives Considered

**Best-effort cancel.**
Every saga state attempts a reversal; if no CREDIT exists or settlement happened, the operation silently no-ops. Rejected because it loses the signal that the originator needs to fall back to a pacs.004. Operators would have no audit trail distinguishing "cancellation succeeded" from "cancellation silently dropped".

**Synchronous PDCR + asynchronous final resolution.**
Always respond PDCR; resolve the actual outcome in a scheduled job that writes the follow-up camt.029. ISO 20022 supports this and is closer to how some production RTGS systems behave. Rejected for now because: (a) it requires tracking pending cancellations in a new table and a delivery mechanism for the follow-up message, both of which are scope this issue doesn't justify; and (b) the synchronous matrix already produces the correct outcome for the four deterministic states (INITIATED, FUNDS_RESERVED, FEDNOW_CONFIRMED, COMPLETED), and PDCR is reserved for the genuinely ambiguous cases (CORE_SUBMITTED, COMPENSATING).

**Outbound cancellation in the same change.**
The issue text mentions both directions. Rejected for this change — outbound camt.056 (we initiate a recall of our own payment) needs an admin endpoint, a tracking record so the camt.029 response can be matched to the right saga, and a state machine for the cancellation case itself. That's its own work, separable from receive-side handling, and not blocking the operator value of supporting inbound cancellations.

## Consequences

**Positive:**
- Operators get explicit signal on every cancellation attempt. The matrix is documented; the audit log shows which branch fired.
- The saga state machine is unchanged. Cancellation reuses the existing COMPENSATING → FAILED terminal path with a distinct reason code carried from the camt.056.
- Both rails work without code duplication — the service is rail-agnostic.

**Negative:**
- PDCR responses for CORE_SUBMITTED and COMPENSATING are best-effort acknowledgements without a follow-up resolution. The originator institution must follow up with a pacs.004 return if reconciliation later confirms the payment settled. This is the same gap that ARDT-after-pacs.004 already implies, just exposed via a different path. Documented in known-limitations.
- During FUNDS_RESERVED → CORE_SUBMITTED transition, a cancellation could race the core call. Worst case: we respond CNCL and reverse the credit, then the core confirms the original transfer. The next reconciliation cycle will surface this as a discrepancy and the operator will need to manually issue a pacs.004 return. This window is narrow (sub-second) and is documented as a known-limitations entry.
- Outbound cancellation (we initiate the camt.056) is not implemented. The class `Camt056Message.forPaymentCancellation` exists and constructs a valid message, but the framework has no admin path or saga binding to issue one. Tracked as future work.

## Related

- `Camt056Message.java`, `Camt029Message.java` — message models (predate this ADR)
- `CancellationService.java` — implements the decision matrix
- `SagaOrchestrator.cancelInboundSaga` — saga lifecycle hook for cancellation
- `ShadowLedger.reverseCredit` — Shadow Ledger primitive for crediting back
- `FedNowGateway.receiveCancellation`, `RtpGateway.receiveCancellation` — HTTP entry points
- [ADR-0005](0005-dual-rail-architecture-fednow-rtp.md) — rail-agnostic processing principle reused here
- [known-limitations.md](../known-limitations.md) — entry 10 (cancellation handlers) is now resolved
