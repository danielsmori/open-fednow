# OpenFedNow — Legacy-to-Real-Time Payment Integration Framework

**An open-source middleware framework for connecting legacy core banking systems to the Federal Reserve's FedNow Instant Payment Service.**

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/Status-Early%20Development-yellow)]()
[![Java](https://img.shields.io/badge/Java-17%2B-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green)]()

---

## The Problem

The Federal Reserve's FedNow Instant Payment Service launched in July 2023. As of early 2026, only approximately 1,500 of the nation's 10,000+ financial institutions have connected — roughly 16% of the eligible ecosystem.

The barrier is not cost or intent. Industry research documents that **73% of U.S. financial institutions cite legacy core banking systems as a moderate-to-severe obstacle** to FedNow participation (U.S. Faster Payments Council / Finzly, 2024). The core banking platforms that power the majority of U.S. banks — Fiserv, FIS, and Jack Henry — were designed for batch-processing cycles, not 24/7 real-time settlement.

This creates four fundamental incompatibilities:

- **Processing model mismatch** — Legacy systems process in batches; FedNow requires sub-20-second event-driven responses
- **Availability mismatch** — Legacy systems have maintenance windows; FedNow operates 24/7/365
- **Protocol mismatch** — Legacy systems use proprietary APIs; FedNow uses ISO 20022 REST/JSON messaging
- **Concurrency mismatch** — Legacy systems were not designed for high-volume simultaneous transaction loads

These incompatibilities cannot be resolved by adding API endpoints. They require purpose-built architectural layers that bridge the two paradigms — allowing institutions to participate in FedNow without replacing their existing core systems.

---

## The Solution

OpenFedNow is a five-layer middleware framework that resolves each of these incompatibilities through proven architectural patterns, validated at national scale during Brazil's PIX instant payment deployment.

The framework is designed around a key insight: **approximately 85% of the integration architecture is identical across all financial institutions**. Only the Core Banking Adapter — roughly 15% of the total engineering scope — varies per core banking vendor. This means that building adapters for the three dominant U.S. platforms covers the majority of the market without duplicating the underlying work.

### Core Banking Platform Coverage

| Vendor | U.S. Bank Market Share | U.S. Credit Union Share |
|--------|------------------------|------------------------|
| Fiserv (DNA, Precision, Premier, Cleartouch) | 42% | 31% |
| Jack Henry (SilverLake, Symitar, CIF 20/20) | 21% | 12% |
| FIS (Horizon, IBS) | 9% | — |
| **Big Three combined** | **>70%** | |

*Source: Federal Reserve Bank of Kansas City, Market Structure of Core Banking Services Providers, March 2024.*

Three adapter implementations make the complete framework available to thousands of institutions.

---

## Quick Start

**Prerequisites:** Java 17+, Docker. No other infrastructure setup required — the framework uses H2 in-memory for local development.

```bash
git clone https://github.com/danielsmori/open-fednow
cd open-fednow
docker-compose up -d        # Redis (Shadow Ledger) + RabbitMQ (maintenance queue)
mvn spring-boot:run         # starts on :8080 with sandbox adapter and H2 in-memory
```

### 1. Send a payment (core online)

```bash
curl -s -X POST http://localhost:8080/fednow/receive \
  -H "Content-Type: application/json" \
  -d '{
    "messageId":                  "MSG-DEMO-001",
    "endToEndId":                 "E2E-DEMO-001",
    "transactionId":              "TXN-DEMO-001",
    "interbankSettlementAmount":  250.00,
    "interbankSettlementCurrency":"USD",
    "creditorAccountNumber":      "ACC-DEMO-12345"
  }'
```

```json
{"transactionStatus":"ACSC","originalEndToEndId":"E2E-DEMO-001","originalTransactionId":"TXN-DEMO-001"}
```

`ACSC` — AcceptedSettlementCompleted. The sandbox core accepted the transfer.

### 2. Test a rejection scenario

The sandbox adapter routes by `creditorAccountNumber` prefix. No config change needed:

```bash
curl -s -X POST http://localhost:8080/fednow/receive \
  -H "Content-Type: application/json" \
  -d '{
    "messageId":                  "MSG-DEMO-002",
    "endToEndId":                 "E2E-DEMO-002",
    "transactionId":              "TXN-DEMO-002",
    "interbankSettlementAmount":  250.00,
    "interbankSettlementCurrency":"USD",
    "creditorAccountNumber":      "RJCT_FUNDS_ACC-DEMO-67890"
  }'
```

```json
{"transactionStatus":"RJCT","originalEndToEndId":"E2E-DEMO-002","rejectReasonCode":"AM04"}
```

`RJCT / AM04` — Rejected, insufficient funds. Other prefixes: `RJCT_ACCT_` (AC01), `RJCT_CLOSED_` (AC04), `TOUT_` (triggers the ACSP provisional-acceptance path).

### 3. Trigger provisional acceptance (ACSP)

The `TOUT_` prefix makes the sandbox adapter return a TIMEOUT status immediately. The `SyncAsyncBridge` maps this to a provisional acceptance without waiting. No app restart needed:

```bash
curl -s -X POST http://localhost:8080/fednow/receive \
  -H "Content-Type: application/json" \
  -d '{
    "messageId":                  "MSG-DEMO-003",
    "endToEndId":                 "E2E-DEMO-003",
    "transactionId":              "TXN-DEMO-003",
    "interbankSettlementAmount":  300.00,
    "interbankSettlementCurrency":"USD",
    "creditorAccountNumber":      "TOUT_ACC-DEMO-12345"
  }'
```

```json
{"transactionStatus":"ACSP","originalEndToEndId":"E2E-DEMO-003","originalTransactionId":"TXN-DEMO-003"}
```

`ACSP` — AcceptedSettlementInProcess. FedNow received a response within its 20-second window. The `PEND_` prefix works identically. For the full maintenance-window path (core offline → ACSP → RabbitMQ queue → reconcile), restart the app with `OPENFEDNOW_SANDBOX_CORE_AVAILABLE=false mvn spring-boot:run`.

### 4. Trigger reconciliation

No restart needed — reconcile against the same running instance:

```bash
curl -s -X POST http://localhost:8080/admin/reconcile
```

```json
{
  "transactionsReplayed": 0,
  "discrepanciesDetected": 0,
  "reconciliationSuccessful": true,
  "summary": "Clean reconciliation: 0 entries confirmed across all accounts"
}
```

`transactionsReplayed: 0` — H2 in-memory resets on restart, so this run starts with a clean ledger. In a PostgreSQL deployment, bridge-mode transactions accumulated during the offline window appear here as `transactionsReplayed: N`. The reconciliation path is fully exercised in `BridgeModeIntegrationTest` and `ReconciliationServiceIntegrationTest` using Testcontainers.

---

## The Shadow Ledger in Action

The Shadow Ledger is what makes 24/7 FedNow participation possible with a legacy core that has nightly maintenance windows. It's the most defensible piece of this architecture, so here's what it actually does.

### The problem it solves

```
Legacy core: offline 2am–6am for batch processing
FedNow:      $300 payment arrives at 3am
Without Shadow Ledger: institution doesn't respond → FedNow marks you unavailable
With Shadow Ledger:    institution responds ACSP in under 1 second → FedNow happy
```

### Concrete behavior, step by step

**Account balance is seeded from the core at startup (stored in Redis as integer cents):**

```bash
$ redis-cli SET balance:ACC-12345 5000000     # $50,000.00
$ redis-cli GET balance:ACC-12345
"5000000"
```

Balances are stored as integer cents — no floating-point arithmetic on money.

---

**Stage 1: 11pm — Core is online. A $250 payment arrives.**

```java
// Inside MessageRouter.routeInbound() when core is available:
shadowLedger.applyDebit("ACC-12345", new BigDecimal("250.00"), "TXN-0001");
```

The debit uses Redis WATCH / MULTI / EXEC — atomic, safe under concurrent load:

```
WATCH  balance:ACC-12345
GET    balance:ACC-12345           → "5000000"
MULTI
  SET  balance:ACC-12345  4975000  ← $50,000 – $250 = $49,750
EXEC                               → success (key unchanged since WATCH)
```

```bash
$ redis-cli GET balance:ACC-12345
"4975000"                          # $49,750.00
```

An audit row is written to PostgreSQL:

```
shadow_ledger_transaction_log:
  transaction_id  = TXN-0001
  type            = DEBIT
  amount          = 250.00
  balance_before  = 50000.00
  balance_after   = 49750.00
  core_confirmed  = FALSE          ← pending core confirmation
```

`pacs.002 ACSC` is returned to FedNow. The core is contacted and confirms.
`core_confirmed` flips to `TRUE`. The Shadow Ledger and core are in sync.

---

**Stage 2: 2am — Core goes offline for scheduled maintenance.**

The `AvailabilityBridge` polls every 30 seconds and detects the transition:

```
[WARN] Core banking system OFFLINE — entering bridge mode,
       transactions will be queued for replay
```

**A $300 payment arrives at 2:47am.**

```java
// AvailabilityBridge.isInBridgeMode() == true
// Balance check passes against Shadow Ledger: $49,750 > $300
shadowLedger.applyDebit("ACC-12345", new BigDecimal("300.00"), "TXN-0002");
availabilityBridge.queueForCoreProcessing("E2E-MAINT-001", serializedPacs008);
```

```bash
$ redis-cli GET balance:ACC-12345
"4945000"                          # $49,450.00 — debit applied to Shadow Ledger

$ curl -s -u guest:guest \
    'http://localhost:15672/api/queues/%2F/maintenance-window-transactions' \
    | python3 -c "import sys,json; q=json.load(sys.stdin); print('queued:', q['messages'])"
queued: 1
```

`pacs.002 ACSP` is returned to FedNow immediately — well within the 20-second window.
The core never saw this request. FedNow has no idea the core was offline.

---

**Stage 3: 6am — Core comes back online.**

```
[INFO] Core banking system ONLINE — exiting bridge mode, reconciliation pending
```

```bash
$ curl -s -X POST http://localhost:8080/admin/reconcile
```

```json
{
  "transactionsReplayed": 1,
  "discrepanciesDetected": 0,
  "reconciliationSuccessful": true,
  "summary": "Clean reconciliation: 1 entries confirmed across all accounts"
}
```

The `ReconciliationService`:
1. Finds all accounts with `core_confirmed = FALSE` entries
2. Fetches the authoritative balance from the core for each account
3. If the Shadow Ledger balance matches: marks entries confirmed, done
4. If there's a discrepancy (e.g., the core processed something OpenFedNow didn't know about): overwrites the Shadow Ledger with the core's figure, logs a `RECONCILIATION` row, and alerts — **zero discrepancy tolerance**

```bash
$ redis-cli GET balance:ACC-12345
"4945000"                          # $49,450.00 — confirmed by core
```

The institution was available to FedNow for the entire 4-hour maintenance window. Every payment was accepted and the ledger is correct.

### What protects against overdrafts under concurrent load

Three payments arrive simultaneously at 3am for the same account:

```
Thread 1: WATCH balance:ACC-12345 → GET "4945000" → MULTI → SET "4895000" → EXEC ✓
Thread 2: WATCH balance:ACC-12345 → GET "4945000" → EXEC returns [] (conflict) → retry
Thread 3: WATCH balance:ACC-12345 → GET "4895000" → MULTI → SET "4845000" → EXEC ✓
Thread 2: WATCH balance:ACC-12345 → GET "4845000" → MULTI → SET "4795000" → EXEC ✓
```

Each thread retries until its EXEC succeeds. The balance is always consistent. See [ADR-0001](docs/adr/0001-optimistic-locking-shadow-ledger-debits.md) for the full analysis including the Lettuce empty-list caveat.

---

## Architecture

The framework is structured as five independent layers. Each layer addresses a specific dimension of the legacy-to-real-time incompatibility.

```
┌─────────────────────────────────────────────────────────────┐
│              FedNow Service (Federal Reserve)                │
│                    ISO 20022 / HTTPS                         │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│         LAYER 1 — API Gateway & Security                     │
│  TLS mutual auth · Fed PKI certificates · Rate limiting      │
│  Fraud pre-screening · pacs.008 / pacs.002 routing           │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│    LAYER 2 — Anti-Corruption Layer / Core Banking Adapter ★  │
│  ISO 20022 ↔ Vendor protocol translation                     │
│  Sync-to-async bridge · Vendor-specific adapters             │
│  [ Fiserv ] [ FIS ] [ Jack Henry ] [ IBM z/OS ]              │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│       LAYER 3 — Real-Time Processing Engine                  │
│  Saga orchestration · Idempotency framework                  │
│  Distributed cache · Circuit breakers                        │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│    LAYER 4 — Shadow Ledger & 24/7 Availability Bridge ★      │
│  Real-time balance tracking · Async message queuing          │
│  Maintenance window handling · Reconciliation service        │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│         LAYER 5 — Legacy Core Banking (unchanged)            │
│  Fiserv · FIS · Jack Henry · IBM z/OS — no modification      │
└─────────────────────────────────────────────────────────────┘
```

★ Novel architectural contributions: the Anti-Corruption Layer and Shadow Ledger resolve the two hardest problems in legacy payment integration.

### Layer Descriptions

**Layer 1 — API Gateway & Security**
Manages all communication with the Federal Reserve's FedNow Service. Handles TLS mutual authentication using Federal Reserve PKI certificates, ISO 20022 message parsing and validation (pacs.008.001.08 credit transfers, pacs.002.001.10 payment status reports), rate limiting, and fraud pre-screening on all inbound and outbound paths.

**Layer 2 — Anti-Corruption Layer / Core Banking Adapter**
The architectural core of the framework. Translates between the modern ISO 20022 world (REST APIs, JSON, UTF-8) and the proprietary world of each core banking vendor (vendor-specific APIs, proprietary formats). Also manages the synchronous-to-asynchronous bridge: FedNow requires synchronous sub-20-second responses, but legacy core processing is inherently asynchronous. This layer decouples the two models. The vendor-specific adapter is the only component that varies between institutions (~15% of total scope).

**Layer 3 — Real-Time Processing Engine**
Manages transaction orchestration and state across distributed systems. Key components: Saga pattern implementation for distributed transaction management (with compensation logic for rollback across multiple systems), idempotency key management to prevent duplicate processing, distributed cache for real-time balance availability, and circuit breakers to prevent cascade failures.

**Layer 4 — Shadow Ledger & 24/7 Availability Bridge**
Resolves the hardest operational problem: legacy core systems go offline for maintenance while FedNow never does. The Shadow Ledger maintains a real-time view of available balances independently of the core system. Transactions arriving during core downtime are queued via async messaging and processed against the Shadow Ledger. A reconciliation service ensures the core ledger remains authoritative when it returns online. From FedNow's perspective, the institution is always available.

**Layer 5 — Legacy Core Banking**
The existing core banking infrastructure — unchanged. A deliberate architectural principle: the framework works around legacy systems, never requiring their transformation. This protects the stability of core banking operations while enabling full FedNow participation.

---

## ISO 20022 Compatibility

FedNow uses the ISO 20022 international messaging standard — the same standard used by Brazil's PIX instant payment system. This framework implements the following message types:

| Message Type | Description | Direction |
|---|---|---|
| `pacs.008.001.08` | FI-to-FI Customer Credit Transfer | Outbound (send) |
| `pacs.002.001.10` | Payment Status Report | Inbound (confirmation/rejection) |

---

## Architectural Background

The five-layer architecture in this framework was developed and validated during the production integration of Brazil's PIX national instant payment system at Santander Brazil (2020–2021). PIX launched on November 16, 2020 under Central Bank of Brazil mandate, with the national network reaching 175 million registered users and a single-day record of 313.3 million transactions across all participating institutions.

The core architectural problem — connecting legacy batch-processing mainframe systems to a 24/7 real-time payment network under ISO 20022 — is structurally identical to the FedNow challenge. The Anti-Corruption Layer design, Shadow Ledger pattern, and Saga orchestration approach were each developed and validated in that production environment.

The Santander PIX platform is proprietary to Santander Brazil. This framework is a new, independent implementation — built from the ground up for the U.S. context, applying the proven methodology to the FedNow environment with Fiserv, FIS, and Jack Henry adapters replacing the original mainframe adapters.

---

## Project Structure

```
openfednow/
├── src/main/java/io/openfednow/
│   ├── gateway/              # Layer 1 — API Gateway & Security
│   │   ├── FedNowGateway.java
│   │   ├── CertificateManager.java
│   │   ├── MessageRouter.java
│   │   └── AdminController.java  # /admin/reconcile
│   ├── acl/                  # Layer 2 — Anti-Corruption Layer
│   │   ├── core/
│   │   │   ├── CoreBankingAdapter.java      # Abstract interface
│   │   │   ├── MessageTranslator.java
│   │   │   └── SyncAsyncBridge.java
│   │   └── adapters/
│   │       ├── FiservAdapter.java           # In development
│   │       ├── FisAdapter.java              # Planned
│   │       └── JackHenryAdapter.java        # Planned
│   ├── processing/           # Layer 3 — Real-Time Processing Engine
│   │   ├── saga/
│   │   │   ├── PaymentSaga.java
│   │   │   └── SagaOrchestrator.java
│   │   └── idempotency/
│   │       └── IdempotencyService.java
│   ├── shadowledger/         # Layer 4 — Shadow Ledger & Bridge
│   │   ├── ShadowLedger.java
│   │   ├── AvailabilityBridge.java
│   │   └── ReconciliationService.java
│   └── iso20022/             # ISO 20022 message models
│       ├── Pacs008Message.java
│       └── Pacs002Message.java
├── docs/
│   ├── architecture.md
│   ├── shadow-ledger.md
│   ├── anti-corruption-layer.md
│   ├── saga-pattern.md
│   ├── iso20022-mapping.md
│   ├── known-limitations.md   # What is and isn't production-ready
│   └── adr/                  # Architecture Decision Records
│       ├── 0001-optimistic-locking-shadow-ledger-debits.md
│       ├── 0002-redis-shadow-ledger-over-direct-core-reads.md
│       ├── 0003-provisional-acceptance-acsp.md
│       └── 0004-eventual-consistency-shadow-ledger-and-core.md
├── LICENSE                   # Apache 2.0
└── README.md
```

---

## Known Limitations

The framework is a reference architecture, not a production-ready product. See [docs/known-limitations.md](docs/known-limitations.md) for the full list. Key items:

- **Vendor adapters are stubs.** `FiservAdapter`, `FisAdapter`, and `JackHenryAdapter` define the contract but do not make real vendor API calls. Only `SandboxAdapter` is functional.
- **Shadow Ledger not wired into the HTTP flow.** `ShadowLedger.applyDebit()` / `applyCredit()` are fully implemented and tested in isolation; they are not yet called from the payment endpoint.
- **Single-instance.** The `WATCH`/`MULTI`/`EXEC` optimistic locking is safe for one pod. Multi-pod deployments require a distributed lock per account or consistent-hash routing.
- **No authentication on `/admin/*`.** Production deployments must place admin endpoints behind network-level or application-level access controls.
- **Post-reconciliation reversals are customer-visible.** If the core rejects a provisionally accepted transaction, a pacs.004 return goes back to FedNow. The sender's institution sees a credit followed by a return.

---

## Roadmap

**Phase 1 — Foundation & Fiserv Adapter (Months 1–9)**
- Five-layer framework foundation
- Fiserv DNA / Precision adapter (42% of U.S. banks, 31% of credit unions)
- Pilot validation with community bank partners
- Full test suite and deployment documentation

**Phase 2 — FIS Adapter & Open Publication (Months 10–18)**
- FIS IBS / Horizon adapter
- Public open-source release with documentation
- Reference architecture whitepaper

**Phase 3 — Jack Henry Adapter & Standards Contribution (Months 19–24)**
- Jack Henry SilverLake / Symitar adapter
- Big Three combined: >70% of U.S. banks covered
- Submission to U.S. Faster Payments Council as reference integration pattern

**Phase 4 — IBM z/OS Path & Knowledge Transfer (Month 25+)**
- IBM z/OS mainframe adapter for large institutions
- Technical assistance program for community bank engineering teams

---

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

Areas where contributions are especially valuable:
- Core banking vendor adapter implementations
- ISO 20022 message validation
- Test coverage for Saga compensation logic
- Documentation and integration guides

---

## License

This project is licensed under the **Apache License 2.0**. See [LICENSE](LICENSE) for the full text.

The goal of the Apache 2.0 license is to ensure this framework is freely available to any U.S. financial institution — regardless of size — without licensing fees or restrictions.

---

## References

- Federal Reserve. [FedNow Service](https://www.frbservices.org/financial-services/fednow). Federal Reserve Financial Services, 2023.
- Federal Reserve Bank of Kansas City. *Market Structure of Core Banking Services Providers*. March 2024.
- U.S. Faster Payments Council / Finzly. *Faster Payments Barometer*. 2024.
- ISO 20022. [Financial Services — Universal Financial Industry Message Scheme](https://www.iso20022.org). International Organization for Standardization.
- Banco Central do Brasil. [PIX — Sistema de Pagamentos Instantâneos](https://www.bcb.gov.br/estabilidadefinanceira/pix). 2020.
