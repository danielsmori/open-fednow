# ADR-0010: Cached-balance and other-channel spending risk

## Status

Accepted as a **sandbox risk record**. It does not approve outbound bridge-mode sends for a live institution. See [known limitations](../known-limitations.md) and the [current capability matrix](../capability-matrix.md).

## Context

The legacy Redis Shadow Ledger can hold a balance while a core-shaped fixture is unavailable. Other channels, such as cards, teller or ACH, may debit an institution account without updating that cache. A payment decision based only on the cached amount can therefore overspend the true available funds. The Redis update, PostgreSQL audit write, core posting and rail effect also do not share one transaction. The local ACSP-shaped response has not been validated as a live FedNow response.

## Decision

`openfednow.bridge-mode.allow-sends` defaults to `false`; the legacy `/fednow/send` route is separately disabled by default. The opt-in fraud rules can cap a transfer and count velocity, but they do **not** establish authoritative all-channel funds availability. `CardAuthorizationEventListener` is an extension seam whose default implementation is a no-op; it is not a verified card-processor integration. Reconciliation can detect some local/core differences after the fact, but it does not make an earlier send safe or guarantee that all external authorizations were observed.

The newer SQL reliability path refuses a synthetic send unless its fixture declares exclusive control over debiting channels. That flag is useful for fault testing, not proof of a bank-wide reservation. The non-production `/fednow/send` bridge uses this SQL service without calling a vendor adapter. A live adaptation would require an institution to verify reservation ownership, lookup/expiry, posting identity, outcome lookup and other-channel coordination. [The adapter capability contract](../reliability/adapter-capabilities-v1.md) records those missing guarantees.

## Consequences

Receive-side maintenance-window behavior remains an experiment requiring rail-status and core review. Outbound sends stay disabled by default. No national exposure amount, institution risk acceptance, outside technical review, or production mitigation result is inferred from this repository.
