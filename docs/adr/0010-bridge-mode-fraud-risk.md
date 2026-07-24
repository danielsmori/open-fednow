# ADR-0010: Bridge-mode fraud risk and the STIP gap

## Status

Accepted

## Context

During a core banking maintenance window, the framework enters "bridge mode": the Shadow Ledger becomes the authoritative balance source, ACSP is returned to FedNow, and inbound transactions are queued in RabbitMQ for replay after the core comes back online. ADR-0003 explains why ACSP is chosen; ADR-0004 explains why eventual consistency between the Shadow Ledger and the core is acceptable.

Neither of those ADRs names a specific risk that arises during the same window: **the Shadow Ledger is not the only system authorizing debits on the customer's account.** Card-processor Stand-In Processing (STIP) runs on a parallel path — Visa DPS, FIS CardManager, Fiserv STAR, Jack Henry card processing, etc. During the maintenance window, a debit card swipe or ATM withdrawal is authorized by the card network through STIP against a stale balance snapshot the network holds. That authorization never touches the Shadow Ledger until reconciliation.

The consequence is a bounded double-spend window. A customer with $1,000 available can, within a single maintenance window:

1. Have STIP authorize a $600 card purchase against the stale $1,000 balance held by the card network.
2. Have OpenFedNow authorize a $600 outbound FedNow send against the $1,000 balance held in the Shadow Ledger.

Both are approved. Both go to settlement. The customer's account is overdrawn by $200 after reconciliation reconciles the two.

Prof. James Angel (Georgetown McDonough, Fed Faster Payments Task Force) surfaced this in a technical review on 2026-07-14. It is a real gap. It also has a real mitigation posture already, plus a defined roadmap to closing it further.

## Decision

Document the risk explicitly, name the mitigations already in place, and record the roadmap to closing the gap further. Do not attempt to eliminate the STIP gap unilaterally within the framework — full elimination requires card-processor event integration, which is institution- and vendor-specific.

The bounded double-spend exposure is the same risk institutions already accept for STIP itself: banks have accepted for decades that a maintenance window creates a temporary authorization gap on the card side, and their compliance and fraud programs already account for it. OpenFedNow does not create a new risk category — it participates in an existing one.

### Three mitigation layers exist today

Every outbound FedNow send during bridge mode passes through these three checks before authorization:

1. **Single-transfer amount cap.** `openfednow.fraud.max-single-transfer-amount` (default $25,000) bounds the maximum size of any single send. Rejection reason code `FRAD`. See ADR-0008.
2. **Debtor velocity check.** No more than `openfednow.fraud.velocity.max-per-window` sends (default 10) from the same debtor account in `openfednow.fraud.velocity.window-seconds` (default 60). Backed by an atomic Redis `INCR`+`EXPIRE`. Also `FRAD`.
3. **Reconciliation detection.** When the core comes back online, `ReconciliationService` replays the queued transactions in `applied_at` order and compares the Shadow Ledger balance against the core's ground truth. Any discrepancy — including one caused by a STIP debit that arrived while the framework was offline — is surfaced in the `reconciliation_run` record and logged with the account ID (masked to last 4 via `PiiRedactor`). The check runs unconditionally on every reconciliation cycle.

The first two prevent the exposure from being unbounded per transaction. The third guarantees the drift is observed and can be investigated the moment the core returns.

### Four-step roadmap to closing the gap further

Each step reduces the STIP gap incrementally. They are ordered by cost-to-implement and by how much they narrow the exposure:

