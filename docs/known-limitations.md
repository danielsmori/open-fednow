# Known Limitations

This document catalogs the genuine limitations of the OpenFedNow framework — both architectural trade-offs that are intentional and implementation gaps that are not yet built. The distinction matters: an architectural trade-off has a documented reason and an accepted consequence; an implementation gap is work that is simply not done yet.

---

## Architecture-Level Limitations

These are deliberate design choices with known trade-offs. They are not bugs.

### 1. Post-reconciliation reversals are customer-visible

**What happens:** If the core banking system rejects a transaction that was provisionally accepted during a maintenance window (for example, because the account was frozen or the funds were seized between when the Shadow Ledger reserved them and when the core came back online), the compensation path sends a pacs.004 return payment to FedNow. The sender's institution receives a credit — and then receives a return of that credit.

**Why this exists:** The architecture accepts ACSP (provisional acceptance) before the core has confirmed. This is the correct ISO 20022 response for this scenario and within FedNow's rules, but it creates a bounded window of financial exposure.

**Consequence:** Depending on regulatory context and the reason for rejection, the institution may be required to notify the sender, the sender's institution, or both. The specific notification requirements depend on the rejection reason code (e.g., account seizure vs. technical failure) and the institution's compliance obligations under Regulation J and its FedNow participation agreement.

**Related:** [ADR-0003](adr/0003-provisional-acceptance-acsp.md), [ADR-0004](adr/0004-eventual-consistency-shadow-ledger-and-core.md)

---

### 2. Single middleware instance assumption

**What happens:** The Shadow Ledger's optimistic locking (`WATCH`/`MULTI`/`EXEC` on Redis) is correct for a single middleware deployment. Under concurrent load on a single instance, WATCH conflicts cause retries (up to `MAX_RETRY_ATTEMPTS = 3`) and the balance always converges to the correct value.

**Why this is a limit:** In a horizontally scaled deployment (multiple middleware pods behind a load balancer), two instances could both pass a balance check and both proceed to decrement the same balance within the same WATCH cycle. Redis WATCH is per-connection — it does not coordinate across connections from different pods. This would require either:
- A Redis distributed lock (Redlock) per account before the WATCH/MULTI/EXEC block
- Routing all operations for a given account to the same pod (consistent hashing)
- Replacing WATCH/MULTI/EXEC with a Lua script, which is atomic but has the same single-shard constraint in Redis Cluster

**Current behavior:** Safe and correct for a single pod. Scaling to multiple pods without one of the above mitigations introduces a race condition on balance decrements.

---

### 3. Reconciliation ordering depends on NTP accuracy

**What happens:** During reconciliation, queued transactions are replayed against the core in `applied_at` timestamp order. Out-of-order replay would post transactions against the wrong intermediate balances (e.g., a $500 withdrawal replayed before the $600 deposit it depends on).

**Why this depends on NTP:** The `applied_at` timestamps are assigned by the middleware using the system clock. If the system clock drifts backward (clock slew, NTP correction), two transactions in the same maintenance window could receive timestamps that do not reflect their true causal order.

**Mitigation in place:** The reconciliation replay queries by `applied_at ASC`, which is correct assuming monotonically increasing timestamps. The middleware does not generate synthetic sequence numbers or vector clocks.

**Risk:** A clock slew large enough to reverse two transactions during a maintenance window would produce incorrect intermediate balances during replay. This is unlikely (NTP slew rate is typically bounded to 500ppm) but not impossible in a misconfigured or VM-heavy environment.

---

### 4. ACSP during maintenance window is a deliberate departure from "core is authoritative"

**What happens:** During a maintenance window, the institution returns ACSP to FedNow before the core has seen the transaction. The Shadow Ledger is the decision-maker for that window. This is architecturally necessary — but it is not zero-risk.

**The bounded risk:** The Shadow Ledger's balance is initialized from the core and updated atomically on each transaction. The only source of divergence is a core-side rejection of a provisionally accepted payment (see limitation #1 above). All other sources of balance drift are prevented by design.

**Why this is documented:** Compliance teams at regulated institutions should understand that for the duration of a maintenance window (typically 2–4 hours), the core is not the ledger of record. The Shadow Ledger is. This is disclosed in [ADR-0004](adr/0004-eventual-consistency-shadow-ledger-and-core.md) and should be disclosed in the institution's internal control documentation.

