# ADR-0001: Redis optimistic locking for the legacy Shadow Ledger

## Status

Accepted for the sandbox implementation. It does not establish end-to-end payment or cross-channel funds correctness.

## Decision

`ShadowLedger.applyDebit` checks and changes a Redis balance using WATCH/MULTI/EXEC and bounded retries. WATCH detects writes from other clients to the watched key, including other application processes using the same Redis instance. It can prevent two local debits from both acting on one unchanged cached balance. On repeated contention, the operation can fail rather than overspend the local value.

The subsequent PostgreSQL audit row is a separate write. Neither WATCH nor a distributed lock makes the Redis balance, SQL record, core effect and rail effect one transaction. Other channels can also debit the institution account without changing this cached balance. See [Redis transaction documentation](https://redis.io/docs/latest/develop/using-commands/transactions/), [known limitations](../known-limitations.md), and [ADR-0010](0010-bridge-mode-fraud-risk.md).

## Update: atomic scripts for capped debits ([#68](https://github.com/danielsmori/open-fednow/issues/68))

The capped debit added for the bridge-mode aggregate cap is a single atomic Lua script, not a WATCH retry loop. A loop can run out of retries after a payment's saga already exists, turning a lost race into an error instead of a clean rejection. Reconciliation's balance correction is also a script. It is a compare-and-set on a per-account balance version that every balance write bumps in the same atomic step ([#144](https://github.com/danielsmori/open-fednow/issues/144)). Concurrent WATCH-based writers still see script writes to the balance key, so both approaches coexist. Moving the plain `applyDebit` to a script as well is tracked in [#143](https://github.com/danielsmori/open-fednow/issues/143).

## Consequences

Local concurrency tests support the implementation under their schedules only. The old Redis/SQL outbound gateway is disabled by default. The newer [SQL reliability slice](../reliability/architecture.md) uses separate synthetic SQL accounts and holds; it does not convert legacy Redis obligations or validate a real core.
