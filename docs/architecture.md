# Architecture Overview

OpenFedNow is structured as five independent layers. Each layer addresses one specific dimension of the incompatibility between legacy batch-processing core banking systems and the Federal Reserve's FedNow 24/7 real-time payment network.

This document describes the architecture rationale, the design decisions behind each layer, and how the layers interact. For context on the underlying market problem, see [fednow-adoption-gap.md](fednow-adoption-gap.md). For the production background that validates this architecture, see [pix-validation.md](pix-validation.md).

---

## The Four Incompatibilities

Legacy core banking systems and real-time payment networks are incompatible along four distinct dimensions. Each layer in this framework addresses one or more of them.

| Incompatibility | Root Cause | Layer That Resolves It |
|----------------|------------|------------------------|
| Processing model | Legacy: batch cycles. FedNow: sub-20-second event-driven | Layer 2 (SyncAsyncBridge) |
| Availability | Legacy: maintenance windows. FedNow: 24/7/365 | Layer 4 (Shadow Ledger) |
| Protocol | Legacy: proprietary APIs/formats. FedNow: ISO 20022 REST/JSON | Layer 2 (ACL) + Layer 1 |
| Concurrency | Legacy: sequential processing. FedNow: simultaneous load | Layer 3 (Processing Engine) |

The key architectural insight is that these incompatibilities are *independent* — each can be resolved without solving the others. This is why a layered approach works: each layer has a single, well-defined responsibility.

---

## Full Architecture

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

★ Novel architectural contributions: the Anti-Corruption Layer and Shadow Ledger resolve the two hardest problems in legacy payment integration and are the primary engineering innovations in this framework.

---

## Layer 1 — API Gateway & Security

**Responsibility:** All communication with the Federal Reserve's FedNow Service.

**What it does:**
- Establishes and maintains TLS mutual authentication using Federal Reserve PKI certificates
- Parses and validates inbound ISO 20022 messages (pacs.008.001.08 credit transfers)
- Routes validated messages to Layer 2 for core processing
- Constructs outbound ISO 20022 responses (pacs.002.001.10 status reports) for return to FedNow
- Enforces rate limiting and applies fraud pre-screening on all inbound paths

**Why a dedicated layer:** The Federal Reserve's connectivity requirements are independent of core banking vendor. Isolating them here means the gateway never needs to change when adapters change, and vice versa.

**Key classes:** `FedNowGateway.java`, `CertificateManager.java`, `MessageRouter.java`

---

## Layer 2 — Anti-Corruption Layer / Core Banking Adapter

**Responsibility:** Translation between the ISO 20022 world and each core banking vendor's proprietary world, and the resolution of the timing incompatibility between FedNow and legacy systems.

This is the architectural core of the framework. It contains two distinct mechanisms:

### Protocol Translation (Anti-Corruption Layer)

FedNow uses ISO 20022: REST APIs, JSON over HTTPS, UTF-8 encoding, standardized field names and data types. Legacy core banking systems use proprietary formats: vendor-specific APIs, custom field names, fixed-point arithmetic, ASCII or EBCDIC encoding (Fiserv Precision/Premier), or XML/SOAP interfaces.

These two worlds cannot communicate without translation. The MessageTranslator handles field mapping, encoding conversion, and amount formatting for each vendor. The CoreBankingAdapter interface defines the minimal contract (post a transfer, check a balance, report availability), and each vendor's concrete adapter implements it.

### Synchronous-to-Asynchronous Bridge

FedNow requires a synchronous response within 20 seconds. Legacy core systems were designed for batch processing and are inherently asynchronous — a core system that accepts a transaction may not confirm it for minutes.

The SyncAsyncBridge resolves this by:
1. Attempting a synchronous call to the core with a 15-second timeout
2. If the core responds in time: return that result directly to FedNow
3. If the core does not respond in time: return a provisional acceptance to FedNow, backed by Shadow Ledger balance validation, and register the transaction for async reconciliation
4. When the core eventually confirms or rejects: the Saga orchestrator handles the outcome

This design ensures FedNow never times out waiting for a legacy core response.

### The 85/15 Principle

Approximately 85% of the integration architecture — Layers 1, 3, and 4, plus the ACL core — is identical across all core banking vendors. Only the vendor-specific adapter (the concrete implementation of `CoreBankingAdapter`) varies. This represents approximately 15% of total engineering scope.

Building adapters for Fiserv, FIS, and Jack Henry covers >70% of U.S. financial institutions without duplicating the underlying framework.