---

## Implementation Gaps

These are features that are not yet built. They are explicitly tracked as future work.

### 5. No remaining implementation gaps for the three primary vendors

`FiservAdapter`, `FisAdapter`, and `JackHenryAdapter` are implemented and tested in reference mode. Production use is credential-, endpoint-, and certification-dependent. Each uses OAuth 2.0 and maps vendor rejection codes to ISO 20022.

| Adapter | Protocol | Auth | Tests |
|---------|----------|------|-------|
| `FiservAdapter` | REST/JSON | OAuth 2.0 (form body) | WireMock, 12 tests |
| `FisAdapter` | REST/JSON | OAuth 2.0 (Basic auth header) | WireMock, 11 tests |
| `JackHenryAdapter` | SOAP/XML (jXchange) | OAuth 2.0 (Basic auth header) | WireMock, 12 tests |

`MockVendorAdapter` is a functional reference implementation — it provides a full in-memory balance ledger and configurable failure modes (timeout simulation, core availability toggle), and is activated via `openfednow.adapter=mock`. It is suitable for development and contract-testing, but is not a substitute for a real vendor integration.

`CoreBankingAdapterContractTest` defines the shared behavioral contract. `SandboxAdapterContractTest`, `MockVendorAdapterContractTest`, and `JackHenryAdapterContractTest` extend it. Fiserv and FIS are covered by dedicated WireMock integration tests.

**Production deployment note:** Each adapter requires institution-specific credentials (OAuth client ID, client secret, and base URL) obtained from the respective vendor. The `JackHenryAdapter` additionally requires an institution routing ID (9-digit ABA number) used as `InstRtId` in the jXchange SOAP header. The adapter interface (`CoreBankingAdapter`) is the only contract point — the rest of the framework does not need to change.

---

### 8. Live FedNow connectivity requires institution-provided credentials

`FedNowClient` is an interface with two implementations:

- **`SandboxFedNowClient`** — active by default (when `FEDNOW_ENDPOINT` is not set). Returns synthetic in-memory responses for local development and testing.
- **`HttpFedNowClient`** — activated when `FEDNOW_ENDPOINT` is set. Provides HTTP transport with retry-on-transient-failures + optional JWS detached message signing (activate via `openfednow.fednow.signing.enabled=true`; see [ADR-0009](adr/0009-fednow-jws-message-signing.md)). Live FedNow production additionally requires Federal Reserve PKI client certificates and mutual TLS. These are institution-provided credentials and are outside the scope of the framework.

The `FedNowClientConfig` bean is conditional: `HttpFedNowClient` is only created when `openfednow.gateway.fednow-endpoint` is present. When that property is absent, `SandboxFedNowClient` activates via `@ConditionalOnMissingBean`. This means `mvn spring-boot:run` without any environment variables uses the sandbox client throughout.

---

### 9. Live RTP connectivity requires institution-provided credentials

`RtpClient` is an interface with two implementations, symmetric with `FedNowClient`:

- **`SandboxRtpClient`** — active by default (when `RTP_ENDPOINT` is not set). Returns synthetic in-memory responses for local development and testing.
- **`HttpRtpClient`** — activated when `RTP_ENDPOINT` is set. Serializes pacs.008 to canonical ISO 20022 XML via `RtpXmlSerializer` and POSTs to the configured TCH endpoint. Live RTP production additionally requires TCH PKI client certificates, a dedicated private-network connection to the TCH RTP® network, and institutional participation. These are institution-provided credentials and are outside the scope of the framework.

`RtpClientConfig` is conditional: `HttpRtpClient` is only created when `openfednow.gateway.rtp-endpoint` is present. When absent, `SandboxRtpClient` activates via `@ConditionalOnMissingBean`. The TCH certificate-validation hook is invoked on every request via `CertificateManager.validateTchClientCertificate()`; in sandbox mode (no `TCH_TRUSTSTORE_PATH`) it is a no-op.

Layer 1 (inbound XML parsing, outbound XML serialization, certificate-validation hook, conditional HTTP transport) is implemented and tested in reference mode and is symmetric with the FedNow rail at the framework level. Layers 2–4 require no changes — they are rail-agnostic. See [rtp-compatibility.md](rtp-compatibility.md) and [ADR-0005](adr/0005-dual-rail-architecture-fednow-rtp.md).

