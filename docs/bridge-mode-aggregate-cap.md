# Bridge-mode aggregate send cap: operator guide

This guide covers the per-account aggregate send cap added for [#68](https://github.com/danielsmori/open-fednow/issues/68) (pull requests #139, #140, #146, #148) and the automatic reconciliation it relies on. Like the rest of the legacy Redis Shadow Ledger path, it is a **sandbox control**. It bounds one kind of exposure in this reference implementation. It does not make sends during a core outage safe, and it does not establish anything about a live institution's funds. Read [ADR-0010](adr/0010-bridge-mode-fraud-risk.md) and [known limitations](known-limitations.md) first.

## What it bounds, and what it does not

While the core is offline ("bridge mode"), outbound FedNow sends on the legacy route are authorized against the cached Shadow Ledger balance. Card-processor STIP authorizations and other channels can spend the same money without updating that cache (the "STIP gap"). The single-transfer amount cap and the velocity check bound each send and the number of sends. They don't bound the **total** that can leave one account during the window.

The aggregate cap does bound that total. Once the counted sends for an account would exceed the configured ceiling, further sends are rejected with ISO 20022 `AM04` until the core is back **and** a reconciliation run has confirmed those sends against the core's balance. See [Leaving the window](#how-it-works) for what that confirmation does and does not mean.

It does **not**:

- **See other channels.** Card and teller activity is never counted. The cap limits what *this system* sends during a window, so worst-case exposure is the cap plus whatever other channels authorize.
- **Apply to the SQL reliability path.** It covers the legacy `MessageRouter.routeOutbound` path only. The reliability path refuses sends while the core is offline; enforcing the cap there before that changes is tracked in [#137](https://github.com/danielsmori/open-fednow/issues/137).
- **Enable sends on its own.** `openfednow.bridge-mode.allow-sends` is still `false` by default, and with it off, sends during an outage are rejected with `TS01` before the cap is consulted.
- **Run on the default route.** The legacy route it guards (`POST /fednow/send` through `MessageRouter.routeOutbound`) is reachable only in the sandbox. That requires `openfednow.legacy-outbound-sandbox-enabled=true` (default `false`), the sandbox FedNow client, and the reliability bridge (`openfednow.reliability.legacy-entry-bridge-enabled`) left off. Otherwise `/fednow/send` answers 503 or uses the SQL reliability path, and the cap never applies.

## How it works

**Counting.** A send counts toward the debtor's window in two cases:

- the core is offline when it is processed;
- an earlier window for that account is still open (the core is back, but reconciliation hasn't confirmed the earlier sends yet), because the cached balance is still unconfirmed.

The cap check, the balance debit and the count happen in one atomic Redis script. Under concurrency the counted total never exceeds the cap, and a send that loses a race gets a clean `AM04`, never an error.

**The window** is one Redis hash per account (`bridge:aggregate:<accountId>`). It holds the counted total, the time the window opened, and one field per counted send. It has **no TTL**: the cap fails closed and is never lifted by a timer.

**Leaving the window.** A send stops counting only when one of these happens:

- **Its debit is reversed.** For example, FedNow rejected it and the saga was compensated.
- **Reconciliation confirms it.** Its DEBIT row was confirmed by a reconciliation run while the core was online, **and** its saga, if one exists, is final (`COMPLETED` or `FAILED`). A send whose saga is not final stays counted.

"Confirmed" means only that reconciliation read the core's balance while the core was online and aligned the Shadow Ledger with it. The legacy path never posts outbound sends to the core ([known limitation #6](known-limitations.md#6-the-legacy-outbound-path-never-posts-sends-to-the-core)). If the core's balance doesn't include them, reconciliation sets the shadow balance back to the core's figure and lifts the cap, and the account can send its full balance again even though that money already left.

**Unknown outcomes never resolve on their own.** Every `ACSP` response and every timed-out or unanswered submission leaves the saga in `OUTCOME_UNKNOWN`. Nothing in the system moves an outbound saga out of that state; an audited resolution is tracked in [#98](https://github.com/danielsmori/open-fednow/issues/98). Until then, such a send keeps its window open until an operator uses the override (see runbooks).
- **An operator clears the window** with the audited override (see runbooks).

A window with no counted send left is deleted.

**Automatic reconciliation** releases accounts without an operator:

- **When a cycle runs.** Every instance polls core availability (`openfednow.shadow-ledger.reconciliation-poll-interval-seconds`, default 30). A durable Redis marker (`reconciliation:pending`) records that a reconciliation is owed. It is set on every poll that finds the core offline, on every inbound payment queued while the core is offline, and on capped sends counted in bridge mode or that open a new window. Any instance that finds the core online while the marker is set starts a cycle on a dedicated thread.
- **One cycle at a time.** A PostgreSQL advisory lock keeps it to one cycle across all instances.
- **When the marker clears.** Only after a cycle that reconciled every account and left no account capped. Otherwise the cycle is retried with exponential backoff (30 s up to 900 s), and new owed work is tried at once.
- **Concurrent payments are protected.** Each account's balance has a version that every write bumps (`balance-version:<accountId>`). A correction applies only if nothing was written since reconciliation read the balance. A busy account is retried up to 3 times, then left for the next cycle. Only ledger rows that were covered by that read are confirmed.

## Configuration

| Property (environment variable) | Default | Meaning |
| --- | --- | --- |
| `openfednow.bridge-mode.allow-sends` (`BRIDGE_MODE_ALLOW_SENDS`) | `false` | Allow outbound sends while the core is offline. Off: they get `TS01`. |
| `openfednow.bridge-mode.max-aggregate-send-amount` (`BRIDGE_MODE_MAX_AGGREGATE_SEND_AMOUNT`) | unset (disabled) | Ceiling in USD per account per window. There is deliberately no default: set a number the institution has reasoned about. It must be at least 0.01; startup fails otherwise. |
| `openfednow.bridge-mode.expected-window-hours` (`BRIDGE_MODE_EXPECTED_WINDOW_HOURS`) | `6` | Expected maintenance-window length. **Alert threshold only**: never lifts the cap. |
| `openfednow.bridge-mode.near-cap-threshold-percent` (`BRIDGE_MODE_NEAR_CAP_THRESHOLD_PERCENT`) | `80` | A window at or above this percentage of the cap counts as "near cap" in the gauge. |
| `openfednow.bridge-mode.aggregate-metrics-interval-seconds` (`BRIDGE_MODE_AGGREGATE_METRICS_INTERVAL_SECONDS`) | `60` | How often the window gauges are recomputed. |
| `openfednow.reconciliation.auto-on-core-recovery` (`RECONCILIATION_AUTO_ON_CORE_RECOVERY`) | `true` | Reconcile automatically when the core comes back. If off, nothing is marked and reconciliation is operator-driven only. |
| `openfednow.reconciliation.auto-retry-backoff-initial-seconds` / `-max-seconds` (`RECONCILIATION_AUTO_RETRY_BACKOFF_INITIAL_SECONDS` / `_MAX_SECONDS`) | `30` / `900` | Backoff bounds for cycles that don't settle. |
| `openfednow.shadow-ledger.reconciliation-poll-interval-seconds` (no variable; set the property) | `30` | Core-availability poll interval, which also drives the automatic trigger. |

## Metrics

**Collecting them.** The project ships no metrics exporter. Metrics are available through Spring Boot Actuator at `/actuator/metrics`, but the production profile exposes only `health` and `info`. To scrape them, add a Micrometer registry (for example `micrometer-registry-prometheus`) and expose its endpoint (`management.endpoints.web.exposure.include`).

**Aggregating them.** The window gauges are global: every instance scans Redis and reports the same counts, so aggregate them across instances with `max`, never `sum`. Each instance runs that scan, which walks the whole Redis keyspace, once per `aggregate-metrics-interval-seconds`. `reconciliation.auto.consecutive_unsettled` is per instance.

None of these carries an account tag: per-account series would be unbounded and would put account IDs in metrics.

| Metric | Type | What it shows |
| --- | --- | --- |
| `bridge_mode.aggregate.cap_exceeded` | counter | Sends rejected by the cap (`AM04`). |
| `bridge_mode.aggregate.pct_of_cap` | distribution (SLO buckets 50/80/90/100) | How full the debtor's window is after each counted send. |
| `bridge_mode.aggregate.windows_open` | gauge | Accounts with an open window. |
| `bridge_mode.aggregate.windows_near_cap` | gauge | Windows at or above the near-cap threshold. |
| `bridge_mode.aggregate.windows_overdue` | gauge | Windows open longer than `expected-window-hours`. |
| `bridge_mode.aggregate.oldest_window_age_seconds` | gauge | Age of the oldest open window. |
| `bridge_mode.aggregate.last_scan_epoch_seconds` | gauge | When the window gauges were last recomputed. A failed scan keeps the previous values, so alert if this stops advancing. |
| `bridge_mode.sends.blocked` | counter | Sends rejected with `TS01` because bridge-mode sends are disabled. |
| `reconciliation.auto.cycles{result=settled\|unsettled\|skipped\|error}` | counter | Automatic reconciliation cycles by outcome. `skipped` is normal on every instance but one. |
| `reconciliation.auto.consecutive_unsettled` | gauge | Cycles in a row on this instance that ran but didn't settle. Per instance. |

Suggested alerts:

- `windows_overdue > 0` sustained for longer than the retry backoff maximum (15 minutes by default) once maintenance is over. No metric reports core availability, so correlate with your maintenance schedule.
- `last_scan_epoch_seconds` older than a few scan intervals.
- `reconciliation.auto.consecutive_unsettled >= 3`.
- A rising `cap_exceeded` rate.
- `windows_near_cap` trending up during a window.

## Admin endpoints

All require the `ADMIN` role and are recorded in `admin_audit_log`.

| Endpoint | Purpose |
| --- | --- |
| `GET /admin/accounts/{accountId}/bridge-aggregate` | The account's open window: counted total, number of counted sends, when it opened. 404 if none. |
| `DELETE /admin/accounts/{accountId}/bridge-aggregate` | **Override**: delete the window, discarding every counted send and lifting the cap for the account. 204 if a window was cleared, 404 if none. |
| `POST /admin/reconcile` | Run a reconciliation cycle now. It takes the same cross-instance lock, so it is skipped if a cycle is already running on any instance. |

A reconciliation run is successful only if it found no discrepancy and no account was deferred or failed. The field is `reconciliationSuccessful` in the `POST /admin/reconcile` response and `successful` in `/admin/reconciliation-runs`. The summary says how many accounts were deferred, failed, or remain capped.

Because the core may not reflect outbound sends (known limitation #6), the run that releases windows after an outage usually finds a discrepancy and reports not successful. A run skipped because another cycle is already running reports successful with a "Skipped:" summary.

## Requirements when running it

These apply wherever the legacy route and the cap are run, including evaluation environments. They don't make the route suitable for live use; see [ADR-0010](adr/0010-bridge-mode-fraud-risk.md).


- **Redis must not evict and must persist.** The balance (`balance:`), balance version (`balance-version:`), window (`bridge:aggregate:`) and marker (`reconciliation:pending`) keys have no TTL. Use `maxmemory-policy noeviction` (or a `volatile-*` policy) and AOF persistence. If a window key is evicted or lost, that account's cap is silently lifted. If a version key is lost, a stale correction could apply.
- **Single Redis primary.** The scripts touch several keys of one account at once; Redis Cluster isn't supported.
- **PostgreSQL session pooling.** The reconciliation lock is a session-level advisory lock (`pg_try_advisory_lock`). Every instance must reach the same primary, and a pooler in transaction mode (PgBouncer `pool_mode=transaction`) breaks it. A running cycle holds one extra pooled connection.
- **Socket timeouts.** The lock's own connection has a 30 s network timeout. Also set a pgjdbc `socketTimeout` (and `tcpKeepAlive=true`) appropriate to your longest legitimate query, so a dead database connection fails a reconciliation cycle instead of blocking it. Leave room for Flyway migrations if they share the datasource.

## Runbooks

**An account stays capped after the core came back**

1. Check the window: `GET /admin/accounts/{id}/bridge-aggregate`.
2. Check `reconciliation.auto.cycles` and the latest run (`GET /admin/reconciliation-runs?limit=1`). If cycles are `unsettled`, the summary says whether accounts were deferred, failed or still capped.
3. Look for unresolved sends: `GET /admin/sagas` lists non-final sagas. A send in `OUTCOME_UNKNOWN` (every `ACSP` and every timed-out submission) keeps its window open by design.
4. Establish the payment's real outcome out of band. The system has no way to record that outcome on the saga yet ([#98](https://github.com/danielsmori/open-fednow/issues/98)), so reconciliation will never release the send by itself.
5. Once the account's position is established, release it with `DELETE /admin/accounts/{id}/bridge-aggregate`. The call is audited. This is currently the only way to release a window held open by an unknown outcome.

Until the override is used, the reconciliation marker stays set, automatic reconciliation keeps retrying at its maximum backoff, and `reconciliation.auto.consecutive_unsettled` keeps climbing. That is expected; it is the signal that the account needs attention.

**Reconciliation keeps reporting accounts deferred**

- **During an outage:** accounts with an open window are deferred until the core is online. This is expected.
- **With the core online:** a deferred account is *busy*; its balance changed during each of 3 attempts. It is retried on the next cycle. Persistent deferral of one account usually means continuous traffic on it.

**Redis outage**

- Sends fail, because the Shadow Ledger itself is unavailable.
- The trigger can't read its marker. It retries on every poll without inflating its backoff.
- After Redis returns, offline polls and new activity mark again.

## Known limitations

These are listed in [known limitations](known-limitations.md):

- **Inbound replay never happens.** Bridge-mode inbound payments are queued for replay to the core, but nothing consumes that queue ([#151](https://github.com/danielsmori/open-fednow/issues/151)).
- **Plain debits can return a 500.** With the cap disabled, a debit that loses a race returns a 500 ([#143](https://github.com/danielsmori/open-fednow/issues/143)).
- **Outbound sends are never posted to the core** by the legacy path. Reconciliation treats the core's balance as authoritative, so the shadow balance after reconciliation reflects outbound sends only if the core does. That depends on the institution's own FedNow settlement posting; the sandbox adapters don't do it.
- **Overlap is narrowed, not fenced.** If the reconciliation lock is lost mid-cycle, a second cycle could overlap until the first one notices. That takes up to about 15 seconds plus the slowest single account, longer if the database stops responding (the liveness check's own network timeout is 30 seconds), and is not bounded at all if PostgreSQL itself is slow to notice a dead session.
- **Short uncapped outages can go unmarked.** With the cap disabled, an outbound send during an outage shorter than one poll interval doesn't mark reconciliation as owed.
