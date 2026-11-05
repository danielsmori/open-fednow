# Banking Hub Premier capability assessment

Assessment date: 2026-11-05. Scope: public Banking Hub Premier references and a newly created Trial workspace. **Token authentication is the only provider operation executed.** “Documented” below means a public page describes an API; it does not mean its financial guarantees have been established. No local fixture test is a provider observation.

| Needed operation or guarantee | Public evidence | Current result |
|---|---|---|
| Authentication | [Getting started](https://developer.fiserv.com/product/BankingHub/docs/get-started.md): key/secret Basic token request; shared Premier sandbox trial. | Provider Trial authentication succeeded on 2026-11-05; a Bearer token was returned. This proves only the token endpoint, not core access. Legacy token client uses a different form body and is not compatible evidence. |
| Balance observation and freshness | [Premier-DDA Get Account Details](https://developer.fiserv.com/product/BankingHub/api/post/acctservice/acctmgmt/accounts/secured?branch=main&version=Premier&core=Premier-DDA) returns `AcctBal` entries. | Endpoint specification identified; available-balance type and freshness unverified. No provider balance observed. |
| Atomic sufficient-funds reservation | [Premier Add Account Hold](https://developer.fiserv.com/product/BankingHub/api/post/acctholdservice/stophold/accountHolds?branch=active&version=Premier&core=Premier) describes a hold restriction, amount, reason and expiry. | Endpoint identified; no documented atomic sufficient-funds rule or other-channel enforcement established. Unsupported for outbound authorization. |
| Reservation identity, lookup and expiry | [Get Account Hold](https://developer.fiserv.com/product/BankingHub/api/post/acctholdservice/stophold/accountHolds/secured?branch=active&version=Premier&core=Premier) can retrieve a hold by hold identifier. | Provider-ID lookup described. Recovery after a lost creation response, client-key correlation, expiry behavior and trial statefulness remain unverified. |
| Transfer posting | [Add Transfer, Premier-Immediate](https://developer.fiserv.com/product/BankingHub/api/post/xferservice/payments/transfers?branch=main&version=Premier&core=Premier-Immediate): immediate transfer with memo posting and nightly batch behavior. | Endpoint documented; no mapped implementation or provider execution. Memo-post failure may cause a later batch balance update. No safe hold-consumption claim. |
| Posting idempotency and lookup | Public Add Transfer description mentions a duplicate check specifically for memo postings, subject to institution configuration. | General duplicate prevention, retention window, lost-ack recovery and post lookup unverified. |
| Hold release versus posted reversal | No chosen contract. | Both unsupported; they are different financial operations. |
| Other-channel coordination | No selected institution, channel list or provider test. | Unsupported. Shared sandbox evidence would not establish bank-wide authority. |
| Core plus synthetic rail experiment | Atomic reservation, recovery, expiry and safe posting prerequisites absent. | Disabled. Existing demo remains synthetic SQL core plus synthetic rail. |

The API sidebar also lists transfer retrieval and update operations, but their request identity, result semantics and suitability for lost-ack recovery have not been evaluated. Do not infer them from names. A token response was observed and discarded; no core-account, hold, transfer, static-mock or test-core response was captured.

## Next evidence gate

With the Trial workspace and token flow now established, inspect the full versioned balance, hold, transfer lookup, reversal, and history schemas. Record request keys, errors, timeout rules, amount encoding, account/organization identifiers, and duplicate behavior. Execute read-only balance first with an approved synthetic test account. Permit mutations only if their contract and test-environment rules justify them. Keep unknown outcomes under investigation rather than retrying blindly.