Inbound source rail (FedNow vs. RTP) is now persisted on every `saga_state` row via the `source_rail` column (V5 migration). Asynchronous response paths can therefore dispatch through the correct gateway. The wiring for those asynchronous paths — reconciliation-time pacs.002 notifications and compensation-time pacs.004 returns to the originating rail — is still pending.

---

### 10. Outbound camt.056 is not wired

Inbound cancellation handling is now implemented — see "Cancellation handling" below in the operational capabilities section. The remaining gap is **outbound** cancellation: the institution wanting to recall its own payment via a camt.056 it constructs and submits to FedNow. `Camt056Message.forPaymentCancellation()` builds a valid message, but there is no admin endpoint, no `FedNowClient.submitCancellation()` method, and no async tracking of the resulting camt.029. Tracked as future work.

---

### Kafka event bus (optional, available)

An optional Kafka event bus is available via the `PaymentEventPublisher` interface. When Kafka is configured, `KafkaPaymentEventPublisher` is activated; otherwise `NoOpPaymentEventPublisher` is used by default. Six event types are published: `INBOUND_CREDIT_APPLIED`, `INBOUND_PAYMENT_REJECTED`, `INBOUND_QUEUED_FOR_BRIDGE`, `OUTBOUND_PAYMENT_COMPLETED`, `OUTBOUND_PAYMENT_REJECTED`, and `OUTBOUND_PAYMENT_PENDING`. No Kafka dependency is required to run the framework.

---

## Operational capabilities now available

These are implemented and worth calling out so deployers know what they get without further engineering.

### Saga lifecycle resilience

- **Saga recovery on restart.** `SagaRecoveryService` listens to `ApplicationReadyEvent` and dispatches every non-terminal saga to a terminal state on startup. `INITIATED` / `FUNDS_RESERVED` / `CORE_SUBMITTED` sagas are compensated (reason `NARR`); `FEDNOW_CONFIRMED` advances to `COMPLETED`; `COMPENSATING` is finalized to `FAILED` preserving the original reason code.
- **Saga timeout monitor.** `SagaTimeoutMonitor` runs on a fixed schedule (`openfednow.saga.timeout-check-interval-seconds`, default 10) and compensates any saga in a forward-progress state whose `created_at` is older than `openfednow.saga.timeout-seconds` (default 30). Reason code `XPIR` (ISO 20022 Expired) distinguishes timeout-driven failures from restart-recovery failures. Each timeout increments the `saga.timeout` Micrometer counter visible at `/actuator/metrics`.

### Operator endpoints under `/admin`

All `/admin/**` endpoints require HTTP Basic with the `ADMIN` role (`SecurityConfig`). All access — granted, denied, rejected, or error — is recorded in `admin_audit_log` by `AdminAccessAuditFilter`.

| Endpoint | Purpose |
| --- | --- |
| `POST /admin/reconcile` and `POST /admin/reconciliation-runs` | Trigger a manual reconciliation cycle (resource-style alias) |
| `GET /admin/reconciliation-runs[?limit=&offset=]` | Paginated reconciliation history; cap 200 per page |
| `GET /admin/reconciliation-runs/{runId}` | Single reconciliation run; 404 if missing |
| `GET /admin/sagas` | List non-terminal sagas (oldest first) |
| `GET /admin/sagas/{transactionId}` | Saga snapshot by ISO 20022 transaction ID |
| `GET /admin/accounts/{accountId}/balance` | Live Redis balance + unconfirmed-DEBIT total + last transaction timestamp |
| `POST /admin/shadow-ledger/seed` | Re-seed configured accounts from the core (unconditional overwrite) |
| `GET /admin/audit-log[?limit=&offset=]` | Paginated admin access history; cap 500 per page |

### Throughput controls

- **Rate limiting** on `POST /fednow/**` and `POST /rtp/**` via `RateLimitFilter`. Per-client buckets keyed on `X-Forwarded-For` (first hop) or remote address, refreshed every second. Default 100 transfers/sec/client (`openfednow.rate-limit.transfers-per-second`). Rejected requests get HTTP 429 with `Retry-After: 1` and increment `gateway.rate_limited`.
- **Idempotency record retention** is configurable via `openfednow.idempotency.ttl-hours` (default 48); `IdempotencyCleanupService` sweeps expired rows on `openfednow.idempotency.cleanup-interval-minutes` (default 60).

