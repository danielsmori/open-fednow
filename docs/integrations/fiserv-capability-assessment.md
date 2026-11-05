# Banking Hub Premier capability assessment

Assessment date: 2026-11-05. Scope: public Banking Hub `Premier-Immediate` references, with **no** project workspace or provider execution. “Documented” below means a public page describes an API; it does not mean its financial guarantees have been established. No local fixture test is a provider observation.

| Needed operation or guarantee | Public evidence | Current result |
|---|---|---|
| Authentication | [Getting started](https://developer.fiserv.com/product/BankingHub/docs/get-started.md): key/secret Basic token request; shared Premier sandbox trial. | Specification-supported for token acquisition only; untested. Legacy token client uses a different form body and is not compatible evidence. |
| Balance observation and freshness | No chosen account/balance operation mapped to a versioned schema. | Unverified; no provider amount, currency, freshness or tenant evidence. |
| Atomic sufficient-funds reservation | No hold/reservation API and atomicity rule identified in the reviewed pages. | Unsupported for outbound authorization. A prior balance read cannot substitute. |
| Reservation identity, lookup and expiry | No chosen contract. | Unsupported. Lost replies and expired holds must remain unresolved. |
| Transfer posting | [Add Transfer, Premier-Immediate](https://developer.fiserv.com/product/BankingHub/api/post/xferservice/payments/transfers?branch=main&version=Premier&core=Premier-Immediate): immediate transfer with memo posting and nightly batch behavior. | Endpoint documented; no mapped implementation or provider execution. Memo-post failure may cause a later batch balance update. No safe hold-consumption claim. |
| Posting idempotency and lookup | Public Add Transfer description mentions a duplicate check specifically for memo postings, subject to institution configuration. | General duplicate prevention, retention window, lost-ack recovery and post lookup unverified. |
| Hold release versus posted reversal | No chosen contract. | Both unsupported; they are different financial operations. |
| Other-channel coordination | No selected institution, channel list or provider test. | Unsupported. Shared sandbox evidence would not establish bank-wide authority. |
| Core plus synthetic rail experiment | Atomic reservation, recovery, expiry and safe posting prerequisites absent. | Disabled. Existing demo remains synthetic SQL core plus synthetic rail. |

The API sidebar also lists transfer retrieval and update operations, but their request identity, result semantics and suitability for lost-ack recovery have not been evaluated. Do not infer them from names. No provider response, static mock response, or provider test-core result was captured.

## Next evidence gate

After access exists, select one exact Premier API release and environment; inspect the official balance, reservation, transfer lookup, reversal, and history schemas. Record request keys, errors, timeout rules, amount encoding, account/organization identifiers, and duplicate behavior. Execute read-only balance first with an approved synthetic test account. Permit mutations only if their contract and test-environment rules justify them. Keep unknown outcomes under investigation rather than retrying blindly.
