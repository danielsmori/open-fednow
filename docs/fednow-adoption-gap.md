# The FedNow Adoption Gap

Understanding why only ~16% of eligible U.S. financial institutions have connected to FedNow — despite its launch in July 2023 — is essential context for understanding what OpenFedNow is designed to solve.

---

## The Adoption Problem

The Federal Reserve's FedNow Instant Payment Service launched in July 2023. As of early 2026, approximately 1,500 of the nation's 10,000+ eligible financial institutions have connected — roughly 16% of the eligible ecosystem after more than two years.

This is not a cost problem in the traditional sense. FedNow's transaction fees are low — the Federal Reserve charges $0.045 per credit transfer. The adoption barrier lies elsewhere.

---

## What the Industry Says

### American Bankers Association (2025)

In a formal comment to the Office of the Comptroller of the Currency (OCC Docket OCC-2025-0537), the American Bankers Association — representing the $25.1 trillion U.S. commercial banking industry — stated directly:

> "Core service providers have struggled to support the Federal Reserve's FedNow real-time payment system and the ISO 20022 messaging standard... These delays have impeded banks' ability to offer modern payment services."

The ABA further documented that core service providers require **12–18 months** to implement API integrations, and that the three dominant core providers control **72% of the U.S. bank market** (Fiserv 42%, Jack Henry 21%, FIS 9%).

### Community Bank Testimony (2025)

A Chief Financial Officer at a $350M Midwest community bank, submitting an anonymous formal comment to the same OCC proceeding, stated that their bank *"could not implement FedNow due to its Core provider's fees of approximately $0.40 per FedNow transaction"* — making FedNow *"not a viable, profitable option."*

At $0.40 per transaction from the core vendor (versus $0.045 to the Federal Reserve), the economics break for institutions that process high volumes of low-value consumer payments.

### U.S. Faster Payments Council (2024)

The U.S. Faster Payments Council, in the Faster Payments Barometer (2024), documented that **73% of U.S. financial institutions cite legacy core banking systems as a moderate-to-severe obstacle** to FedNow participation.

---

## Why Legacy Core Systems Are the Barrier

The barrier is architectural, not political or financial. It has four components:

**1. Processing model mismatch**
Legacy core banking systems were designed in the 1970s–1990s for batch processing: transactions accumulate during the business day and are posted in bulk overnight. FedNow requires each payment to be processed individually, with a confirmed response returned within 20 seconds. These two models are fundamentally incompatible without a purpose-built bridge.

**2. Availability mismatch**
FedNow operates 24 hours a day, 7 days a week, 365 days a year. Legacy core systems take scheduled maintenance windows — typically 2–4 hours nightly and additional time on weekends. An institution cannot receive or send FedNow payments during its core's maintenance window unless it has a mechanism to operate independently of the core during that period.

**3. Protocol mismatch**
FedNow uses ISO 20022: REST APIs, JSON messaging, UTF-8 encoding, standardized field names. Legacy core systems use proprietary protocols — Fiserv's platforms use a mix of REST (DNA) and SOAP/XML (Precision, Premier); FIS and Jack Henry use their own proprietary APIs. Translation is required at every transaction boundary.

**4. Concurrency mismatch**
Real-time payment networks process transactions simultaneously from thousands of participants. Legacy batch-processing systems were not designed for high-volume concurrent transaction loads. Without careful architectural design, a surge in real-time payment volume can affect the performance of the core system's other operations.

---

## Why This Matters at the National Level

The Federal Reserve's stated goal for FedNow is to make instant payments available to all Americans — including those at community banks and credit unions that collectively serve tens of millions of households. The adoption gap concentrates the benefits of real-time payments at large institutions and leaves community bank customers without access.

The Federal Reserve Bank of Kansas City's 2024 study on core banking market structure noted that the concentration of the core banking market — three vendors serving more than 70% of institutions — means that each vendor's integration timeline creates a direct ceiling on national FedNow adoption.

This is a systemic infrastructure problem, not an institution-level problem. A framework that abstracts the core banking integration into reusable components — available to all institutions without licensing fees — addresses the root cause.

---

## What OpenFedNow Addresses

OpenFedNow resolves all four incompatibilities through its five-layer architecture:

| Incompatibility | OpenFedNow Solution |
|----------------|---------------------|
| Processing model | SyncAsyncBridge: provisional acceptance within FedNow window, async core reconciliation |
| Availability | Shadow Ledger: continuous operation during core maintenance windows |
| Protocol | Anti-Corruption Layer: ISO 20022 ↔ vendor-specific translation per adapter |
| Concurrency | Processing Engine: circuit breakers, idempotency, distributed saga orchestration |

The Apache 2.0 license ensures that any institution — regardless of size — can deploy the framework without licensing fees. The 85/15 principle ensures that building three adapters (Fiserv, FIS, Jack Henry) covers the majority of the market.

---

## References

- American Bankers Association. Comment to OCC Docket OCC-2025-0537. Signed Krista Shonk, SVP & Senior Counsel. 2025.
- Anonymous community bank CFO. Comment to OCC Docket OCC-2025-0537. 2025.
- U.S. Faster Payments Council / Finzly. *Faster Payments Barometer*. 2024.
- Federal Reserve Bank of Kansas City. *Market Structure of Core Banking Services Providers*. March 2024.
- Federal Reserve Financial Services. *FedNow Service Participant List*. https://www.frbservices.org/financial-services/fednow/fednow-participant-list.html
