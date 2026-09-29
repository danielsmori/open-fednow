# RTP reference compatibility boundary

OpenFedNow has `RtpXmlParser` and `RtpXmlSerializer` utilities, an inbound `/rtp/receive` reference route, a source-rail field on legacy saga records, and `SandboxRtpClient` / `HttpRtpClient` implementations for local transport tests. XML parsing includes XXE protection. The inbound route can accept XML or sandbox JSON and returns a pacs.002-shaped result in the matching format.

**Outbound `/rtp/send` is disabled:** it returns HTTP 503 / TS01 without calling `RtpClient`. The former direct-client path bypassed screening, balance reservation and idempotency. Configuring `RTP_ENDPOINT` activates an HTTP client bean for reference utilities; it does not re-enable the send route or prove TCH network connectivity. Certificate-validation hooks are no-ops without configured material and are not certification evidence.

FedNow and RTP may share application-level concepts, but their actual message profiles, connectivity, participant rules and settlement processes require separate verification. The current Java models and inbound routing do not establish that the same financial controls are safe for both rails. The SQL reliability slice and its guarded `/fednow/send` bridge are synthetic FedNow-shaped evaluations only; they do not implement RTP outbound parity.

A live RTP adaptation would require TCH participation and applicable technical specifications, private-network and credential setup, verified message/return/cancellation behavior, authenticated status, and institution-specific core funds controls. See [the current capability matrix](capability-matrix.md), [known limitations](known-limitations.md), and historical [ADR-0005](adr/0005-dual-rail-architecture-fednow-rtp.md).