1. **Kill switch — [issue #66](https://github.com/danielsmori/open-fednow/issues/66) — implemented in commit `284ec0b`.** `openfednow.bridge-mode.allow-sends` refuses outbound sends entirely while the core is offline. Zero exposure at the cost of zero send capability during the window. The `prod` profile defaults to `false`; production deployments explicitly opt in to bridge-mode send capability once they are confident.
2. **Aggregate cap — [issue #68](https://github.com/danielsmori/open-fednow/issues/68).** Bounds the *total* amount an account can send during a bridge-mode window (default disabled; institutions opt in with an amount). Complements the single-transfer cap by bounding sustained draining.
3. **Card authorization port — [issue #69](https://github.com/danielsmori/open-fednow/issues/69) — implemented in commit `e5da58d`.** `CardAuthorizationEventListener` is the seam through which an institution's card processor's real-time authorization stream feeds directly into the Shadow Ledger via `applyDebit`. Once wired, STIP authorizations propagate synchronously and the gap closes at the source. The framework ships the port; each institution supplies the adapter.
4. **Aggregate cap with staleness step-down — future.** The aggregate cap tightens automatically as the Shadow Ledger's last-sync age grows. Considered speculative today (see Prof. Angel review notes); revisit once real institutional operational data is available.

The most conservative deployment posture — kill switch on until the institution has operational confidence with reconciliation, aggregate cap on after that, card authorization port implemented once the card processor supports real-time events — gets progressively closer to zero exposure. The framework's contribution is providing the levers; the institution's contribution is choosing the setting.

### Configuration guidance

The following combinations describe the four canonical postures. Institutions choose based on their risk appetite:

| Posture | `bridge-mode.allow-sends` | Aggregate cap (#68) | `CardAuthorizationEventListener` (#69) | Residual exposure |
|---|---|---|---|---|
| **Receive-only** (recommended for first 90 days) | `false` | n/a | not wired | 0 (no sends during window) |
| **Bounded outbound** | `true` | opt-in, institution-chosen | not wired | ≤ aggregate-cap per account per window |
| **STIP-aware** | `true` | opt-in | wired to card processor | Approaches zero as card-event latency approaches zero |
| **Full trust** (not recommended) | `true` | disabled | not wired | Bounded only by the single-transfer cap and velocity check |

The `prod` profile of `application.yml` defaults to **receive-only**. Institutions explicitly override `BRIDGE_MODE_ALLOW_SENDS=true` when they are ready.

## Alternatives Considered

**Prevent the gap by prohibiting all bridge-mode operation.**
Return `TS01` (SystemUnavailable) to every incoming payment the moment the core goes offline. Zero double-spend exposure. Rejected because the entire point of the Shadow Ledger + AvailabilityBridge architecture is to keep the institution reachable on FedNow during maintenance windows. An 24/7 outage during every core maintenance window would defeat the purpose of the framework.

**Prevent the gap by requiring a real-time card-processor feed.**
Refuse to start the framework unless a `CardAuthorizationEventListener` implementation is registered. Rejected because most target institutions do not currently have card-processor event streaming available — the framework would become unusable for the majority of its intended audience. The card authorization port (#69) exists precisely as an opt-in improvement, not a prerequisite.

**Move the send path to two-phase settlement with a hold-and-confirm cycle.**
Reserve funds in the Shadow Ledger, hold the actual FedNow submission until the core confirms the reservation. Rejected because it re-introduces the synchronous core dependency that the SyncAsyncBridge was designed to eliminate; a slow or offline core would then block outbound sends entirely, forcing FedNow's 20-second SLA to be violated on the sender's side.

**Consortium fraud data / cross-institution velocity checks.**
Interesting but outside framework scope: consortium data requires a data-sharing agreement between institutions and is a commercial fraud-product concern, not a framework concern. `FraudScreeningPort` is the extension seam; an institution wiring in a consortium data source uses that seam.

## Consequences

**Positive:**
- The framework documents a real risk explicitly rather than leaving it as an implicit "maintenance windows are risky" caveat. Compliance teams can point at ADR-0010 in internal control documentation.
- The mitigation roadmap is grounded in configurable levers, not future work. Every step from receive-only to STIP-aware is available at the operator's choice.
- The `bridge-mode.allow-sends` default of `false` in the `prod` profile means the safest posture is the shipped default. Institutions opt in to send-during-window explicitly.
- The Shadow Ledger's discrepancy detection during reconciliation is unconditional — every drift, from every source, surfaces the next time the core comes back.

**Negative:**
- The gap is real. Even the most careful configuration (receive-only during first 90 days, then aggregate cap on, then card port wired) does not eliminate it — the residual exposure is bounded but nonzero until the card processor supports real-time event streaming.
- Institutions running without an aggregate cap and without the card authorization port are trusting the single-transfer cap + velocity check to bound their exposure. The math is on their side (bounded per transaction, bounded per minute) but the total per-window exposure grows with window length.
- Staleness step-down (#68's speculative extension) has no empirical basis today. Adding it prematurely would introduce configuration surface without evidence it improves outcomes.

## Related

- [ADR-0003](0003-provisional-acceptance-acsp.md) — why ACSP is returned during maintenance windows
- [ADR-0004](0004-eventual-consistency-shadow-ledger-and-core.md) — why eventual consistency was chosen over 2PC
- [ADR-0008](0008-fraud-screening.md) — the `FraudScreeningPort` and the default rule set that provides the single-transfer cap + velocity check
- [known-limitations.md](../known-limitations.md) item #4 — the top-level statement of maintenance-window risk this ADR expands on
- `AvailabilityBridge.java` — the bridge-mode entry/exit gate
- `ReconciliationService.java` — the discrepancy detection at core-return
- `CardAuthorizationEventListener.java` (introduced by PR #71 closing #69) — the extension seam for real-time STIP event feeds
- Issues [#66](https://github.com/danielsmori/open-fednow/issues/66) (kill switch), [#68](https://github.com/danielsmori/open-fednow/issues/68) (aggregate cap), [#69](https://github.com/danielsmori/open-fednow/issues/69) (card port) — the three roadmap items this ADR anchors
