# Bridge-mode aggregate send cap

Issue [#68](https://github.com/danielsmori/open-fednow/issues/68); delivered in [#139](https://github.com/danielsmori/open-fednow/pull/139), [#140](https://github.com/danielsmori/open-fednow/pull/140), [#146](https://github.com/danielsmori/open-fednow/pull/146), [#148](https://github.com/danielsmori/open-fednow/pull/148).

During a core maintenance window ("bridge mode"), outbound FedNow sends on the legacy sandbox path authorize against the cached Redis Shadow Ledger balance, while other channels — most importantly card-processor STIP — can spend against the same account without this software seeing it (the "STIP gap", [ADR-0010](adr/0010-bridge-mode-fraud-risk.md)). The per-transfer amount cap and the velocity check bound each send and the send count, but not the total that can leave one account in a window. The aggregate cap bounds that total, per account, to an amount the operator has explicitly chosen.

This is a sandbox risk control on the legacy Redis-backed path. It does not make bridge-mode sends production-safe, and it is not enforced on the [SQL reliability path](reliability/scope.md) — that path refuses sends while the core is offline, and [#137](https://github.com/danielsmori/open-fednow/issues/137)/[#138](https://github.com/danielsmori/open-fednow/issues/138) track enforcing the same policy there before that gate is ever relaxed. Also note [#150](https://github.com/danielsmori/open-fednow/issues/150): inbound bridge-mode payments are queued for core replay, but nothing consumes that queue yet.

## Semantics

The cap **fails closed**. Nothing resets it on a timer.

- A send **counts** toward its account's window when it is debited while the core is offline — and also while a window from an earlier outage is still open, because sends keep authorizing against the unconfirmed cached balance until reconciliation. The count and the debit are one atomic Redis operation; the aggregate can never exceed the cap, under any concurrency.
- A send that would exceed the cap is rejected with ISO 20022 `AM04`, recorded idempotently, and never submitted to FedNow.
- A send **leaves** the window in exactly two ways: its Shadow Ledger debit is reversed (FedNow rejection, saga timeout, compensation retry), or reconciliation confirms its DEBIT row against an online core **and** its saga is final (`COMPLETED`/`FAILED`). Sends whose rail outcome is unresolved (`SUBMITTING`, `OUTCOME_UNKNOWN`) stay counted until they resolve.
- A window whose last counted send leaves it closes; the account is then no longer capped.
- Reconciliation starts automatically when the core comes back ([#146](https://github.com/danielsmori/open-fednow/pull/146)): a durable Redis marker records that one is owed, a PostgreSQL advisory lock keeps it to one cycle across instances, and cycles that do not settle everything retry with exponential backoff.

## Configuration

| Property (under `openfednow.`) | Default | Meaning |
| --- | --- | --- |
| `bridge-mode.max-aggregate-send-amount` | unset (**disabled**) | The per-account ceiling in USD. Unset means no cap: operators must opt in with a number they have reasoned about rather than inherit a shipped one. Must be at least 0.01 and fit in cents. |
| `bridge-mode.expected-window-hours` | `6` | Alerting threshold only: a window open longer than this raises `windows_overdue`. It never resets the cap. |
| `bridge-mode.metrics-sample-interval-seconds` | `60` | How often the window gauges are sampled from Redis. |
| `bridge-mode.allow-sends` | `false` | The #66 kill switch. With it off, bridge-mode sends are rejected with `TS01` and the cap never engages. |
| `reconciliation.auto-on-core-recovery` | `true` | Automatic reconciliation on core recovery — the mechanism that releases capped accounts without an operator. |
| `reconciliation.auto-retry-backoff-initial-seconds` / `-max-seconds` | `30` / `900` | Retry backoff for cycles that did not settle the backlog. |

## Metrics

| Metric | Type | Meaning |
| --- | --- | --- |
| `bridge_mode.aggregate.pct_of_cap` | distribution summary | Counted aggregate as a percentage of the cap, observed at each counted send. No per-account tag (unbounded cardinality, and account ids do not belong in the metrics backend). |
| `bridge_mode.aggregate.cap_exceeded` | counter | Sends rejected by the cap. |
| `bridge_mode.aggregate.open_windows` | gauge | Accounts currently capped by an open window. |
| `bridge_mode.aggregate.windows_near_cap` | gauge | Open windows at 80% of the cap or more — the accounts about to be rejected. |
| `bridge_mode.aggregate.windows_overdue` | gauge | Windows open longer than `expected-window-hours`. A non-zero value that does not drain usually means a send stuck in `OUTCOME_UNKNOWN` — see the runbook below. |
| `bridge_mode.sends.blocked` | counter | Sends rejected by the `allow-sends` kill switch (`TS01`). |
| `reconciliation.auto.cycles{result}` | counter | Automatic cycles by result: `settled`, `unsettled`, `skipped`, `error`. |
| `reconciliation.auto.consecutive_unsettled` | gauge | Cycles in a row (on this instance) that ran but did not settle the backlog. |

## Operator endpoints and runbook

`GET /admin/accounts/{accountId}/bridge-aggregate` shows an account's open window: counted total, number of counted sends, and when it opened. `DELETE` on the same path is an audited override that discards the window and lifts the cap for that account.

**An account stays capped after the core is back** (`windows_overdue` > 0, or the GET shows an old window):

1. Check `reconciliation.auto.consecutive_unsettled` and the logs. If cycles are failing (core errors, lock contention), fix that first; the backoff retries on its own, and `POST /admin/reconcile` forces a cycle.
2. If cycles complete but the window stays, a counted send's saga is not final — typically `OUTCOME_UNKNOWN` after a lost FedNow response. Find it with `GET /admin/sagas` and the window's counted sends, and resolve it through investigation.
3. Once the saga is final, the next cycle (automatic or manual) releases the send.
4. Only if the position has been established out of band and the saga cannot be resolved in-system: `DELETE /admin/accounts/{accountId}/bridge-aggregate`. The call is recorded in the admin audit log.

A run of `/admin/reconcile` reports `successful=false` whenever any account was deferred (core offline, or a payment landed mid-reconciliation) or failed — not only on balance discrepancies. The summary says how many accounts were deferred, failed, or remain capped.

## Deployment requirements

- **Redis**: the window keys (`bridge:aggregate:<account>`), balance keys and balance-version keys (`balance-version:<account>`) carry no TTL and must never be evicted — use `noeviction` (or a `volatile-*` policy) and persistence (AOF). Losing a window fails open. A single Redis primary: the balance and window keys must live on one node; Redis Cluster is not supported (same constraint as the pre-existing WATCH-based debit).
- **PostgreSQL**: the reconciliation lock is a session-level advisory lock. It does not survive a connection pooler in transaction-pooling mode — PgBouncer must use session pooling, or connect directly. A running cycle holds one extra pooled connection.
- **Socket timeouts**: the lock's own connection has a 30s network timeout, but the cycle's per-account queries use ordinary pooled connections. Set pgjdbc `socketTimeout` (seconds; comfortably above your slowest legitimate query, including Flyway migrations if they share the datasource) and `tcpKeepAlive=true` via `spring.datasource.hikari.data-source-properties.*` so a silently dead database socket cannot stall a cycle indefinitely.
- **Alerting**: `windows_overdue` and `consecutive_unsettled` are the two signals that mean "an account is stuck capped"; `reconciliation_run.successful = FALSE` now also fires for deferred accounts and runs during outages.

## Known gaps

- The plain (cap-disabled) debit path can still answer 500 under contention: [#141](https://github.com/danielsmori/open-fednow/issues/141).
- Inbound bridge-mode payments are queued for core replay but nothing consumes the queue: [#150](https://github.com/danielsmori/open-fednow/issues/150).
- The SQL reliability path does not enforce the cap (it refuses offline sends instead): [#137](https://github.com/danielsmori/open-fednow/issues/137)/[#138](https://github.com/danielsmori/open-fednow/issues/138).