**Key classes:** `CoreBankingAdapter.java`, `MessageTranslator.java`, `SyncAsyncBridge.java`, `FiservAdapter.java`, `FisAdapter.java`, `JackHenryAdapter.java`

**Detailed documentation:** [anti-corruption-layer.md](anti-corruption-layer.md)

---

## Layer 3 — Real-Time Processing Engine

**Responsibility:** Transaction orchestration, distributed state management, and reliability under concurrent load.

A FedNow payment touches multiple systems: the Shadow Ledger (balance reservation), the core banking system (actual posting), and FedNow itself (settlement confirmation). No single ACID transaction can span all three. If any step fails after earlier steps have succeeded, the partially-completed state must be rolled back cleanly.

The Processing Engine handles this through three mechanisms:

**Saga Pattern:** Each payment is managed as a saga — a sequence of steps, each with a compensating action that reverses it if a later step fails. The SagaOrchestrator manages the lifecycle and triggers compensation when needed. Saga workflows are implemented on [Temporal.io](https://temporal.io/), which provides durable execution: if the service restarts mid-saga, the workflow resumes from where it left off. See [saga-pattern.md](saga-pattern.md).

**Inter-Layer Messaging (Kafka):** Layers communicate via Apache Kafka events rather than synchronous in-process calls. This decoupling allows each layer to scale independently and provides a durable event log that can be replayed for recovery or audit purposes.

**Idempotency:** FedNow may retry a payment message if it does not receive a timely response. The IdempotencyService ensures that duplicate submissions of the same transaction are detected and deduplicated — the same payment is never posted twice.

**Circuit Breakers (Resilience4j):** If the core banking system becomes unavailable (outside of a scheduled maintenance window), Resilience4j circuit breakers prevent the processing engine from continuing to submit requests to a system that cannot respond. This protects both FedNow response SLAs and the core system itself from an overload of retried requests.

**Key classes:** `PaymentSaga.java`, `SagaOrchestrator.java`, `IdempotencyService.java`

**Detailed documentation:** [saga-pattern.md](saga-pattern.md)

---

## Layer 4 — Shadow Ledger & 24/7 Availability Bridge

**Responsibility:** Continuous FedNow operation regardless of core banking system availability.

This is the second major architectural innovation in the framework. Legacy core banking systems take scheduled maintenance windows — typically nightly (2–4 hours) or on weekends. FedNow operates 24/7/365 and cannot distinguish between an institution that is offline and one that is failing.

The Shadow Ledger solves this by maintaining a continuously updated, independently authoritative view of account balances:

- **During normal operation:** The Shadow Ledger mirrors the core's balances in real time, updating on every FedNow transaction. The core remains authoritative.
- **When the core goes offline:** The Shadow Ledger becomes the authoritative source for balance checks and payment authorization. Transactions are queued via RabbitMQ (durable, persistent) for ordered replay when the core returns. Transaction records are persisted to PostgreSQL for the reconciliation audit log.
- **When the core comes back online:** The ReconciliationService replays all queued transactions against the core in timestamp order and reconciles any balance differences against the PostgreSQL audit log. Zero discrepancy tolerance: any difference triggers an alert.

From FedNow's perspective, the institution is always available. From the core's perspective, the Shadow Ledger is a transparency layer that catches up after downtime — not a replacement for the ledger of record.

**Key classes:** `ShadowLedger.java`, `AvailabilityBridge.java`, `ReconciliationService.java`

**Detailed documentation:** [shadow-ledger.md](shadow-ledger.md)

---

## Layer 5 — Legacy Core Banking (Unchanged)

**Responsibility:** None — this layer represents the existing core banking infrastructure.

A deliberate architectural principle: the framework works *around* legacy systems, never *through* them. No modification to the core banking system is required. This is critical for two reasons:

1. **Risk:** Core banking systems are regulated infrastructure. Modification requires extensive testing, regulatory notification, and vendor approval. Changes take months to years. This framework eliminates that requirement entirely.
2. **Cost:** Core banking replacements cost community banks $5–50M and take 2–5 years. A middleware approach allows FedNow participation at a fraction of that cost, without disrupting existing operations.

---

## Technology Stack

| Component | Technology | Rationale |
|-----------|-----------|-----------|
| Framework | Spring Boot 3.x | Industry standard for Java enterprise services; strong ecosystem for async, messaging, and resilience patterns |
| Language | Java 17+ | Required for Spring Boot 3.x; strong type safety for financial data |
| Internal event bus | Apache Kafka | Decouples layers for independent scaling and replay; guaranteed delivery for inter-layer events |
| Saga orchestration | Temporal.io | Durable workflow execution with built-in retry, timeout, and compensation support for the payment saga |
| Balance store | Redis | Sub-millisecond reads required for FedNow's 20-second window |
| Message queuing | RabbitMQ | Durable message persistence for maintenance window queuing |
| Layer 4 persistence | PostgreSQL | Transactional store for reconciliation audit log and queued transaction records |
| Resilience | Resilience4j | Circuit breakers and bulkheads protecting Layer 2 adapter calls to core banking systems |
| Build | Maven | Standard for Java enterprise projects |
| Deployment (dev) | Docker Compose | Reproducible local environment including Kafka, Redis, RabbitMQ, and PostgreSQL |
| Deployment (prod) | OpenShift / Kubernetes | Container orchestration in production; see Deployment Architecture below |

---

## Deployment Architecture

A production OpenFedNow deployment spans three distinct network zones:

```
┌────────────────────────────────────────────────────────────────────┐
│                  LEGACY FINANCIAL INSTITUTION ZONE                  │
│  IBM z/OS · IBM DB2 · CICS · IBM MQ                                │
│  Fiserv DNA/Precision/Premier · Jack Henry SilverLake/Symitar      │
│  FIS Horizon/IBS · EBCDIC / ASCII encoding                         │
│  (No modification to any component in this zone)                   │
└───────────────────────────┬────────────────────────────────────────┘
                            │  Vendor-specific adapter calls
                            │  (SOAP/XML, REST, MQ messages)
┌───────────────────────────▼────────────────────────────────────────┐
│              OPENSHIFT / KUBERNETES CLUSTER                         │
│                                                                     │
│  Layer 1: Spring Boot API Gateway (Fed PKI TLS)                    │
│  Layer 2: ACL Adapters (per-vendor containers)                     │
│  Layer 3: Temporal.io Saga workers · Kafka brokers                 │
│  Layer 4: Shadow Ledger service · Reconciliation service           │
│           Redis cluster · RabbitMQ · PostgreSQL                    │
└───────────────────────────┬────────────────────────────────────────┘
                            │  ISO 20022 / HTTPS
                            │  Fed PKI mutual TLS
┌───────────────────────────▼────────────────────────────────────────┐
│                   FEDNOW SERVICE ZONE (Federal Reserve)             │
│  FedNow Participant Interface · pacs.008 inbound / pacs.002 return │
└────────────────────────────────────────────────────────────────────┘
```

The three-zone model enforces a clean security boundary: the FedNow Service Zone never has direct access to the institution's core systems, and the institution's legacy systems are never exposed to the public internet. All cross-zone communication is strictly mediated by the OpenShift cluster.

In development, the OpenShift cluster is replaced by Docker Compose, which runs the same services locally.

---

## Design Decisions and Alternatives Considered

**Why not a Core Banking Replacement?**
Replacing the core banking system is the alternative most often proposed by core vendors. It is also the most expensive and highest-risk approach. Community banks typically have 5–15 years remaining on core contracts, $M in data migration costs, and regulatory obligations around continuity. Middleware avoids all of this.

**Why not a direct FedNow API integration?**
The Federal Reserve provides FedNow connectivity documentation, but it documents the FedNow API — not the integration from that API into each core banking system. The latter is the hard problem. A direct integration would require each institution to build their own adapter from scratch, which is precisely the barrier this framework eliminates.

**Why five layers, not three or seven?**
The five layers map directly to the five distinct incompatibilities (protocol × 2, timing, availability, concurrency). Fewer layers would combine concerns that change independently; more layers would create unnecessary abstraction. Each layer boundary corresponds to a system boundary that exists in production — FedNow, the gateway, the adapter, the core system, and the persistence layer.

**Why open source (Apache 2.0)?**
Community banks and credit unions are the institutions most affected by the FedNow adoption gap and least likely to have the engineering resources to build a custom integration. Licensing fees would create the same cost barrier as custom development. Apache 2.0 allows free use, modification, and commercial deployment, ensuring the framework is accessible to the exact institutions it is designed to serve.

---

## Related Documentation

- [Shadow Ledger](shadow-ledger.md) — detailed design for Layer 4
- [Anti-Corruption Layer](anti-corruption-layer.md) — vendor adapter details and the sync/async bridge
- [Saga Pattern](saga-pattern.md) — distributed transaction management
- [ISO 20022 Mapping](iso20022-mapping.md) — message format reference
- [PIX Production Validation](pix-validation.md) — how this architecture was validated in production
- [FedNow Adoption Gap](fednow-adoption-gap.md) — market context and the problem this framework addresses
