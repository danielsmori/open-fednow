# PIX Production Validation

The five-layer architecture in OpenFedNow was not designed in the abstract. It was developed and validated during the production integration of Brazil's PIX national instant payment system — the largest real-time payment deployment in the Western Hemisphere — which faced structurally identical technical challenges.

This document describes the PIX deployment context, the architectural parallels to FedNow, and how the production experience informs the OpenFedNow design.

---

## PIX: National-Scale Real-Time Payments

PIX was mandated by Brazil's Central Bank (Banco Central do Brasil) and launched on November 16, 2020. Key facts about the deployment:

- **Mandatory participation:** Every financial institution in Brazil with more than 500,000 active accounts was required to connect by the launch date — no opt-out, no extension.
- **Scale at launch:** Brazil's banking system comprises hundreds of institutions running diverse core banking systems, including IBM mainframes, legacy COBOL systems, and proprietary platforms.
- **ISO 20022:** PIX uses the ISO 20022 messaging standard — the same standard used by FedNow.
- **24/7 settlement:** Like FedNow, PIX operates around the clock with no maintenance windows permitted from the network's perspective.
- **Transaction volume:** Within its first year, PIX reached hundreds of millions of registered users and processed billions of transactions, including a single-day record of 313.3 million transactions.

---

## The Same Architectural Problem

The technical challenge in integrating Brazil's financial institutions with PIX was structurally identical to the FedNow challenge in the United States:

| Dimension | PIX (Brazil, 2020) | FedNow (U.S., 2023–present) |
|-----------|-------------------|------------------------------|
| Network protocol | ISO 20022 / REST | ISO 20022 / REST |
| Settlement model | Real-time, 24/7 | Real-time, 24/7 |
| Response SLA | Sub-10 seconds (BCB Resolution No. 1/2020) | Sub-20 seconds |
| Core banking systems | IBM mainframe (z/OS, EBCDIC), legacy COBOL, proprietary platforms | Fiserv, Jack Henry, FIS (batch-processing) |
| Key/account directory | BACEN DICT API (live lookup per payment) | FedNow Participant Routing Directory |
| Digital signature / PKI | ICP-Brasil certificates (mandatory per message) | Fed PKI mutual TLS (mandatory) |
| Data privacy regime | LGPD (Lei Geral de Proteção de Dados) | Gramm-Leach-Bliley Act (GLBA) |
| Primary barrier | Legacy batch systems not designed for real-time event-driven processing | Same |
| Mandatory deadline | Yes (Central Bank mandate) | No (voluntary), but competitive pressure equivalent |

The principal difference is that Brazil's integration was executed under a hard regulatory deadline, which created concentrated pressure to solve the architectural problems quickly and reliably. This pressure produced solutions that have now been validated under national-scale load.

---

## How the Five Layers Were Developed

Each layer in the OpenFedNow framework addresses an incompatibility that was first encountered in the PIX integration work and solved in production.

### Layer 2 — Anti-Corruption Layer

Brazil's financial institutions ran core banking systems with proprietary APIs and data formats — many of them IBM mainframe systems using EBCDIC encoding, fixed-point amounts, and COBOL-era field naming conventions. PIX used ISO 20022 JSON. The translation layer (what OpenFedNow calls the Anti-Corruption Layer) was the first component designed.

The 85/15 principle emerged from this work: once the framework architecture was established, the per-institution custom work was exclusively in the adapter — the translation component for each institution's specific core. The framework itself did not change between institutions.

**BACEN DICT API Integration.** Every outbound PIX payment required a live query to the BACEN DICT API (Diretório de Identifiers de Contas Transacionais) before message construction. PIX key resolution — mapping a CPF (individual taxpayer ID), CNPJ (company ID), phone number, email address, or EVP (random key) to the destination account — must be performed at the adapter layer, not in the core. This means the ACL must make an HTTPS call to BCB's DICT API on every outbound payment, validate the key-to-account mapping, inject the resolved account data into the ISO 20022 message, and handle DICT lookup errors as a distinct rejection path. This lookup adds a network round-trip to the payment path and must complete within the 10-second SLA under BCB Resolution No. 1/2020.

