# ADR-0010: Cached-balance and other-channel spending risk

## Status

Accepted as a **sandbox risk record**. It does not approve outbound bridge-mode sends for a live institution. See [known limitations](../known-limitations.md) and the [current capability matrix](../capability-matrix.md).

## Context

The legacy Redis Shadow Ledger can hold a balance while a core-shaped fixture is unavailable. Other channels, such as cards, teller or ACH, may debit an institution account without updating that cache. A payment decision based only on the cached amount can therefore overspend the true available funds. The Redis update, PostgreSQL audit write, core posting and rail effect also do not share one transaction. The local ACSP-shaped response has not been validated as a live FedNow response.

## Decision

`openfednow.bridge-mode.allow-sends` defaults to `false`; the legacy `/fednow/send` route is separately disabled by default. The opt-in fraud rules can cap a transfer and count velocity, but they do **not** establish authoritative all-channel funds availability. `CardAuthorizationEventListener` is an extension seam whose default implementation is a no-op; it is not a verified card-processor integration. Reconciliation can detect some local/core differences after the fact, but it does not make an earlier send safe or guarantee that all external authorizations were observed.

The newer SQL reliability path refuses a synthetic send unless its fixture declares exclusive control over debiting channels. That flag is useful for fault testing, not proof of a bank-wide reservation. The non-production `/fednow/send` bridge uses this SQL service without calling a vendor adapter. A live adaptation would require an institution to verify reservation ownership, lookup/expiry, posting identity, outcome lookup and other-channel coordination. [The adapter capability contract](../reliability/adapter-capabilities-v1.md) records those missing guarantees.

## Update: per-account aggregate send cap ([#68](https://github.com/danielsmori/open-fednow/issues/68))

The legacy path now has an optional per-account aggregate send cap. When bridge-mode sends are enabled, it bounds the total this system sends from one account against the cached balance before reconciliation confirms those sends. A send that would exceed it gets `AM04`. Decisions:

- **No default ceiling.** `openfednow.bridge-mode.max-aggregate-send-amount` is unset by default. A shipped number would become a de facto risk acceptance for anyone who deploys without reading the docs.
- **Fail closed.** Nothing lifts the cap on a timer. A counted send stops counting when its debit is reversed, or when reconciliation, with the core online, has confirmed it and its saga, if one exists, is final. `expected-window-hours` only raises an alert. An outbound saga with an unknown outcome (every ACSP, every timeout) never becomes final on its own, because no resolution mechanism exists yet ([#98](https://github.com/danielsmori/open-fednow/issues/98)). An audited admin override is currently the only release for those windows.
- **"Confirmed" is weaker than it sounds.** Reconciliation aligns the Shadow Ledger with the core's balance. The legacy path never posts outbound sends to the core ([known limitations](../known-limitations.md) #6), so confirmation can lift the cap on money the core has not yet accounted for.
- **Reconciliation runs automatically** when the core returns, one cycle at a time across instances. A fail-closed cap would otherwise leave accounts capped until an operator acted.

The cap does not see card, teller or ACH activity, so it bounds only this system's share of the exposure. It covers the legacy route only; the SQL reliability path is [#137](https://github.com/danielsmori/open-fednow/issues/137). It does not change this record's status: no outbound bridge-mode send is approved for a live institution. Operating details are in the [operator guide](../bridge-mode-aggregate-cap.md).

## Consequences

Receive-side maintenance-window behavior remains an experiment requiring rail-status and core review. Outbound sends stay disabled by default. No national exposure amount, institution risk acceptance, outside technical review, or production mitigation result is inferred from this repository.
