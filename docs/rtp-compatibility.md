# RTP Compatibility

The Clearing House's RTP® network and the Federal Reserve's FedNow Service are both ISO 20022 instant payment rails operating in the United States. This document explains why the OpenFedNow framework architecture is rail-agnostic — and what would actually need to change to connect to RTP instead of, or in addition to, FedNow.

**Important:** RTP connectivity is not implemented. This document describes architectural intent, not working code. The claim is that the framework was designed so that adding RTP requires changing only Layer 1. Layers 2–4 are already rail-agnostic.

---

## The four incompatibilities are identical

The problem this framework was built to solve applies equally to RTP:

| Incompatibility | FedNow | RTP |
|---|---|---|
| Processing model | Legacy batch vs. real-time event | Legacy batch vs. real-time event |
| Availability | Core has maintenance windows; FedNow is 24/7 | Core has maintenance windows; RTP is 24/7 |
| Protocol | Legacy proprietary APIs vs. ISO 20022 | Legacy proprietary APIs vs. ISO 20022 |
| Concurrency | Legacy not designed for simultaneous load | Legacy not designed for simultaneous load |

The Shadow Ledger, the SyncAsyncBridge, the Saga orchestration, and the idempotency layer all exist because of these four incompatibilities. Since the incompatibilities are identical, the components that resolve them are identical.

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

An `RtpGateway.java` (stubbed in `io.openfednow.gateway`) would handle RTP-specific connectivity:
- Validate TCH certificates
- Parse the RTP XML envelope into the same `Pacs008Message`
- Return `Pacs002Message` in RTP XML format

Both gateways feed into the same `MessageRouter`, which routes to the same layers regardless of source. The `Pacs008Message` that `MessageRouter.routeInbound()` receives does not carry a "which rail" field — it doesn't need one, because the processing is identical.

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

## What is not implemented

- `RtpGateway.java` exists as a documented stub. It does not make real connections to The Clearing House's network.
- The RTP XML envelope parser is not implemented.
- TCH certificate validation is not implemented.
- The `MessageRouter` does not yet track inbound rail source.

See [ADR-0005](adr/0005-dual-rail-architecture-fednow-rtp.md) for the architectural decision record.

---

## Related

- [ADR-0005](adr/0005-dual-rail-architecture-fednow-rtp.md) — Decision to keep Layers 2–4 rail-agnostic
- [architecture.md](architecture.md) — Full five-layer architecture overview
- [anti-corruption-layer.md](anti-corruption-layer.md) — How the ACL isolates rail-specific behavior
