# ADR-0005: Share inbound reference processing where safe

## Status

Accepted as a local architecture decision, with outbound parity and live rail behavior **unverified**. The current route status is in [RTP compatibility](../rtp-compatibility.md).

## Context and decision

FedNow- and RTP-shaped inbound gateway fixtures can parse into a common `Pacs008Message` and call the legacy `MessageRouter` with an explicit source `Rail`. The source is persisted on saga records so local response/cancellation handling can distinguish the entry path. This shares application code and tests for selected synthetic behaviors; it does not make the two networks' message profiles, operating rules, security or settlement interchangeable.

`RtpXmlParser` and `RtpXmlSerializer` provide local XML tests, and `RtpClient` has sandbox and HTTP implementations. `/rtp/send` is disabled because its former direct-client path bypassed screening, funds controls and idempotency. The SQL reliability slice and guarded `/fednow/send` bridge are FedNow-shaped synthetic evaluations only; they do not implement RTP outbound financial-control parity.

## Consequences

Code reuse reduces duplication in this repository but does not measure institution integration effort or remove per-rail certification and operational review. A single Redis Shadow Ledger does not prove all-channel funds availability, and the Redis/SQL writes are not atomic. Any live deployment requires rail-specific specifications, authenticated status, verified core capabilities and participation arrangements. See [the capability matrix](../capability-matrix.md).
