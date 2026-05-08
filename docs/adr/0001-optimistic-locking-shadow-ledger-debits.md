# ADR-0001: Optimistic Locking for Shadow Ledger Debits

## Status

Accepted

## Context

FedNow is a push-credit network: a sending institution initiates a payment to a receiving institution. OpenFedNow acts as the receiving institution's middleware. Multiple inbound pacs.008 messages may arrive concurrently — FedNow does not serialize payments to the same creditor account, and a busy institution may receive dozens of simultaneous credit transfers.

Each inbound payment requires a balance check and debit reservation in the Shadow Ledger before the core banking system is contacted. These two operations — read balance, conditionally subtract — must be atomic. Without atomicity, two concurrent payments totalling $1,000 could both pass a $600 balance check, resulting in $1,200 of reservations against a $600 balance: an overdraft in the Shadow Ledger.

Redis is the Shadow Ledger's storage layer (see [ADR-0002](0002-redis-shadow-ledger-over-direct-core-reads.md)). The question is how to make the read-check-write sequence atomic.

The options considered were:

1. **Optimistic locking via WATCH/MULTI/EXEC** — Redis's built-in transaction mechanism. WATCH marks a key; if any watched key is modified between WATCH and EXEC, the transaction is discarded (EXEC returns nil). The caller retries.
2. **Pessimistic locking via distributed mutex** — Acquire a per-account lock (e.g., `SETNX lock:{accountId}`) before reading, release after writing. No retries needed; other readers block.
3. **Lua script** — Encode the read-check-write logic in a Lua script executed atomically on the Redis server.
4. **Single-threaded queue per account** — Route all operations for a given account through a dedicated queue, eliminating concurrency entirely.

## Decision

Use Redis **WATCH/MULTI/EXEC** with a maximum of **3 retry attempts** per debit operation.

The implementation in `ShadowLedger.applyDebit()`:

```
WATCH balance:{accountId}
GET   balance:{accountId}    ← read current balance (outside MULTI)
if balance < amount → UNWATCH, throw IllegalArgumentException
MULTI
  SET balance:{accountId} {balance - amount}
EXEC                          ← returns nil on conflict, list on success
```

If EXEC returns an empty result (key was modified between WATCH and EXEC), the operation retries from the beginning. After 3 failed attempts, an `IllegalStateException` is thrown and the saga enters compensation.

## Alternatives Considered

**Pessimistic locking (distributed mutex)**

`SETNX lock:{accountId} {ttl}` before any read, `DEL` after write. This works, but introduces blocking: a slow holder (e.g., paused by GC) forces all other threads for that account to wait. Under FedNow's 20-second response window, a stuck lock on a high-traffic account is a liability. Lock TTL is also difficult to tune: too short and a slow holder's lock expires mid-write, too long and a crashed holder blocks the account until expiry. Rejected because it introduces coordinated blocking into a latency-sensitive path.

**Lua script**

A Lua script executes atomically on the Redis server — no WATCH needed. The script would read the balance, check it, and conditionally update in one round trip. The downside is that Redis executes Lua scripts on a single thread; a slow or large script blocks all other Redis operations for that client. For a simple read-check-write, Lua adds deployment complexity (script management, versioning) with no meaningful advantage over WATCH/MULTI/EXEC. Rejected in favour of the simpler, standard approach.

**Single-threaded queue per account**

Route all balance operations for account X through a dedicated thread or actor, eliminating concurrency at the cost of throughput. This limits parallelism to the number of active accounts and adds significant architectural complexity (queue provisioning, backpressure handling, failure recovery per account). Rejected as over-engineered for the contention levels expected in practice.

## Consequences

**Positive:**
- No blocking: threads that conflict retry immediately rather than waiting for a lock holder. Under low-to-moderate concurrency, retries are rare.
- No external coordination: WATCH/MULTI/EXEC is a single connection's contract with the Redis server, not a distributed protocol. No ZooKeeper, no Consul, no lock manager.
- Overdraft prevention is guaranteed: if EXEC succeeds, no concurrent write occurred between the balance read and the update.
- Standard: WATCH/MULTI/EXEC is the idiomatic Redis approach and is well understood by engineers familiar with Redis.

**Negative:**
- Under high concurrent contention on the same account (many simultaneous debits), retry exhaustion (`IllegalStateException`) is possible. The 3-retry limit is a safety valve; in practice, contention must be extreme for all 3 attempts to conflict. If this becomes a production issue, the retry count can be increased or account-level serialization introduced for specific high-traffic accounts.
- The Lettuce Redis client (used by Spring Boot 3.x) returns an empty list rather than `null` when EXEC is discarded. The success check must be `txResult != null && !txResult.isEmpty()`. Using only `txResult != null` silently treats a conflicted transaction as successful.
- Retry logic adds complexity to the `applyDebit` implementation compared to a simple SET. The `capturedBalance` array workaround (to share state between the WATCH phase and the post-EXEC logging) is a Java-specific awkwardness of the `SessionCallback` API.

## Related

- `ShadowLedger.java` — `applyDebit()` implementation
- [ADR-0002](0002-redis-shadow-ledger-over-direct-core-reads.md) — why Redis is used as the balance store
- [ADR-0004](0004-eventual-consistency-shadow-ledger-and-core.md) — consistency model between Shadow Ledger and core
