# Saga and uncertain outcomes in the legacy reference flow

The older gateway/router records saga state in PostgreSQL while the Shadow Ledger mutates Redis and a synthetic core or rail may have a separate effect. Those systems do not share one transaction. Compensation can reverse a known local effect, but it cannot establish that an external payment was rejected or undo a confirmed settlement.

For a credit-transfer attempt, the legacy saga persists `SUBMITTING` before calling the rail-shaped client. If the response is lost, it records `OUTCOME_UNKNOWN`, retains the debit and prevents an automatic second send or timeout compensation. Startup recovery quarantines interrupted `SUBMITTING` records. An operator can inspect `/admin/sagas/{transactionId}`; the legacy route has no authoritative status inquiry or verified resolution action and is disabled by default.

The separate [SQL reliability service](reliability/architecture.md) makes its synthetic hold, effect row and final state atomic in PostgreSQL, records possible-send intent before the remote call, and reconciles an unknown result through a separate local simulator. Its controlled `/fednow/send` bridge is non-production. Historical legacy obligations are not converted into synthetic SQL outcomes. Returns remain a separate unsupported durable workflow; `/fednow/return` is disabled by default.

See the [scenario register](reliability/requirements.csv) and [evaluation report](reliability/evaluation-report.md) for tested crash boundaries and remaining gaps.
