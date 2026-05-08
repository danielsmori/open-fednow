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

### 5. Vendor adapters are skeleton implementations

`FiservAdapter`, `FisAdapter`, and `JackHenryAdapter` exist and implement the `CoreBankingAdapter` interface, but all methods contain `// TODO` stubs. None of them make real calls to vendor APIs. The `SandboxAdapter` is the only functional adapter.

A production deployment requires implementing the vendor-specific HTTP or MQ integration in the appropriate adapter. The adapter interface (`CoreBankingAdapter`) is the only contract point — the rest of the framework does not need to change.

---

### 6. Shadow Ledger debit/credit not wired into the HTTP payment endpoint

The `ShadowLedger.applyDebit()` and `applyCredit()` methods are fully implemented and tested at the service layer (`ShadowLedgerConcurrencyTest`, `ShadowLedgerIntegrationTest`). However, `MessageRouter.routeInbound()` does not call them — the HTTP payment endpoint does not update the Shadow Ledger balance or write to `shadow_ledger_transaction_log`.

**Practical effect:** In the local Quick Start demo, the reconciliation endpoint returns `transactionsReplayed: 0` because there are no unconfirmed ledger entries. The reconciliation path is correctly exercised in `BridgeModeIntegrationTest` and `ReconciliationServiceIntegrationTest` via direct service calls.

Wiring this requires calling `shadowLedger.applyCredit()` for inbound payments in bridge mode, and `shadowLedger.applyDebit()` for outbound payments (fund reservations). This is the next implementation milestone.

---

### 7. Send-side (outbound) payment flow is not implemented

The framework handles the receive side: an inbound pacs.008 from FedNow arrives, the institution processes it, and a pacs.002 is returned. The send side — where the institution initiates a FedNow credit transfer to another institution — is not implemented. `MessageRouter.routeOutbound()` exists as a method but has no complete pipeline.

---

### 8. FedNowClient is a stub

`FedNowClient` exists as a Spring component but does not make real HTTP calls to FedNow's API. All methods return synthetic responses (mocked ACSC). A production deployment requires implementing FedNow's actual API protocol (mTLS, message signing, FedNow-specific headers and schema validation).

---

### 9. Cancellation message flow (camt.056 / camt.029) is modeled but not wired

The ISO 20022 message classes `Camt056Message` (payment cancellation request) and `Camt029Message` (resolution of investigation) are implemented with their field mappings. There is no endpoint, handler, or service that processes an inbound camt.056 or generates an outbound camt.029.

---

### 10. No authentication on admin endpoints

`POST /admin/reconcile` is open to any caller. In production, admin endpoints must be protected by network-level access controls (VPN, bastion host) or application-level authentication (OAuth2, client certificates). The Javadoc on `AdminController` notes this. It is not implemented in the framework itself because the appropriate mechanism varies by institution.

---

## Local Development Limitations

These are behaviors that differ between the local H2-backed development setup and a production PostgreSQL deployment.

### 11. H2 in-memory does not persist across restarts

Transaction records written to `shadow_ledger_transaction_log`, `saga_state`, and `idempotency_keys` are lost when the application restarts. In a PostgreSQL deployment, these records survive restarts, which means:

- Bridge-mode transactions accumulate in `shadow_ledger_transaction_log` during an offline window
- After the core returns and the app restarts, `ReconciliationService.reconcile()` finds and replays them
- `transactionsReplayed` in the reconciliation report reflects the actual backlog

In local development with H2, `transactionsReplayed` is always 0 after a restart because the table was reset.

---

### 12. Redis has no replication or persistence in docker-compose

The `docker-compose.yml` starts a single Redis node with no replicas and no AOF/RDB persistence configured. If Redis is stopped and restarted between a maintenance window and reconciliation, all Shadow Ledger balance entries are lost — the reconciliation would read zero balances from Redis and flag discrepancies for every account.

For production, Redis should be deployed with AOF persistence enabled (`--appendonly yes`) and at minimum one replica.

---

## Related

- [ADR-0001](adr/0001-optimistic-locking-shadow-ledger-debits.md) — why WATCH/MULTI/EXEC (and its single-instance constraint)
- [ADR-0003](adr/0003-provisional-acceptance-acsp.md) — why ACSP is returned, and the exposure window
- [ADR-0004](adr/0004-eventual-consistency-shadow-ledger-and-core.md) — why eventual consistency was chosen over 2PC
- [shadow-ledger.md](shadow-ledger.md) — Shadow Ledger operational detail and failure modes
- [saga-pattern.md](saga-pattern.md) — compensation path for post-reconciliation core rejections
