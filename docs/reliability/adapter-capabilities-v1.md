# Core adapter reliability capability contract v1.0

The executable `CoreReliabilityCapabilities` profile is a **gate**, not vendor certification. `CoreBankingAdapter` exposes posting, balance read and health methods. It has no reservation, reversal, expiry, or lost-acknowledgment lookup method. The profile defaults to unsupported; the FIS-, Fiserv- and Jack Henry-shaped implementations declare only local fixture evidence. `permitsLiveOutboundReservation()` is false for all three. The controlled `/fednow/send` bridge uses the separate SQL synthetic account and rail, never a vendor-shaped adapter.

| Capability | FIS-shaped | Fiserv-shaped | Jack Henry-shaped | Meaning |
|---|---|---|---|---|
| Balance read | Local fixture | Local fixture | Local fixture | Existing HTTP/SOAP client decodes mock balance responses; authority and freshness remain unverified. |
| Balance authority/freshness | Interface only | Interface only | Interface only | No timestamp, source-of-truth, all-channel view or freshness SLA contract. |
| All-channel reservation | Unsupported | Unsupported | Unsupported | No enforceable reservation operation; balance read cannot authorize a safe send. |
| Reservation lookup/expiry | Unsupported | Unsupported | Unsupported | No identity, expiry or recovery lookup. |
| Posting identity/deduplication | Interface only | Interface only | Interface only | Request construction carries transaction/correlation fields; actual vendor deduplication was not tested. |
| Reversal identity/deduplication | Unsupported | Unsupported | Unsupported | No adapter method for an operation-keyed reversal. |
| Lost-acknowledgment lookup | Unsupported | Unsupported | Unsupported | POST timeout maps to `TIMEOUT`; no follow-up operation lookup. |
| Dependency outage | Local fixture | Local fixture | Local fixture | Health and network-error behavior exercised against controlled responses, not a vendor environment. |
| Other-channel coordination | Unsupported | Unsupported | Unsupported | No proof of coordination with cards, ACH, teller or other debits. |
| Actual vendor environment | Unavailable | Unavailable | Unavailable | No live credentialed tests or certification claim. |

The common `VendorReliabilityCapabilityContractTest` checks that all three adapters expose the same conservative profile and fail the live-send capability gate. The existing `FisAdapterTest`, `FiservAdapterTest`, and `JackHenryAdapterTest` exercise their actual JSON/SOAP serialization, authentication headers, response/reason mapping and timeout handling against WireMock vendor-shaped fixtures. `scripts/evaluate.py` runs these four classes as one named `adapters` selection and archives their JUnit XML. Passing them means the authored fixtures matched the authored adapter code; it is not independent vendor validation. Restricted or institution-specific vendor specifications and environments were not available.

The safe integration decision is to refuse vendor-backed reliable outbound sends until an institution can implement and verify the missing contract. The SQL exclusive-control fixture is a deliberately bounded synthetic account; toggling its `coreAvailable` flag tests failure handling but does not grant a bank-wide reservation guarantee.
