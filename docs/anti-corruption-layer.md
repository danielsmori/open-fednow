# Anti-Corruption Layer: reference adapter boundary

The `CoreBankingAdapter` interface separates the gateway/router's synthetic payment model from local core-shaped operations for balance reads, posting and health. `SyncAsyncBridge` exercises delayed core responses. FIS-, Fiserv- and Jack Henry-shaped implementations construct JSON or SOAP requests and map fixture responses. Their tests use WireMock; no vendor environment or institution-specific specification was available for verification.

The shared code demonstrates a possible separation of concerns, not a measured percentage of reusable work or coverage of vendor products. Product lines can differ substantially, and an institution would have to verify the applicable API version, authentication, posting semantics, error codes and reconciliation behavior. The [versioned capability matrix](reliability/adapter-capabilities-v1.md) identifies what the current interface cannot assert: authoritative/fresh balance, enforceable all-channel reservations, reservation lookup and expiry, posting/reversal deduplication, and outcome lookup after a lost acknowledgment.

The SQL reliability service uses its own synthetic core fixture and does not call these vendor adapters. A vendor-backed outbound payment must be refused until the missing capabilities are implemented and verified in the relevant institutional environment. A mock balance read or a configured exclusive-control flag is insufficient.

For the application flow, see [architecture](architecture.md), [reliability architecture](reliability/architecture.md), and [known limitations](known-limitations.md).
