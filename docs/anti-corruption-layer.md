# Anti-Corruption Layer

The Anti-Corruption Layer (ACL) is the architectural boundary between the ISO 20022 world of FedNow and the proprietary world of each core banking vendor.

## Problem

FedNow uses the ISO 20022 international messaging standard: JSON over HTTPS, UTF-8 encoding, standardized field names and data types. Legacy core banking systems use proprietary formats: vendor-specific APIs, custom field names, fixed-point arithmetic, often ASCII or EBCDIC encoding.

These two worlds cannot communicate directly without translation — and the translation is different for every vendor.

## The 85/15 Principle

Approximately 85% of the integration architecture is identical across all vendors. Only the adapter — the piece that speaks each vendor's proprietary language — varies. This adapter represents ~15% of the total engineering scope.

This means building adapters for Fiserv, Jack Henry, and FIS makes the full framework available to >70% of U.S. financial institutions without duplicating the underlying work.

## Vendor Adapter Status

| Vendor | Platforms | Market Share | Status |
|--------|-----------|-------------|--------|
| Fiserv | DNA, Precision, Premier, Cleartouch | 42% banks, 31% CUs | In Development (Phase 1) |
| FIS | Horizon, IBS | 9% banks | Planned (Phase 2) |
| Jack Henry | SilverLake, Symitar, CIF 20/20 | 21% banks, 12% CUs | Planned (Phase 3) |

## Sync-to-Async Bridge

FedNow requires a synchronous response within 20 seconds. Legacy core systems process asynchronously. The SyncAsyncBridge resolves this by:

1. Attempting a synchronous call to the core with a 15-second timeout
2. If the core responds in time, returning that result directly to FedNow
3. If the core does not respond in time, returning a provisional acceptance to FedNow based on Shadow Ledger validation, and registering the transaction for async reconciliation

## Related classes

- `CoreBankingAdapter.java` — vendor-neutral interface
- `MessageTranslator.java` — field mapping and encoding translation
- `SyncAsyncBridge.java` — timing decoupling
- `FiservAdapter.java`, `FisAdapter.java`, `JackHenryAdapter.java` — vendor implementations
