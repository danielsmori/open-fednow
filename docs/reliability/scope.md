# Reliability evaluation scope

Reviewed source: Federal Reserve Financial Services, *FedNow Service Operating Procedures*, version 3.6, effective April 28, 2026. The [official operating-circulars index](https://www.frbservices.org/resources/rules-regulations/operating-circulars/) listed this version on September 28, 2026. The operating procedures are public rules, not an implementation or transport specification. Restricted message, security, institution, and vendor specifications were unavailable for this work.

OpenFedNow is a synthetic reference integration. Its JSON `/transfers` client, in-memory rail, mock core adapters, and tests do not establish live FedNow connectivity, participant certification, vendor compatibility, or production safety. Outbound RTP remains disabled. Bridge-mode outbound sends are disabled by default. An ACSP emitted for inbound bridge processing is an application experiment and must not be represented as a verified FedNow provisional response; the public procedures distinguish service settlement from receiver posting.

The target audience is engineers evaluating failure handling and adapting a scenario contract to their own controlled test environments. A scenario pass means the specified observable invariant held for the tested source, fixture, schedule, and dependencies. It does not establish reliability in untested schedules or financial institutions.

The existing outbound route retains an unresolved cross-store boundary: the Redis balance mutation and SQL saga/audit writes cannot commit atomically. A cached balance cannot authorize a send safely when other channels may debit the core independently. Do not enable a live or institution-facing send based on this route. The reference evaluation will use synthetic accounts only, and any capability it cannot prove must fail closed.

No live-network result, bank adoption, economic benefit, or personal contribution is asserted here.
