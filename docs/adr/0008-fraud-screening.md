# ADR-0008: Fraud pre-screening — port-based design with a configurable default

## Status

Accepted

## Context

The payment pipeline previously had no fraud-screening step. Inbound and outbound credit transfers flowed from validation directly into balance reservation and FedNow / TCH submission, with no opportunity to apply rule-based or threshold-based checks before funds were committed. Several places in the documentation referenced fraud screening as a Layer 1 responsibility, but no implementation existed.

The framework needs to do two things at once:

1. **Ship a sensible default.** A reference deployment should catch the obvious cases — an account on a known-bad list, a single transfer well above the institution's normal size, a debtor sending an unrealistic number of transfers per minute. These are the rules an operator can stand up immediately without integrating an external system.
2. **Not pretend to be a fraud product.** Real fraud detection at production scale uses ML models, behavioral profiles, hosted scoring services, and commercial rules engines. The framework should not lock institutions into the reference implementation; institutions with mature fraud programs need a clean way to plug in their own.

## Decision

Introduce a narrow port — `FraudScreeningPort` with a single method `ScreeningResult screen(Pacs008Message)` — and a default rule-based implementation behind a feature flag, with a no-op fallback so the wiring works regardless of whether anyone has opted into the default rules.

### Decision shape: three outcomes, not two

`ScreeningResult.Decision` has three values: `PASS`, `REVIEW`, `BLOCK`. Real fraud signals are fuzzy and operations teams need a way to flag a payment for follow-up without halting it. `REVIEW` is the channel: the payment proceeds, the `fraud.reviewed` Micrometer counter increments, and a WARN log line lets dashboards surface it. Only `BLOCK` short-circuits the request with a pacs.002 RJCT.

### Default rule set

`DefaultFraudScreeningService` applies four rules in priority order:

1. **Account denylist** — debtor or creditor on the configured denylist → BLOCK. Highest priority because a known-bad account should not be allowed to skate past the other rules.
2. **Amount cap** — `interbankSettlementAmount > openfednow.fraud.max-single-transfer-amount` (default $25,000) → BLOCK.
3. **Debtor velocity** — more than `openfednow.fraud.velocity.max-per-window` transfers from the same debtor account within `openfednow.fraud.velocity.window-seconds` (defaults 10 / 60) → BLOCK. Backed by Redis `INCR` + `EXPIRE` so the cost is constant per call.
4. **Elevated-amount review** — amount at or above 50% of the cap, but under it → REVIEW.

The rules are deliberately simple. They are the kind of thing an operator would expect to be enforced out of the box, not the kind of thing that requires ML training data.

### Activation: opt-in feature flag

The default service registers as a Spring bean only when `openfednow.fraud.enabled=true`. The fallback `NoOpFraudScreeningService` activates via `@ConditionalOnMissingBean` — every payment receives `PASS` until an institution opts in.

This matches the rest of the framework: a fresh deployment runs in an unscreened sandbox, an operator turns rules on when they're ready. It also means an institution can register its own implementation without touching the default at all; `@ConditionalOnMissingBean` will silently step aside.

### Placement in the pipeline

`MessageRouter` invokes the port between the idempotency check and saga initiation — for both `routeInbound` and `routeOutbound`. The decision is:

- **Before saga creation.** A BLOCK must not leave a saga in `INITIATED` that recovery later has to clean up. The port runs after the idempotency check (a duplicate is a duplicate even if it would block on fraud rules — return the cached pacs.002) and before any persistent side effect.
- **Before the sufficient-funds check on outbound.** An outright fraud signal shouldn't get masked as "insufficient funds" when the real reason is upstream.

### Response shape

BLOCK returns HTTP 200 with a pacs.002 RJCT carrying reason code `FRAD` (ISO 20022 *Fraudulent origin*). The HTTP status is 200 because the framework received a valid message; the rejection lives in the pacs.002 payload, consistent with how every other rejection (insufficient funds, account closed, core unavailable) is signaled.

The issue text suggested 402 or 422. Rejected because mixing HTTP-level rejection with pacs.002-level rejection would force callers to handle two different shapes for the same failure mode. One shape is simpler and matches the existing protocol.

## Alternatives Considered

**Hard-coded rules with no extensibility seam.**
Smallest change. Rejected because the framework's value at scale depends on institutions being able to integrate their own fraud programs. Locking everyone into the default rules would be wrong.

**Mandatory external service integration (no default).**
Force every deployment to wire in a hosted fraud scoring service. Rejected because the reference framework needs to run end-to-end out of the box without external dependencies. The four rules in the default service cover enough of the obvious cases to be operationally useful for sandbox and small deployments.

**Asynchronous fraud screening with provisional acceptance.**
Return ACSP immediately; resolve the fraud check in the background; send a pacs.004 return if it later fails. Rejected for the synchronous default path because: (a) the same shape would be needed regardless of async wrapper; (b) ACSP-then-return creates customer-visible reversals that the architecture explicitly tries to minimize (see ADR-0003, ADR-0004); (c) most fraud screens are sub-100ms decisions on hot data — the synchronous path is fine.

**Two-decision model (PASS / BLOCK only).**
Simpler enum. Rejected because operations teams have repeatedly asked for a "flag but don't block" channel. REVIEW is what makes that visible without making the screen overly cautious.

## Consequences

**Positive:**
- The port is narrow enough that swapping the implementation requires no router or message-model changes.
- Default rules are operationally useful out of the box. The Redis-backed velocity check is constant-time per call.
- The feature is opt-in, so the existing test suite and CI continue to work without configuration changes.
- Both rails (FedNow + RTP) and both directions (inbound + outbound) share the same screen call — one port covers all four paths.

**Negative:**
- The four default rules are useful but not sufficient for production at scale. The README and known-limitations both note that institutions are expected to provide their own implementation for production deployments.
- Velocity check is keyed on debtor account only. A sophisticated attacker spreading transactions across multiple debtor accounts would bypass it. Per-creditor and aggregate velocity would be reasonable additions but are not in scope here.
- REVIEW decisions are visible only via logs and metrics. There is no dedicated `fraud_audit_log` table or admin endpoint listing reviewed payments. Tracked as potential future work — the existing `admin_audit_log` is purpose-built for admin endpoint access, not for fraud signals.

## Related

- `FraudScreeningPort.java` — the interface
- `ScreeningResult.java` — the decision + reason record
- `DefaultFraudScreeningService.java` — reference rules (denylist, cap, velocity, review)
- `NoOpFraudScreeningService.java` — default-active fallback
- `MessageRouter.routeInbound` / `routeOutbound` — call sites
- [ADR-0003](0003-provisional-acceptance-acsp.md) and [ADR-0004](0004-eventual-consistency-shadow-ledger-and-core.md) — why synchronous fraud rejection is preferred over async ACSP-then-reverse
