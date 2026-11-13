# Component status

This page lists every component in the repository and what has been implemented and tested. It was moved from the README so that the README can lead with how to run the tests. For the limits of these results, see [reliability scope](reliability/scope.md), [known limitations](known-limitations.md) and the [capability matrix](capability-matrix.md).

| Component | Status |
|-----------|--------|
| Five-layer architecture | ✅ Implemented |
| ISO 20022 message models (pacs.008, pacs.002, pacs.004, camt.056/029) | ✅ Implemented |
| Cancellation handling — inbound camt.056 → camt.029 with state-keyed decision matrix | ✅ Implemented on both rails; CNCL reverses Shadow Ledger credit and terminates saga. See [ADR-0007](adr/0007-camt056-cancellation-lifecycle.md) |
| Fraud pre-screening — `FraudScreeningPort` with rule-based default (amount cap, debtor velocity, denylist) | ✅ Disabled by default; opt-in via `openfednow.fraud.enabled=true`. BLOCK returns RJCT before financial effects; screening timeout/error returns TS01 by default. Institutions swap in their own port for production. See [ADR-0008](adr/0008-fraud-screening.md) |
| SandboxAdapter — all scenarios: ACSC, RJCT, ACSP, timeout | ✅ Implemented |
| MockVendorAdapter — in-memory balance ledger, configurable failure modes | ✅ Implemented; `CoreBankingAdapterContractTest` enforces adapter contract |
| `CoreBankingAdapter` contract | ✅ Implemented |
| Shadow Ledger — Redis-backed, WATCH/MULTI/EXEC optimistic locking | ✅ Implemented + tested |
| Shadow Ledger endpoint wiring (inbound + outbound) | ✅ Implemented |
| Maintenance-window bridge fixture | ✅ Local queue/reconciliation behavior tested; continuous live availability unverified |
| Reconciliation — local replay and comparison after fixture core returns | ✅ Implemented and tested with synthetic dependencies |
| Reconciliation pagination — keyset-paginated account scan | ✅ Configurable batch size (default 500); institution-scale memory behavior not benchmarked |
| Saga orchestration — compensation on core rejection | ✅ Implemented + tested |
| Idempotency — Redis + PostgreSQL dual-write, 48h window | ✅ Implemented + tested in legacy sandbox; cross-store effect atomicity unproven |
| Local Redis concurrent debit guard | ✅ Race tests cover watched-key updates; cross-channel overdraft prevention unverified |
| Send-side (outbound) payment flow | SQL synthetic reference slice and opt-in non-production `/fednow/send` bridge implemented; legacy Redis/SQL gateway path disabled by default; no live rail/core authorization established |
| Payment returns (pacs.004 outbound) | Synthetic client shape exists, but `/fednow/return` is disabled by default; no durable return outcome lifecycle is established. HTTP return submission makes one attempt and propagates uncertainty. |
| Admin auth — HTTP Basic on `/admin/*` | ✅ Implemented as reference configuration |
| Admin audit log — every `/admin/**` access recorded to PostgreSQL | ✅ Implemented; both GRANTED and DENIED captured, surfaced via `GET /admin/audit-log`. Sensitive query parameters (`token`, `apikey`, `password`, …) are rewritten to `REDACTED` before persistence by `PiiRedactor` |
| PII redaction in structured logs | ✅ Account numbers masked to last 4 in `MessageRouter` insufficient-funds log, `ShadowLedger` / `ReconciliationService` discrepancy log; single policy source in `io.openfednow.security.pii.PiiRedactor` |
| Rotatable admin credentials — file-backed source | ✅ Set `openfednow.admin.credential-file` to a two-line file; re-read on every login (mtime-cached). A K8s Secret mount can be rotated in place without a pod restart |
| Bridge-mode send policy — receive-only on-ramp | ✅ `openfednow.bridge-mode.allow-sends` (default `false` in every shipped profile). When off, outbound sends during a maintenance window are rejected with ISO 20022 `TS01` (SystemUnavailable) and increment `bridge_mode.sends.blocked` |
| Bridge-mode aggregate send cap — fail-closed per-account ceiling | ✅ `openfednow.bridge-mode.max-aggregate-send-amount` (default unset = disabled) bounds the total one account can send against the cached balance per maintenance window; counted atomically with the debit, rejected with ISO 20022 `AM04`, released only on reversal or core-confirmed reconciliation with a final rail outcome. Metrics, runbook and deployment requirements in [bridge-mode-aggregate-cap.md](bridge-mode-aggregate-cap.md) |
| Currency guard — reject non-USD at ingress | ✅ Inbound and outbound sends in a currency the rail cannot settle (currently USD-only for both FedNow and RTP) are rejected with ISO 20022 `AM03` before any saga init, fraud screen, or ledger touch |
| Correlate saga_state ↔ admin_audit_log via request-id | ✅ V7 migration adds `request_id` to `saga_state` and `reconciliation_run`; `SagaOrchestrator.initiate` and `ReconciliationService.reconcile` stamp `MDC[requestId]` so any admin-initiated saga is one JOIN from its audit row |
| Admin query endpoints — saga state, balances, reconciliation history | ✅ `GET /admin/sagas[/{txId}]`, `/admin/accounts/{id}/balance`, `/admin/reconciliation-runs[/{id}]`, `/admin/audit-log` |
| Saga recovery on application restart | ✅ `ApplicationReadyEvent` listener; unknown legacy rail outcomes remain quarantined for review |
| Saga timeout monitor — auto-compensate stalled sagas | ✅ `@Scheduled` sweep; ISO 20022 `XPIR` reason; `saga.timeout` Micrometer counter |
| Balance seeding from core on startup | ✅ Configurable account list seeded via SETNX; `POST /admin/shadow-ledger/seed` for on-demand re-seed |
| Idempotency cleanup — scheduled sweep of expired Postgres rows | ✅ Configurable TTL (default 48h) and sweep cadence (default 60min) |
| Rate limiting — per-client on `/fednow/**` and `/rtp/**` POSTs | ✅ Resilience4j `RateLimiter` per IP / X-Forwarded-For; 429 + Retry-After; `gateway.rate_limited` counter |
| Saga compensation retry — sweeps FAILED sagas with missing REVERSAL rows | ✅ `@Scheduled` retry; idempotent reversal primitives; `saga.compensation.retry.succeeded` and `.failed` counters |
| Admin audit log retention — scheduled cleanup of `admin_audit_log` | ✅ Configurable retention (default 365 days) and sweep cadence |
| Transactional boundaries — multi-statement writes commit atomically | ✅ `@Transactional` on `SagaOrchestrator.compensate` / `cancelInboundSaga`; `TransactionTemplate`-wrapped per-account reconciliation |
| Idempotent Shadow Ledger reversals — safe under retry | ✅ `reverseDebit` and `reverseCredit` skip on existing REVERSAL row; one-call semantics from any caller |
| Default-credential startup guard | ✅ `@PostConstruct` check refuses to start the `prod` profile if admin credentials are still the sandbox defaults |
| HTTP security headers — HSTS, X-Content-Type-Options, X-Frame-Options, Cache-Control | ✅ Configured in `SecurityConfig`; verified end-to-end via `SecurityHeadersTest` |
| CORS — deny-by-default for server-to-server API | ✅ Explicit empty `CorsConfigurationSource`; institutions registering a browser-origin allow-list override the bean |
| Graceful shutdown — drain in-flight requests on SIGTERM | ✅ `server.shutdown=graceful` + 30s drain window; Helm `terminationGracePeriodSeconds: 60` |
| HikariCP pool configuration | ✅ Pool 50 / min-idle 10 / 5s connection timeout in the production profile; no capacity benchmark or live sizing validation |
| Outbound FedNow transfer uncertainty | ✅ Legacy credit transfer attempts once and quarantines a missing status; the SQL reference slice adds durable holds and inquiry. Return submissions attempt once but have no durable lifecycle, so the return gateway stays disabled. |
| Fraud screening timeout — hard cap on port calls | ✅ `CompletableFuture` deadline (default 1500ms); rejects on timeout / exception by default |
| Atomic velocity counter — single Redis Lua script | ✅ `INCR` + `EXPIRE` in one round-trip; sliding window matching the documented semantic |
| Reconcile concurrency guard — in-JVM and cross-instance | ✅ `ReentrantLock` with tryLock inside one JVM, plus a PostgreSQL session-level advisory lock across instances (released by the database if the holder dies); a second concurrent call returns a "Skipped" report rather than racing. Cycles start automatically when the core-shaped adapter reports availability again |
| Saga source-rail tracking — dual-rail dispatch foundation | ✅ `source_rail` column on `saga_state` (V5); both gateways thread `Rail` through `MessageRouter` |
| Dependency scanning — Dependabot + Trivy | ✅ Weekly Maven + Actions updates; Trivy scan fails the build on HIGH/CRITICAL findings |
| CI — unit + integration test jobs | ✅ Unit and Testcontainers-backed integration jobs passed for [merged PR #87](https://github.com/danielsmori/open-fednow/pull/87); the reliability slice is now on `main` |
| Dual-rail inbound reference routing | ✅ Shared legacy router with persisted source rail; RTP outbound disabled and live rail parity unverified |
| RTP Layer 1 — inbound XML, outbound XML, TCH cert validation hook, sandbox + HTTP client | Inbound reference routing and transport utilities implemented; `/rtp/send` disabled pending financial-control parity |
| Optional Kafka event bus — `PaymentEventPublisher`, 6 event types | ✅ Implemented (disabled by default; no Kafka required) |
| Kafka publish DLQ — failed publishes routed to a dead-letter topic | ✅ `<topic>.dlq` (configurable) with `X-DLQ-Original-Topic` + `X-DLQ-Reason` headers; `events.publish.failed` / `events.publish.dlq_failed` counters |
| Event schema versioning — `schemaVersion` field + `X-Schema-Version` / `X-Event-Type` headers | ✅ Implemented; JSON Schema in `docs/event-schemas/`; strategy documented in [ADR-0006](adr/0006-event-schema-versioning.md) |
| Vendor-shaped adapters (Fiserv, FIS, Jack Henry) | Local REST/JSON or SOAP construction, authentication and response-mapping tests against WireMock fixtures. No vendor-environment or product-compatibility validation; see [capability contract](reliability/adapter-capabilities-v1.md). |
| Banking Hub Premier read-only adapter | [Premier assessment](integrations/fiserv-banking-hub.md) and a fail-closed [capability assessment](integrations/fiserv-capability-assessment.md). A separate Java adapter authenticated to the shared Trial sandbox and read Fiserv's published sample DDA; available funds and all money mutations remain unsupported. No Banking Hub send integration has been performed. |
| TigerBeetle S16 portability experiment | Opt-in [local Python target mode](reliability/tigerbeetle-s16.md) uses a pinned TigerBeetle development ledger for synthetic reservations, postings and independent effect reads. The unchanged S16 profile and separate rail simulator evaluate it; production and vendor paths are unaffected. |
| S02 ledger and rail comparison | The [reproducible local comparison](reliability/s02-ledger-rail-comparison.md) evaluates a status-only check, an independent effect comparator, and the portable evaluator against synthetic Java/PostgreSQL and Python/SQLite targets. |
| FedNow JWS message signing — outbound RS256 detached signature + inbound verification | ✅ Implemented per RFC 7515 + RFC 7797 with `b64=false`; opt-in via `openfednow.fednow.signing.enabled=true`. See [ADR-0009](adr/0009-fednow-jws-message-signing.md) |
| Live FedNow connectivity (Fed PKI, mTLS) | 🔲 Credential/certification-dependent; simulator-compatible HTTP client implemented |
| Live RTP connectivity (TCH network, TCH PKI certificates) | 🔲 TCH onboarding, applicable specifications and certification required; local XML and HTTP utilities do not establish a live pipeline |

See [docs/known-limitations.md](known-limitations.md) for the full gap analysis.