### Balance seeding

`BalanceSeedService` reads `openfednow.shadow-ledger.seed-accounts` (comma-separated). On `ApplicationReadyEvent` it loads each account's balance from the core adapter into Redis using `SETNX` so a recycled middleware pod never clobbers live balances. The on-demand `POST /admin/shadow-ledger/seed` endpoint uses unconditional overwrite for explicit resyncs.

### Fraud pre-screening

`FraudScreeningPort` is the extensibility seam — one method, one structured result, called by `MessageRouter` on every inbound and outbound credit transfer before any side effects.

The default implementation (`DefaultFraudScreeningService`) is opt-in via `openfednow.fraud.enabled=true` and applies four rules in priority order: account denylist (BLOCK), amount cap (BLOCK), debtor velocity via Redis `INCR` + `EXPIRE` (BLOCK), and elevated-amount review at ≥ 50% of the cap (REVIEW — proceeds but flagged in `fraud.reviewed` counter and WARN log). When disabled, `NoOpFraudScreeningService` passes everything through.

Institutions with mature fraud programs are expected to replace the default implementation with their own port implementation calling into a hosted scoring service or ML model. The contract is intentionally narrow so swapping doesn't touch router or message-model code. See [ADR-0008](adr/0008-fraud-screening.md) for the full decision and consequences.

### Cancellation handling (camt.056 / camt.029)

`CancellationService` handles inbound camt.056 cancellation requests on both rails. `POST /fednow/cancellation` and `POST /rtp/cancellation` accept the camt.056 and return the camt.029 synchronously. Decision logic is keyed on the saga state of the original payment:

- INITIATED / FUNDS_RESERVED → CNCL (Shadow Ledger credit reversed if applied; saga moves to FAILED carrying the camt.056 reason code)
- CORE_SUBMITTED / COMPENSATING → PDCR (outcome can't be determined synchronously; operator follow-up may be needed)
- FEDNOW_CONFIRMED / COMPLETED → RJCR / ARDT (settlement is committed; originator must use pacs.004 return)
- FAILED or no saga → RJCR / NOOR

The full decision matrix and design tradeoffs are documented in [ADR-0007](adr/0007-camt056-cancellation-lifecycle.md).

---

## Local Development Limitations

These are behaviors that differ between the local H2-backed development setup and a production PostgreSQL deployment.

### 12. H2 in-memory does not persist across restarts

Transaction records written to `shadow_ledger_transaction_log`, `saga_state`, and `idempotency_keys` are lost when the application restarts. In a PostgreSQL deployment, these records survive restarts, which means:

- Bridge-mode transactions accumulate in `shadow_ledger_transaction_log` during an offline window
- After the core returns and the app restarts, `ReconciliationService.reconcile()` finds and replays them
- `transactionsReplayed` in the reconciliation report reflects the actual backlog

In local development with H2, `transactionsReplayed` is always 0 after a restart because the table was reset.

---

### 13. Redis has no replication or persistence in docker-compose

The `docker-compose.yml` starts a single Redis node with no replicas and no AOF/RDB persistence configured. If Redis is stopped and restarted between a maintenance window and reconciliation, all Shadow Ledger balance entries are lost — the reconciliation would read zero balances from Redis and flag discrepancies for every account.

For production, Redis should be deployed with AOF persistence enabled (`--appendonly yes`) and at minimum one replica.

---

## Related

- [ADR-0001](adr/0001-optimistic-locking-shadow-ledger-debits.md) — why WATCH/MULTI/EXEC (and its single-instance constraint)
- [ADR-0003](adr/0003-provisional-acceptance-acsp.md) — why ACSP is returned, and the exposure window
- [ADR-0004](adr/0004-eventual-consistency-shadow-ledger-and-core.md) — why eventual consistency was chosen over 2PC
- [shadow-ledger.md](shadow-ledger.md) — Shadow Ledger operational detail and failure modes
- [saga-pattern.md](saga-pattern.md) — compensation path for post-reconciliation core rejections
