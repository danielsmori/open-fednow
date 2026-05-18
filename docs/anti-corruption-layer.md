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
| Fiserv | DNA, Precision, Premier, Cleartouch | 42% banks, 31% CUs | Implemented |
| FIS | Horizon, IBS | 9% banks | Implemented |
| Jack Henry | SilverLake, Symitar, CIF 20/20 | 21% banks, 12% CUs | Implemented |

## Vendor-Specific Implementation Notes

### Fiserv

Fiserv's platforms are not uniform — each product line uses a different API style, which is why Fiserv integration is non-trivial despite the vendor being the same:

- **DNA:** RESTful API. Most compatible with ISO 20022's REST/JSON model; the translation overhead is primarily field mapping and amount formatting.
- **Precision / Premier:** SOAP/XML API. Requires XML marshalling/unmarshalling and character encoding conversion (Fiserv Precision/Premier use ASCII, not UTF-8).
- **Cleartouch:** Similar to Precision/Premier in API style.

Additional Fiserv implementation considerations:
- Amount fields are represented as fixed-point strings (e.g., `"10050"` for $100.50), not as decimal numbers
- Session tokens expire after 30 minutes of inactivity — the adapter must handle token refresh transparently
- Fiserv-specific rejection codes must be mapped to ISO 20022 reason codes (e.g., Fiserv `INSUF_FUNDS` → ISO `AM04`)

### FIS

FIS Horizon and IBS use proprietary REST APIs distinct from Fiserv's. Field naming conventions differ significantly from ISO 20022 — amounts are decimal strings rather than fixed-point cent integers, credentials use HTTP Basic auth, and the institution ID is passed as a `feId` header. The FIS adapter is implemented in `FisAdapter.java`.

### Jack Henry

Jack Henry SilverLake (banks) and Symitar (credit unions) are both served by the jXchange SOAP Service Gateway, which provides a unified API across platforms. The adapter is implemented in `JackHenryAdapter.java`.

Additional Jack Henry implementation notes:
- **Protocol:** SOAP/XML over HTTPS (not REST). All operations — `TrnAdd`, `AcctBalInq`, and `Ping` — POST to the same Service Gateway endpoint, routed by the `SOAPAction` header.
- **jXchangeHdr:** Every request except `Ping` must include a `jXchangeHdr_CType` SOAP header with required fields: `AuditUsrId`, `AuditWsId`, `ValidConsmName` (max 15 chars), `ValidConsmProd` (max 25 chars), and `InstRtId` (9-digit ABA routing number).
- **Authentication:** OAuth 2.0 client credentials grant with HTTP Basic auth header (`Authorization: Basic base64(clientId:clientSecret)`), the same pattern as FIS. Jack Henry migrated from legacy WS-Security username/password authentication to OAuth 2.0 in May 2025.
- **Amount format:** Plain decimal strings (e.g. `"1000.50"`), same as FIS. Not fixed-point cents.
- **Error codes:** Numeric values in SOAP fault `ErrRec/ErrCode` (e.g. `3050` for insufficient funds), mapped to ISO 20022 codes by `JackHenryReasonCodeMapper`.

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
