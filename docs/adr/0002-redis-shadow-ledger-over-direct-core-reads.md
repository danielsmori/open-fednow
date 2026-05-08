# ADR-0002: Redis Shadow Ledger over Direct Core Balance Reads

## Status

Accepted

## Context

Every inbound FedNow payment requires a balance check before the credit transfer is accepted. FedNow's rules require a response within 20 seconds; in practice, a well-behaved participant responds in under 5 seconds. The question is: where does that balance check happen?

The straightforward answer is the core banking system — it holds the ledger of record. But this creates two problems.

**Problem 1: The core is not always available.** Legacy core banking systems take scheduled maintenance windows. A typical community bank core is offline 2–4 hours nightly and may have additional weekend windows. FedNow operates 24/7/365. If a balance check requires the core to be online, the institution cannot participate in FedNow during maintenance — which is precisely the adoption barrier this framework addresses.

**Problem 2: The core is not fast enough.** Legacy core systems process transactions in batch cycles. A synchronous balance inquiry may take 2–15 seconds depending on vendor, system load, and network path. At the upper end, that leaves no headroom for the rest of the payment flow before FedNow's 20-second window closes.

The options considered were:

1. **Direct core balance read** — Query the core banking system on every inbound payment.
2. **Redis-backed Shadow Ledger** — Maintain a continuously updated replica of relevant account balances in Redis. Serve balance checks from Redis; update it on every FedNow transaction.
3. **Database-backed Shadow Ledger** — Same concept but using PostgreSQL instead of Redis.
4. **Event-sourced account state** — Derive the current balance by replaying the transaction log on each request.

## Decision

Maintain account balances in **Redis** as integer cent values, updated on every FedNow debit and credit. The Shadow Ledger owns these balances and serves all balance checks independently of the core banking system.

Balance storage format: a Redis string key `balance:{accountId}` holding a 64-bit integer representing the balance in cents (e.g., `"5000000"` for $50,000.00).

**Why integer cents:** floating-point arithmetic on currency values produces rounding errors that compound across operations. Integer cents are exact. A balance of $12,345.67 is stored as `1234567`, which participates in exact arithmetic. Division back to dollars is performed only for display/logging.

The Shadow Ledger is kept consistent with the core through:
- Seeding from the core's current balances on framework startup
- Real-time updates on every FedNow debit (`applyDebit`) and credit (`applyCredit`)
- Reconciliation against core-confirmed balances after each maintenance window (`reconcile`)

When the core is offline, the Shadow Ledger is authoritative for balance decisions.

## Alternatives Considered

**Direct core balance read**

The option most consistent with the core as ledger of record. Rejected for two reasons: (1) the core is unavailable during maintenance windows, which is the primary scenario this framework must handle; (2) core balance inquiry latency is unpredictable and may consume the majority of FedNow's 20-second window, leaving no margin for the rest of the payment flow.

A hybrid approach — direct core read when available, Shadow Ledger fallback when not — was also considered. Rejected because it creates two code paths for the same operation, with different consistency properties. The Shadow Ledger approach is simpler: one path always, with the core remaining authoritative only through reconciliation.

**PostgreSQL-backed Shadow Ledger**

PostgreSQL provides ACID transactions, which would eliminate the need for Redis WATCH/MULTI/EXEC. However, PostgreSQL read latency (1–5ms per query under load, including connection pool overhead) is an order of magnitude slower than Redis (<0.5ms). For a balance check in a 20-second payment flow, the absolute numbers are small — but the rationale for a latency-optimised store is sound: the Shadow Ledger will be read on every single inbound payment, and Redis's in-memory access model is the right tool for that workload. PostgreSQL is used alongside Redis for the durable audit log (`shadow_ledger_transaction_log`), where write durability matters more than read latency.

**Event-sourced account state**

Derive the current balance by summing (or replaying) all FedNow transactions for the account from the PostgreSQL log. Correct in theory; impractical for a real-time balance check. An account with years of FedNow activity would require scanning hundreds of rows on every payment. Even with indexing, this cannot consistently deliver sub-millisecond balance reads. Rejected.

## Consequences

**Positive:**
- Sub-millisecond balance reads from Redis leave ample headroom within FedNow's 20-second response window.
- The Shadow Ledger operates independently of the core: balance checks and debit reservations work identically during maintenance windows.
- Integer cent storage eliminates floating-point rounding as a source of reconciliation discrepancies.
- Redis INCRBY (used by `applyCredit`) is atomically correct without any transaction overhead.

**Negative:**
- The Shadow Ledger introduces a second balance store alongside the core, which must be kept consistent. Reconciliation logic (`ReconciliationService`) is required to detect and correct drift. This adds architectural complexity.
- The Shadow Ledger covers only accounts that participate in FedNow transactions, not the full core ledger. An account that has never had a FedNow transaction will have no Redis key; `getAvailableBalance` returns zero for unknown accounts, which is a safe default for an outbound payment check (no funds reserved = no payment) but requires explicit seeding on onboarding.
- Redis is an in-memory store. A Redis failure (without replication/persistence) would lose all Shadow Ledger balances. For production deployments, Redis Sentinel or Redis Cluster with AOF persistence is required. The framework assumes a durable Redis deployment; the `AvailabilityBridge` falls back to conservative mode (no new outbound payments accepted) on Redis unavailability.

## Related

- `ShadowLedger.java` — balance read/write implementation
- `AvailabilityBridge.java` — maintenance window detection and fallback behaviour
- `ReconciliationService.java` — consistency restoration after core return
- [ADR-0001](0001-optimistic-locking-shadow-ledger-debits.md) — how concurrent debits are made safe
- [ADR-0004](0004-eventual-consistency-shadow-ledger-and-core.md) — the consistency model this design relies on
- [shadow-ledger.md](../shadow-ledger.md) — operational detail of the Shadow Ledger
