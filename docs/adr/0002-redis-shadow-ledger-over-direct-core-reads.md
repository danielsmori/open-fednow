# ADR-0002: Redis balance cache in the legacy reference design

## Status

Historical design decision for a sandbox maintenance-window experiment. The cache is not an authority for live cross-channel funds availability.

## Decision

The original design stores a core-shaped balance snapshot in Redis and applies local payment adjustments there. This makes it possible to test a flow while the configured core fixture is unavailable. `BalanceSeedService` seeds configured accounts; `ReconciliationService` later compares local records with a core-shaped balance. The repo has not measured a real core's latency, availability or freshness against Redis, so no throughput or response-time advantage is claimed.

## Consequences

A card, ACH, teller or other debit may change the bank's available funds without changing the cache. Redis and PostgreSQL writes can diverge on failure. A local reconciliation result does not retroactively make a prior payment decision safe. Legacy outbound sends are disabled by default; [ADR-0010](0010-bridge-mode-fraud-risk.md) describes the risk. The newer [SQL reliability slice](../reliability/architecture.md) is a distinct synthetic account model requiring its fixture's exclusive-channel control; it is not a bank-core replacement.
