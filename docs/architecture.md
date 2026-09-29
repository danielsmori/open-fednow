# Architecture overview

OpenFedNow is a synthetic reference implementation. The original five-layer diagrams describe a design exploration; they do not establish a deployed bank integration, live FedNow connection, vendor certification, or continuous funds availability. The currently testable reliability slice is described in [the reliability architecture](reliability/architecture.md), with its [scope](reliability/scope.md) and [evaluation](reliability/evaluation-report.md).

## Implemented paths and authority

The legacy gateway, router, core-adapter interface, saga, Redis Shadow Ledger, PostgreSQL records, and optional event publishing exercise message and recovery shapes in a sandbox. Redis balance changes, SQL saga/audit writes, core effects, and rail effects are separate transactions. They cannot be treated as one atomic payment. A timeout can leave the remote outcome unknown. Legacy `/fednow/send`, `/fednow/return`, and outbound RTP are disabled by default; sandbox flags are for controlled demonstrations only. The receive-side bridge's ACSP-shaped response is also a synthetic experiment, not a verified live FedNow provisional status.

The additive `/reference/v1/payments` slice uses PostgreSQL as the authority for a **synthetic** account, scoped payment claim, hold, status event, and financial effect. A committed possible-send intent precedes the simulator call. After that boundary, recovery uses inquiry and never blindly resubmits. A correlated synthetic settlement posts the hold once; a correlated rejection releases it once. Receiver posting is recorded separately from service settlement. Its two core fixture modes test synchronous reservation and asynchronous acknowledgment/lookup. Neither fixture demonstrates reservation across a real institution's other spending channels. An account without an enforceable exclusive-channel reservation is refused.

The external evaluation harness runs a separate SQLite-backed rail simulator and checks observable payment state, account balances, effect rows, and simulator event counts. It also drives an independent Python/SQLite reference target. All targets, statuses, clocks, and faults are author-built synthetic fixtures. The [scenario register](reliability/requirements.csv) identifies full, partial, and unsupported coverage.

## Integration boundaries

A live payment integration would require applicable restricted FedNow message, security, transport, and inquiry specifications; participant access and certification; authenticated status authority; verified core balance freshness and all-channel reservations; durable return and event-delivery handling; and institution-specific operational controls. A configured HTTP URL or certificate is insufficient. Vendor-shaped Fiserv, FIS, and Jack Henry adapters have local mock/WireMock tests only and are not verified against vendor environments.

Optional Kafka publication and RabbitMQ queueing exist in the legacy experiment, but the new reference slice does not publish payment events and has no transactional outbox claim. The repo does not use Temporal for its sagas. Container and Helm files are deployment examples, not evidence of a production installation or throughput capacity. See [known limitations](known-limitations.md) before adapting any path.