**ICP-Brasil Digital Signatures.** Every PIX message — both outbound payments and inbound confirmations — required a valid digital signature under the ICP-Brasil PKI (Brazil's national public-key infrastructure). The adapter layer was responsible for: generating signatures on outbound messages using the institution's ICP-Brasil certificate, validating signatures on all inbound messages from BACEN, managing certificate rotation without service interruption, and integrating with HSM (Hardware Security Module) infrastructure for private key operations. This requirement has a direct U.S. analog: FedNow's Fed PKI mutual TLS certificate requirement, which Layer 1 handles.

**LGPD Compliance.** Brazil's Lei Geral de Proteção de Dados (LGPD) imposed data handling constraints on any layer that processed account holder information. PIX payments carry CPF/CNPJ identifiers, which are personal data under LGPD. The adapter layer's data handling — including logging policy, field masking in traces, and data retention for reconciliation records — was designed with LGPD compliance as a requirement. This validated the design principle that compliance obligations are handled at the adapter layer, not embedded in the core framework, allowing different regulatory regimes to be accommodated without changing shared code.

### Layer 2 — Synchronous-to-Asynchronous Bridge

Legacy cores in Brazil, like those in the U.S., processed transactions asynchronously. PIX's 10-second response requirement — mandated by Banco Central do Brasil under Resolution No. 1/2020, Article 27 — was incompatible with cores that might take minutes to confirm a transaction. The Sync-to-Async Bridge was developed to resolve this: provisional acceptance backed by a real-time balance check, with async reconciliation of the core's actual response. This pattern was validated under production load at one of Brazil's largest financial institutions before being incorporated into the framework.

The SLA difference between PIX (10 seconds) and FedNow (20 seconds) means the U.S. implementation has slightly more margin in the SyncAsyncBridge's synchronous timeout window, but the architectural pattern is unchanged: both systems require a deterministic response within a fixed window regardless of the core's actual processing time.

### Layer 4 — Shadow Ledger

Brazil's largest institutions discovered the maintenance window problem during integration testing: their cores had scheduled nightly maintenance that conflicted with PIX's 24/7 requirement. The Shadow Ledger design — real-time balance replication, durable queueing for offline periods, reconciliation on core return — was developed to address this. The reconciliation logic in particular required careful design to handle edge cases (partially applied transactions, duplicate submissions during reconnect) that only appear at production scale.

### Layer 3 — Saga Orchestration

The distributed transaction problem — a payment spanning FedNow, the Shadow Ledger, and the core, with no single ACID transaction available — appeared immediately in production when partial failures needed to be handled cleanly. The Saga pattern's compensating transaction model was validated as the correct approach for this class of problem.

---

## What PIX Proves About FedNow

**The architecture is correct.** Every technical challenge in FedNow integration — batch-to-real-time mismatch, 24/7 availability, ISO 20022 protocol translation, distributed transaction safety — was encountered and resolved in the PIX deployment. The solutions are not theoretical; they operated at hundreds of millions of transactions per month.

**The 85/15 principle holds at scale.** The framework was applied across multiple institutions during the PIX rollout, each with different core banking systems. In each case, the shared framework was unchanged; only the adapter layer varied. This validates the design principle that makes OpenFedNow reusable across all U.S. financial institutions.

**Community-bank-scale problems are the same as national-scale problems, technically.** A community bank connecting to FedNow faces the same four incompatibilities as a national bank. The transaction volume is lower, but the architectural challenges are identical. The PIX framework scales down cleanly because it was designed around architectural correctness, not throughput.

---

## The U.S. Context: Why an Independent Implementation

The Santander Brazil PIX integration is proprietary to Santander Brazil. OpenFedNow is a new, independent implementation — built from the ground up for the U.S. context, applying the same architectural methodology to the FedNow environment.

The differences from the PIX implementation are:
- **Adapter targets:** Fiserv, FIS, and Jack Henry replace the mainframe and COBOL adapters used in Brazil
- **Protocol version:** FedNow uses pacs.008.001.08 and pacs.002.001.10; PIX used earlier ISO 20022 versions
- **Regulatory environment:** FedNow is voluntary (for now); PIX was mandatory. OpenFedNow's Apache 2.0 license reflects the need to lower the adoption cost barrier in a voluntary environment
- **Shadow Ledger timing:** FedNow's 20-second window (vs. PIX's 10-second) provides slightly more tolerance for the SyncAsyncBridge, but the design is otherwise the same

The methodology — five layers, each addressing one incompatibility, with vendor-specific adapters as the only variable component — is unchanged.

---

## References

- Banco Central do Brasil. *PIX — Instant Payment System*. https://www.bcb.gov.br/estabilidadefinanceira/pix
- Banco Central do Brasil. *PIX Statistics*. Transaction volume and adoption data published quarterly.
- ISO 20022. *Financial Services — Universal Financial Industry Message Scheme*. https://www.iso20022.org
- Federal Reserve Financial Services. *FedNow Service*. https://www.frbservices.org/financial-services/fednow
