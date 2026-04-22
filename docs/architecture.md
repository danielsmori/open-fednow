# Architecture Overview

OpenFedNow is structured as five independent layers. Each layer addresses one specific dimension of the legacy-to-real-time incompatibility. See the main README for the full architecture diagram.

## Layer Responsibilities

| Layer | Component | Incompatibility Addressed |
|-------|-----------|--------------------------|
| 1 | API Gateway & Security | Protocol — TLS mutual auth, ISO 20022 parsing |
| 2 | Anti-Corruption Layer | Protocol + Speed — vendor translation, sync/async bridge |
| 3 | Real-Time Processing Engine | Speed + Concurrency — Saga, idempotency, circuit breakers |
| 4 | Shadow Ledger & Availability Bridge | Availability — 24/7 operation through maintenance windows |
| 5 | Legacy Core Banking | Unchanged — no modification required |

## Detailed documentation

- [Shadow Ledger](shadow-ledger.md)
- [Anti-Corruption Layer](anti-corruption-layer.md)
- [Saga Pattern](saga-pattern.md)
- [ISO 20022 Mapping](iso20022-mapping.md)
