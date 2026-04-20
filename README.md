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
│   │   └── MessageRouter.java
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
│   └── iso20022-mapping.md
├── LICENSE                   # Apache 2.0
└── README.md
```

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
