# Capability and evidence matrix

September 23, 2026. This is a reference implementation evaluated with synthetic data. No vendor or rail certification, independent reproduction, production savings, or live bank reuse is established by these tests.

| Area | Implemented / simulated evidence | Independent examination | Unfinished work | External dependencies |
|---|---|---|---|---|
| FedNow outbound controls | Router screening, funds check, reservation, saga; mocked rail with real test databases in `OutboundPaymentIntegrationTest` | No independent reproduction recorded | Submission uncertainty, concurrent duplicate delivery, cross-store crash recovery | Applicable rail message/transport specifications, credentials, certification, institutional review |
| RTP outbound | Transport/serialization utilities; `/rtp/send` deliberately returns 503 / TS01 without submitting | None recorded | Financial-control parity on actual send route; disabled in this release | TCH participation, specifications, connectivity and credentials |
| Inbound routing | FedNow/RTP use shared router and source-rail field; synthetic tests | None recorded | Validate provisional statuses, return/cancellation behavior against applicable rail rules | Rail message tests and institutional procedures |
| Screening | Optional denylist/amount/velocity rules; timeout, error and invalid result reject by default; explicit fail-open override | None recorded | Production screening policy, bounded execution, operational review workflow; no AI or sanctions engine | Institution-selected screening service and evaluation data |
| Downtime | Outbound disabled by default; bridge guard regression tests | None recorded | Cross-channel balance correctness, crash/replay and out-of-order external-event validation | Authoritative core balances and actual processor interface |
| Jack Henry adapter | SOAP request construction and mock assertions | Preliminary criticism relayed by developer relations; corrected implementation not verified | Scoped examination against applicable balanced-transaction specification | Versioned vendor documentation, authorized sandbox and credentials |
| Other vendor adapters | Reference implementations and mock contracts | None recorded | Vendor compatibility and operational validation | Vendor specifications and authorized environments |
| Submission uncertainty | `UncertainSubmissionReproducerTest` demonstrates current synthetic rejection and compensation after a delayed response | None recorded | Durable unresolved state, status inquiry, safe retry, restart/timeout behavior, late status | Applicable operating procedures and message specification versions |

The first evaluation covers synthetic FedNow controls only. A passing regression suite is developer evidence, not independent examination. See [evaluation instructions](evaluation.md).
