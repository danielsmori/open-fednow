# ADR-0005: Dual-Rail Architecture — FedNow and RTP

## Status

Accepted

## Context

The Clearing House's RTP® network and the Federal Reserve's FedNow Service are the two primary ISO 20022 instant payment rails operating in the United States. Both launched within a few years of each other (RTP: 2017, FedNow: 2023), and many financial institutions will eventually need to participate in both.

When this framework was designed, a key question was whether to build a FedNow-specific pipeline or to build a rail-agnostic pipeline with a FedNow-specific entry point.

The four incompatibilities that motivate this framework's existence apply equally to RTP:

| Incompatibility | FedNow | RTP |
|---|---|---|
| Processing model | Legacy batch vs. real-time event | Legacy batch vs. real-time event |
| Availability | Core has maintenance windows; FedNow is 24/7 | Core has maintenance windows; RTP is 24/7 |
| Protocol | Legacy proprietary APIs vs. ISO 20022 | Legacy proprietary APIs vs. ISO 20022 |
| Concurrency | Legacy not designed for simultaneous load | Legacy not designed for simultaneous load |

Both rails also share the same ISO 20022 message types at the core: `pacs.008.001.08` (credit transfer), `pacs.002.001.10` (payment status report), `pacs.004.002.06` (return), `camt.056.001.08` (cancellation request), and `camt.029.001.09` (investigation response). The message payloads that flow through Layers 2–4 are the same regardless of which rail delivered them.

What does vary between rails is confined to Layer 1 (the API Gateway):

- **Connectivity model** — FedNow uses REST/JSON over TLS with Federal Reserve PKI certificates. RTP uses ISO 20022 XML over a dedicated network connection with TCH (The Clearing House) certificates.
- **Message envelope** — FedNow wraps ISO 20022 in a JSON envelope. RTP uses the canonical XML envelope.
- **Settlement mechanism** — FedNow settles via the FedNow settlement service. RTP settles via TCH's multilateral net settlement.
- **Participation rules** — Different application processes, certification requirements, and bilateral agreements.

## Decision

Keep Layers 2–4 entirely rail-agnostic. Layer 1 is the only variation point between FedNow and RTP.

The gateway layer provides the extensibility point:

```
FedNow Service  ──→  FedNowGateway  ──┐
                                       ├──→  MessageRouter  ──→  Layers 2–4  ──→  Core
RTP Network     ──→  RtpGateway     ──┘
```

Both gateways parse their respective message envelopes and deliver the same `Pacs008Message` to `MessageRouter.routeInbound()`. The `MessageRouter` and everything downstream — `CoreBankingAdapter`, `ShadowLedger`, `SagaOrchestrator`, `IdempotencyService` — have no knowledge of which rail the message arrived on.

`RtpGateway.java` is stubbed in `io.openfednow.gateway`. The stub documents the architectural intent without implementing the TCH network connectivity (which requires The Clearing House participation, a dedicated network connection, and TCH-issued certificates that cannot be obtained in open-source development).

## Alternatives Considered

**Rail-specific processing pipelines**

Build a separate Layer 2–4 stack for each rail: one set of Saga orchestrators, Shadow Ledger instances, and idempotency services for FedNow; a separate set for RTP.

The argument for this approach is operational isolation: a defect in the FedNow pipeline cannot affect the RTP pipeline. In practice, the disadvantage far outweighs the isolation benefit. The Shadow Ledger, Saga orchestration, and idempotency logic are institution-level concerns — they track balances and transaction state for accounts that receive payments from either rail. Duplicating the pipelines would mean that a $500 debit from a FedNow payment and a $500 debit from an RTP payment compete against the same account balance but are tracked in separate Shadow Ledgers. This creates a consistency problem that is harder than anything solved by the five-layer architecture. Rejected.

**Separate deployments per rail**

Deploy a separate instance of the full five-layer stack for each rail, with a separate Shadow Ledger per rail.

This avoids the pipeline duplication consistency problem by routing each account to exactly one rail per deployment. It is operationally simpler in the short term and achieves isolation. The cost is that dual-rail participation requires running two independent deployments, two Shadow Ledgers, two reconciliation cycles, and two sets of vendor adapter integrations — roughly doubling operational complexity. For a community bank connecting to two rails, this is the wrong trade-off. Rejected as the default approach; appropriate as a deployment option for institutions that require strict operational isolation between rails for regulatory reasons.

**Encode rail source in `Pacs008Message`**

Add a `sourceRail` field to `Pacs008Message` so that Layers 2–4 can make rail-aware decisions if needed in the future.

This approach would preserve dual-rail flexibility while allowing per-rail behavior. The problem is that it pollutes the ISO 20022 domain model with an infrastructure concern. The ISO 20022 message standard does not include a source-rail field; adding one creates a non-standard internal model. If rail-specific behavior is genuinely required at Layer 3 or 4, the correct mechanism is a separate application-level context object passed alongside the message, not a modified message model. Deferred — not needed now, and the right implementation approach is clear if it becomes necessary.

## Consequences

**Positive:**
- Dual-rail participation requires only a new `RtpGateway` implementation in Layer 1. Layers 2–4 require no changes.
- The ISO 20022 message models (`Pacs008Message`, `Pacs002Message`, etc.) work for both rails without modification.
- All existing tests for Layers 2–4 cover both rails by construction — there is nothing rail-specific to test in those layers.
- An institution deploying OpenFedNow for FedNow today does not need to re-integrate or re-certify Layers 2–4 when adding RTP participation later.

**Negative:**
- `RtpGateway` needs to track the inbound source rail to ensure that `Pacs002Message` responses are returned to the correct rail. This is a small addition to `MessageRouter` — the router must record which gateway received the inbound message and dispatch the response back to the same gateway. Not architecturally complex, but it is a deliberate gap in the current implementation.
- The TCH certificate validation and RTP XML envelope parsing are not implemented. These require The Clearing House participation credentials that are outside the scope of open-source development.
- Dual-rail operation with a single Shadow Ledger requires that the Shadow Ledger's balance accounting is correct regardless of rail source. This is straightforward (the Shadow Ledger is account-centric, not rail-centric) but must be verified when `RtpGateway` is fully implemented.

## Related

- `FedNowGateway.java` — FedNow-specific Layer 1 implementation (the model for `RtpGateway`)
- `RtpGateway.java` — RTP stub (documents architectural intent)
- `MessageRouter.java` — routes parsed `Pacs008Message` to Layers 2–4 regardless of source rail
- [rtp-compatibility.md](../rtp-compatibility.md) — operational guide: what works today vs. what is stubbed
- [ADR-0001](0001-optimistic-locking-shadow-ledger-debits.md) — Shadow Ledger concurrency guarantees that apply to both rails
