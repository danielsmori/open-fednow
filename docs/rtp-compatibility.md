# RTP Compatibility

The Clearing House's RTP® network and the Federal Reserve's FedNow Service are both ISO 20022 instant payment rails operating in the United States. This document describes the current implementation status of RTP support in the OpenFedNow framework and what remains pending institutional onboarding with The Clearing House.

---

## Current Status

**Implemented:**
- `RtpXmlParser` — parses canonical ISO 20022 pacs.008.001.08 XML with XXE protection
- `RtpGateway.receiveTransfer()` — accepts `application/xml` (production format) and `application/json` (sandbox/compatibility); routes inbound messages through the shared Layers 2–4 pipeline

**Pending institutional onboarding with The Clearing House:**
- **TCH certificate validation** — requires TCH digital certificates and network access keys, available only through TCH participation agreements. This is an external access dependency, not a technical unknown — the validation architecture follows the same pattern as Federal Reserve PKI in `FedNowGateway` and is defined in `CertificateManager`.
- **Live TCH dedicated network transport** — RTP uses a private network, not public TLS
- **RTP outbound XML serialization** — serializing `Pacs002Message` responses to the canonical RTP XML envelope
- **Live RTP certification and settlement validation**

---

## The four incompatibilities are materially similar

The problem this framework was built to solve applies to both FedNow and RTP in materially similar ways:

| Incompatibility | FedNow | RTP |
|---|---|---|
| Processing model | Legacy batch vs. real-time event | Legacy batch vs. real-time event |
| Availability | Core has maintenance windows; FedNow is 24/7 | Core has maintenance windows; RTP is 24/7 |
| Protocol | Legacy proprietary APIs vs. ISO 20022 | Legacy proprietary APIs vs. ISO 20022 |
| Concurrency | Legacy not designed for simultaneous load | Legacy not designed for simultaneous load |

The Shadow Ledger, the SyncAsyncBridge, the Saga orchestration, and the idempotency layer all exist because of these incompatibilities. Because the incompatibilities are materially similar across both rails, the components that resolve them are fully shared.

---

## The shared standard: ISO 20022

Both rails use ISO 20022 as their message standard. The core message types are the same:

| Message | FedNow usage | RTP usage |
|---|---|---|
| `pacs.008.001.08` | FI-to-FI credit transfer | FI-to-FI credit transfer |
| `pacs.002.001.10` | Payment status report | Payment status report |
| `pacs.004.002.06` | Payment return | Payment return |
| `camt.056.001.08` | Cancellation request | Cancellation request |
| `camt.029.001.09` | Investigation response | Investigation response |

The ISO 20022 message models in `io.openfednow.iso20022` work for both rails without modification.

---

## What varies between rails

Layer 1 (the API Gateway) is the only component that varies. FedNow and RTP have different:

- **Connectivity model** — FedNow uses REST/JSON over TLS with Federal Reserve PKI certificates. RTP uses ISO 20022 XML over a dedicated network connection with TCH (The Clearing House) certificates.
- **Settlement timing** — FedNow settles via the FedNow settlement service. RTP settles via TCH's multilateral net settlement.
- **Participation rules** — Different application processes, technical certification requirements, and bilateral agreements.
- **Message envelope** — FedNow wraps ISO 20022 in a JSON envelope. RTP uses the canonical XML envelope.

Layers 2–4 do not touch the transport, the network, or the settlement mechanism. They operate on parsed ISO 20022 message objects. The `CoreBankingAdapter` interface, the `ShadowLedger`, the `SagaOrchestrator`, and the `IdempotencyService` do not know which rail the message arrived on.

---

## The extensibility point

The gateway layer is the extensibility point. `FedNowGateway.java` handles FedNow-specific connectivity:
- Validates Federal Reserve PKI client certificates via `CertificateManager`
- Parses the FedNow JSON envelope into `Pacs008Message`
- Returns `Pacs002Message` in the FedNow JSON format

`RtpGateway.java` (reference mode, `io.openfednow.gateway`) handles the RTP inbound path:
- **Implemented:** Parses the RTP canonical ISO 20022 XML envelope via `RtpXmlParser` into `Pacs008Message`
- **Pending:** TCH certificate validation (institutional onboarding required)
- **Pending:** RTP outbound XML serialization and TCH network transport

Both gateways feed into the same `MessageRouter`, which routes to the same layers regardless of source. The `Pacs008Message` that `MessageRouter.routeInbound()` receives does not carry a "which rail" field — it doesn't need one, because the message follows the same rail-agnostic shared pipeline.

---

## Dual-rail operation

An institution running both rails simultaneously would have:

```
FedNow Service  ──→  FedNowGateway  ──┐
                                       ├──→  MessageRouter  ──→  Layers 2–4  ──→  Core
RTP Network     ──→  RtpGateway     ──┘
```

The `MessageRouter` and everything downstream sees no difference. The only operational distinction is that `Pacs002Message` responses must be returned to the correct rail — the router would need to track the inbound source and dispatch accordingly. This is a small addition to `MessageRouter`, not a new pipeline.

---

## Related

- [ADR-0005](adr/0005-dual-rail-architecture-fednow-rtp.md) — Decision to keep Layers 2–4 rail-agnostic
- [architecture.md](architecture.md) — Full five-layer architecture overview
- [anti-corruption-layer.md](anti-corruption-layer.md) — How the ACL isolates rail-specific behavior
