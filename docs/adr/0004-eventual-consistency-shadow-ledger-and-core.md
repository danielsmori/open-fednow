# ADR-0004: Redis/core divergence in the legacy sandbox

## Status

Historical design decision. Temporary divergence is **observed risk**, not an accepted live-payment guarantee. See [known limitations](../known-limitations.md).

## Context and decision

The legacy Shadow Ledger changes a Redis balance while saga/audit data is written to PostgreSQL and the core-shaped adapter may have a separate effect. Those updates do not form one atomic transaction. The sandbox permits local state to diverge and runs `ReconciliationService` to compare with a core-shaped balance later. It uses per-account SQL transactions for its records and a same-JVM lock to avoid overlapping local runs; neither gives distributed atomicity or proves the adapter's balance is authoritative.

A cached balance may also be stale because card, ACH, teller or other channels can spend the same funds. Replaying or overwriting local state after the fact cannot justify a prior unsafe send. Therefore legacy outbound effects are disabled by default. The separate SQL reliability path uses synthetic exclusive-control accounts and records holds and final financial effects transactionally in PostgreSQL, but it is not a verified institution core. See [ADR-0010](0010-bridge-mode-fraud-risk.md) and the [reliability architecture](../reliability/architecture.md).

## Consequences

Reconciliation reports describe the local fixture run; they do not certify convergence to a bank's ledger or a live rail outcome. Historical ambiguous obligations remain unresolved until separately verified.
