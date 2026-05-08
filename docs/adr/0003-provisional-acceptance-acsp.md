# ADR-0003: Provisional Acceptance (ACSP) over Synchronous Blocking

## Status

Accepted

## Context

FedNow requires every inbound pacs.008 credit transfer to receive a pacs.002 status response within 20 seconds. The FedNow Service treats a non-response the same as a rejection; repeated non-responses affect the institution's standing as a FedNow participant.

The processing path for an inbound payment is:

```
pacs.008 received
    → Shadow Ledger balance check
    → Submit to core banking system
    → Receive core confirmation/rejection
    → Return pacs.002 to FedNow
```

Steps 1 and 4 are fast (sub-millisecond Redis reads, local JSON serialization). Step 3 — the core confirmation — is not. Legacy core banking systems process transactions asynchronously. A batch-oriented core may accept a transaction immediately but not produce a settled confirmation for several seconds or more. Under peak load, core response times of 10–15 seconds are not uncommon.

If OpenFedNow waits for the core to confirm before responding to FedNow, and the core takes 16 seconds, FedNow's window closes before the response is sent. The institution is marked as non-responsive.

The question is: how should OpenFedNow respond to FedNow when the core has not yet confirmed?

The options considered were:

1. **Synchronous blocking** — Wait for the core to respond, up to the full 20-second window.
2. **Provisional acceptance (ACSP)** — Respond to FedNow with "AcceptedSettlementInProcess" (ACSP) immediately after Shadow Ledger validation, without waiting for the core.
3. **Optimistic acceptance (ACSC)** — Respond to FedNow with "AcceptedSettlementCompleted" (ACSC) immediately, treating Shadow Ledger validation as sufficient confirmation.
4. **Reject on timeout** — If the core does not respond in time, return a rejection (RJCT) to FedNow.

## Decision

Implement a **15-second synchronous timeout** on the core submission. If the core responds within 15 seconds, return its result (ACSC or RJCT) directly to FedNow. If the core has not responded after 15 seconds, return **ACSP** (AcceptedSettlementInProcess) to FedNow, then register the transaction for asynchronous resolution via the Saga reconciliation path.

This is implemented in `SyncAsyncBridge.submitWithTimeout()`:

```java
CompletableFuture.supplyAsync(() -> adapter.postCreditTransfer(message))
    .get(15, TimeUnit.SECONDS)   // → core result if fast enough
    // TimeoutException → return ACSP, register for reconciliation
```

**Why 15 seconds, not 20:** The remaining 5 seconds are reserved for network transit to FedNow, serialization, and any upstream middleware latency. A 20-second timeout would leave no margin and risk missing FedNow's window on a slow network day.

**What ACSP means:** ACSP is ISO 20022's "settlement in process" status. It is a legitimate, defined response for exactly this scenario — the payment has been accepted but final settlement confirmation is pending. FedNow's rules explicitly permit ACSP in the context of provisional acceptance. The distinction from ACSC ("settlement completed") is meaningful: ACSP signals that the institution intends to settle but has not yet confirmed it with the core.

When ACSP is returned, the payment is registered with the `SyncAsyncBridge` for reconciliation. When the core eventually confirms (on return from maintenance window or when the slow response arrives), the Saga advances to completion. If the core rejects the provisionally accepted payment, the Saga triggers a FedNow return payment (pacs.004) as compensation.

## Alternatives Considered

**Synchronous blocking (wait up to 20 seconds)**

The simplest approach: block the response thread and wait. The problem is tail latency: even if 99% of core responses arrive in under 5 seconds, the 1% that take 18 seconds will intermittently cause FedNow timeouts. In a high-volume institution, 1% of payments failing with a FedNow timeout is not acceptable. Worse, if the core goes into a slow state under load — which is precisely when latency spikes — many payments will time out simultaneously, damaging the institution's FedNow participant standing. Rejected.

**Optimistic acceptance (ACSC without core confirmation)**

Return ACSC immediately after Shadow Ledger validation, treating the Shadow Ledger balance as authoritative for settlement. Simpler than ACSP (no reconciliation path needed), but misleading: ACSC represents completed settlement, not a reservation. If the core later rejects the payment (e.g., account closed, compliance hold), OpenFedNow has already told FedNow the payment was completed. The resulting pacs.004 return payment would look like a reversal, not an error — a regulatory and audit concern. Rejected because ACSP is semantically correct and ACSC is not.

**Reject on timeout**

If the core does not respond in time, send RJCT to FedNow and compensate. Simple and conservative: the institution never accepts a payment it hasn't confirmed with the core. The problem is that this is indistinguishable from a core availability failure from FedNow's perspective. An institution that frequently rejects payments due to core latency will be treated as a poor-quality participant. The entire purpose of OpenFedNow is to absorb this timing gap, not to expose it to FedNow as rejections. Rejected.

## Consequences

**Positive:**
- FedNow's 20-second window is reliably met regardless of core response time. The institution's participant standing is protected.
- ACSP is semantically correct: it accurately represents "accepted but not yet final" — which is exactly the system state when the core has not confirmed.
- The 15-second window is large enough to capture the majority of core responses synchronously, minimising the number of transactions that need to go through the async reconciliation path.
- Compensation via pacs.004 is a well-defined ISO 20022 mechanism. The code path for "core rejects a provisionally accepted payment" is explicit, tested, and auditable.

**Negative:**
- The async reconciliation path adds complexity: the Saga must track ACSP transactions, the core must eventually confirm or reject, and the pacs.004 compensation path must be exercised in production. Each of these is a failure mode that requires monitoring.
- ACSP creates a window of financial exposure: the institution has told FedNow that a payment is in process before the core has confirmed it can be posted. If the core rejects (e.g., account frozen after the Shadow Ledger check passed), the institution must issue a return — which FedNow processes as a separate transaction, introducing a brief period where the recipient believes they have received funds that will subsequently be reversed. This is a known limitation documented in [known-limitations.md](../known-limitations.md).
- The 15-second timeout is a configurable constant in the current implementation. Institutions with consistently slow cores may need to tune this value (accepting a tighter margin against FedNow's 20-second window) or invest in core response time optimisation.

## Related

- `SyncAsyncBridge.java` — timeout and ACSP logic
- `SagaOrchestrator.java` — compensation for core-rejected provisionally accepted payments
- [ADR-0004](0004-eventual-consistency-shadow-ledger-and-core.md) — the consistency model that makes ACSP safe
- [shadow-ledger.md](../shadow-ledger.md) — how the Shadow Ledger validates the balance before ACSP is issued
- [saga-pattern.md](../saga-pattern.md) — how the compensation path executes when the core rejects
