# Banking Hub Trial account-read observation

Observed 2026-11-05 around 18:30 UTC. Evaluated revision: `ffdfc23` on `feature/fiserv-banking-hub-foundation`. Target: Fiserv Banking Hub shared Premier Trial sandbox, host supplied by the user's Trial workspace. Account request follows Fiserv's [Premier Trial Postman collection](https://developer.fiserv.com/product/BankingHub/docs/resources/resources.md) R2026.3.3, **Account Service → Get Account - DDA**, and the public [Premier-DDA Get Account Details](https://developer.fiserv.com/product/BankingHub/api/post/acctservice/acctmgmt/accounts/secured?branch=main&version=Premier&core=Premier-DDA) reference.

| Scenario | Result | Sanitized observation |
|---|---|---|
| `python3 -m unittest discover -s scripts -p 'test_fiserv_trial_preflight.py'` | Pass | 3 local tests. |
| `python3 scripts/fiserv_trial_preflight.py --read-sample-dda` | Pass | One token request followed by one read-only account-details request. Returned account ID matched the configured sample DDA; response contained 30 `AcctBal` entries. Repeated once after changing the runner to read the sample ID from private local configuration; both reads gave the same sanitized result. |
| Balance authority/freshness | Unverified | No `BalType` has yet been mapped to authoritative available funds; one read cannot establish freshness or statefulness. |
| Hold/transfer mutations and lost-response recovery | Not run | No financial mutation was attempted. |

The sample account ID, credentials, token, complete request headers, balances and complete provider response are not included here. The sample account value is held only in an owner-readable local file, not Git. This result proves access to a provider-hosted **sandbox account API** for the published sample. It does not prove connection to a live bank core, other-channel reservation, rail integration, or FedNow validation.

`SHA256SUMS` hashes the listed files at the final PR revision. The account-read observation remains tied to the evaluated revision above; refreshing these hashes does not mean the provider request was rerun. Earlier evidence bundles describe earlier stages at their stated commits.
