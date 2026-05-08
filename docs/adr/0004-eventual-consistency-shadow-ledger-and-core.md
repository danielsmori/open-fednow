# ADR-0004: Eventual Consistency Between Shadow Ledger and Core

## Status

Accepted

## Context

OpenFedNow maintains two representations of account balances:

1. **The Shadow Ledger** (Redis) — updated in real time on every FedNow transaction. Authoritative during core maintenance windows. Sub-millisecond reads.
2. **The core banking system** — the institution's ledger of record. Updated when transactions are posted. Authoritative during normal operation.

These two systems cannot be updated in the same atomic transaction. Redis and a legacy core banking system do not share a transaction coordinator, and legacy cores do not participate in two-phase commit (2PC) protocols. Any operation that touches both systems is inherently non-atomic: either the Shadow Ledger update succeeds and the core update fails, or vice versa.

This means the two systems will periodically disagree. The question is: how should that disagreement be managed?

The consistency options for a distributed system without a shared transaction coordinator are:

1. **Strong consistency** — ensure the two systems are always in agreement before returning. Requires a distributed transaction protocol (2PC or similar).
2. **Causal consistency** — ensure that operations are applied in the same causal order on both systems, but permit temporary divergence.
3. **Eventual consistency** — accept temporary divergence and guarantee that the systems will converge to the same state within a bounded time window.
4. **Shadow Ledger as read-only mirror** — the Shadow Ledger never makes authoritative balance decisions; it only caches core reads.

## Decision

Accept **eventual consistency** between the Shadow Ledger and the core banking system, with the following guarantees:

- **The core is always the ledger of record.** After reconciliation, the core's confirmed balance supersedes the Shadow Ledger.
- **Reconciliation is bounded.** During a maintenance window, the Shadow Ledger diverges from the core. When the core returns online, `ReconciliationService.reconcile()` replays all unconfirmed transactions and re-synchronises balances. The divergence window is bounded by the length of the maintenance window (typically 2–4 hours).
- **Zero discrepancy tolerance after reconciliation.** Any difference between the Shadow Ledger's computed balance and the core's confirmed balance after reconciliation is treated as an error, not a normal condition, and triggers an alert. The Shadow Ledger's balance is overwritten with the core's confirmed figure.
- **The Shadow Ledger is authoritative only during the divergence window.** Between a maintenance window start and a successful reconciliation, the Shadow Ledger makes balance decisions without core confirmation. This is an intentional, time-bounded exception to the "core is authoritative" rule.

The reconciliation protocol:
```
Core returns online
    → ReconciliationService.reconcile()
    → For each account with unconfirmed transactions:
        1. Replay unconfirmed transactions against core (in timestamp order)
        2. GET confirmed balance from core
        3. Compare against Shadow Ledger balance
        4. If match: mark transactions as core_confirmed = TRUE
        5. If mismatch: alert, overwrite Shadow Ledger with core balance,
           log RECONCILIATION row in audit table
```

If a transaction that was provisionally accepted (ACSP) is rejected by the core during reconciliation, the Saga compensation path triggers a pacs.004 return payment.

## Alternatives Considered

**Strong consistency via 2PC**

Two-phase commit would guarantee that the Shadow Ledger and core are always in agreement. In practice, legacy core banking systems do not implement 2PC. Even if they did, the coordinator overhead would add latency to every FedNow transaction — precisely the latency budget that is already tight (see [ADR-0003](0003-provisional-acceptance-acsp.md)). A 2PC commit that times out leaves the system in an indeterminate state that is arguably worse than an eventually-consistent one with a clear reconciliation protocol. Rejected as incompatible with legacy core capabilities.

**Shadow Ledger as read-only mirror (no authoritative decisions)**

The Shadow Ledger caches core balances but is never the decision-maker: if the core is offline, reject the payment rather than serve from the Shadow Ledger. This is the simplest possible consistency model (no divergence possible if the Shadow Ledger never makes decisions). It is also the least useful: it provides no maintenance-window availability improvement, which is the primary motivation for the Shadow Ledger's existence. The entire architectural rationale of this framework is to absorb the core's unavailability. Rejected as contradicting the framework's core value proposition.

**Causal consistency**

Ensure that every operation applied to the Shadow Ledger is eventually applied to the core in the same causal order. This is essentially what reconciliation does — the distinction from "eventual consistency" is subtle. The difference would matter if there were multiple nodes updating the Shadow Ledger concurrently in a way that could create causal ordering violations. In OpenFedNow's current architecture, there is a single Shadow Ledger service (per deployment), so causal ordering of the Shadow Ledger → core replay is trivially guaranteed by timestamp ordering. Effectively equivalent to the chosen approach.

## Consequences

**Positive:**
- No distributed transaction protocol required. The Shadow Ledger and core operate independently, each using their own native consistency mechanisms (Redis WATCH/MULTI/EXEC and the core's own transaction handling).
- The divergence window is explicit, bounded, and monitored. This is safer than a system that claims strong consistency but achieves it through undocumented hacks.
- Reconciliation is a first-class operational workflow, not a last-resort repair procedure. The `ReconciliationService` runs automatically on core return, and its audit table (`reconciliation_run`) provides a complete record of every reconciliation cycle.
- Zero discrepancy tolerance after reconciliation means that undetected drift is architecturally impossible. Any discrepancy surfaces as an alert, not as a silent balance error that compounds over time.

**Negative:**
- During a maintenance window, the Shadow Ledger makes financial decisions without the core's knowledge. This is a deliberate departure from the "core is always authoritative" principle. The risk is bounded: the Shadow Ledger's balance is initialised from the core and updated atomically on each FedNow transaction, so the only source of divergence is a core-side rejection of a provisionally accepted payment — which is handled by the Saga compensation path.
- Reconciliation after a long maintenance window must replay transactions in strict timestamp order. Out-of-order replay would post transactions against balances that don't reflect the correct intermediate state. The `ReconciliationService` enforces ordering via `applied_at` timestamps, but this assumes monotonically increasing and correct timestamps on the OpenFedNow middleware — a dependency on NTP accuracy.
- The eventual consistency model is harder to reason about and test than a strongly consistent one. Tests must explicitly simulate divergence scenarios (maintenance window → provisional acceptance → core return → reconciliation) to validate the convergence path. See `BridgeModeIntegrationTest` and `SagaCompensationIntegrationTest`.
- If the core rejects a provisionally accepted transaction during reconciliation, the compensation path (pacs.004 return payment) is a visible event to the payment sender. The sender's institution received a credit that is subsequently returned. Depending on regulatory context and the reason for rejection, this may require additional notification or escalation. This is documented in [known-limitations.md](../known-limitations.md).

## Related

- `ReconciliationService.java` — convergence protocol implementation
- `ShadowLedger.java` — `reconcile()` method for balance correction
- `SagaOrchestrator.java` — compensation for post-reconciliation core rejections
- [ADR-0001](0001-optimistic-locking-shadow-ledger-debits.md) — how atomic updates to the Shadow Ledger are guaranteed
- [ADR-0002](0002-redis-shadow-ledger-over-direct-core-reads.md) — why Redis is used as the Shadow Ledger store
- [ADR-0003](0003-provisional-acceptance-acsp.md) — why ACSP is returned when the core has not confirmed
- [shadow-ledger.md](../shadow-ledger.md) — Shadow Ledger operational detail including the maintenance-window cycle
