# Roadmap and development history

This page records the development phases completed so far and the open work. It was moved from the README.

**Phase 1 — Core reference framework implemented**
- Five-layer architecture: Shadow Ledger, SyncAsyncBridge, Saga orchestration, idempotency, reconciliation
- ISO 20022 message models — pacs.008 / pacs.002 / pacs.004 / camt.056 / camt.029
- `MockVendorAdapter` + `CoreBankingAdapterContractTest` baseline; sandbox scenario routing

**Phase 2 — Fiserv + FIS reference adapters implemented; vendor validation open**
- Fiserv and FIS reference adapter request/response shapes, tested locally with mocks; institution-specific products and compatibility remain unverified

**Phase 3 — Jack Henry reference adapter implemented; vendor validation open**
- Jack Henry jXchange-shaped reference SOAP adapter, locally tested; product-specific compatibility remains unverified
- Three vendor-shaped reference adapters implemented; the repo does not establish coverage of any percentage of U.S. institutions

**Phase 3b — RTP reference transport utilities implemented; live validation open**
- RTP reference transport utilities (outbound gateway initiation disabled): `RtpXmlParser`, `RtpXmlSerializer`, `RtpClient` with sandbox + HTTP implementations, TCH certificate-validation hook
- `RtpGateway` inbound XML route and outbound transport utilities present; `/rtp/send` remains disabled ([ADR-0005](adr/0005-dual-rail-architecture-fednow-rtp.md))

**Phase 4 — Sandbox operational tooling implemented; institution validation open**
- Saga lifecycle: source-rail tracking, restart-time recovery, timeout monitor with `XPIR` compensation, compensation retry for failed reversals
- Admin endpoints: saga state queries, account balance views, reconciliation history, audit log; all under HTTP Basic + `ADMIN` role
- Admin access auditing with retention sweep; idempotency TTL cleanup; balance seeding from core on startup
- Cancellation handling — camt.056 → camt.029 with state-keyed decision matrix ([ADR-0007](adr/0007-camt056-cancellation-lifecycle.md))
- Fraud screening port with rule-based default ([ADR-0008](adr/0008-fraud-screening.md))
- Per-client rate limiting on `/fednow/**` and `/rtp/**`; reconciliation pagination for large institutions
- Event schema versioning ([ADR-0006](adr/0006-event-schema-versioning.md))

**Phase 5 — Reference hardening controls implemented; production validation open**
- Transactional boundaries on multi-statement writes; idempotent Shadow Ledger reversals; atomic Lua velocity counter; same-JVM reconcile concurrency guard
- HSTS, deny-by-default CORS, default-credential startup guard in prod profile
- Graceful shutdown with bounded drain window; configurable HikariCP pool (no FedNow capacity benchmark)
- Outbound FedNow credit transfers make one HTTP attempt and quarantine unknown outcomes; hard timeout on `FraudScreeningPort` calls (fail-closed by default; explicit fail-open option)
- Dependabot + Trivy workflow; GitHub Actions CI with both unit and integration test jobs

**Phase 6 — Reference signing components implemented; live profile unverified**
- RS256 detached JWS message signing implemented per RFC 7515 + RFC 7797 ([ADR-0009](adr/0009-fednow-jws-message-signing.md))
- Outbound: `FedNowJwsSigner` + RestTemplate interceptor attaches a locally defined `X-JWS-Signature` when enabled
- Inbound: `JwsInboundVerificationFilter` verifies locally shaped signatures when enabled
- Opt-in via `openfednow.fednow.signing.enabled=true`; sandbox / demo flow unchanged
- Live mapping also requires applicable restricted message and transport specifications, status authority verification, inquiry behavior, institutional controls, and certification; a certificate and URL alone are insufficient.

**Phase 7 — Synthetic SQL reliability evaluation merged in [PR #87](https://github.com/danielsmori/open-fednow/pull/87)**
- SQL-scoped operation ownership, holds, possible-send intent, inquiry and final effect tracking for synthetic accounts
- Controlled non-production bridge from `/fednow/send`; legacy Redis/SQL and return paths remain disabled by default
- External scenario oracle across four configurations, with separate fault, restart and two-process cases; [measured report](reliability/evaluation-report.md) and [evidence bundle](reliability/evidence/candidate-2026-09-29/README.md)

**Open work**
- Live FedNow / RTP connectivity (institutional credentials — see Production Boundaries)
- Cross-process, cross-store and other-channel funds correctness for the legacy Shadow Ledger ([#40](https://github.com/danielsmori/open-fednow/issues/40))
- Distributed tracing across MDC, RabbitMQ, and Kafka boundaries
- Operational runbooks for the standard incident-response scenarios
