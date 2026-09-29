# Synthetic evaluation driver contract v1

Targets are separate HTTP processes. A driver maps target-specific routes to `seed(account, amount_minor, exclusive)`, `external_debit`, `submit(payment)`, `lookup(operation_id)`, `effects(operation_id)`, `account(account_id)`, and `make_inquiry_due(operation_id)`. Scenario definitions and the oracle live in this harness, outside OpenFedNow Java classes. The rail simulator is a separate process with a persistent SQLite database and observable `/payments/{messageId}` and `/events` endpoints.

The JSON payload is a test fixture shaped like a small pacs.008 subset. The rail JSON `/submit` and `/payments` routes are **not** FedNow transports or pacs.028 mappings. `SYNTHETIC_RAIL` is an author-built source label, not a Federal Reserve authenticated status. The driver must not map a missing inquiry result to rejection.

The current OpenFedNow driver needs a non-production run with PostgreSQL, Redis and RabbitMQ; `openfednow.reliability.synthetic-rail-url` must point at the local simulator and `openfednow.reliability.fixture-api-enabled=true` enables the protected fixture routes. Both the payment and fixture routes require admin Basic auth. The fixture API is absent under the `prod` profile. Each test uses unique synthetic identifiers and accounts.

Manifest entries identify the observable invariants. A runner failure or unavailable target is reported as an execution failure, never a successful defect detection. Unimplemented scenarios must be listed as unsupported in the evaluation report; this seven-case harness subset does not satisfy the complete S01–S18 plan.
