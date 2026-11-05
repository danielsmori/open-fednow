# Banking Hub Trial authentication observation

Observed 2026-11-05 against the user-created Banking Hub Trial workspace, whose supplied host is `https://bankinghub-cert.fiservapis.com/banking/efx/v1`. Evaluated code revision: `54b4786` on `feature/fiserv-banking-hub-foundation`. Product/API family: Banking Hub Premier; token path documented in [Getting Started](https://developer.fiserv.com/product/BankingHub/docs/get-started.md). Environment described by Fiserv as a shared Premier sandbox. Its statefulness and any connection to a provider-hosted test core were not established.

| Scenario | Result | Observation |
|---|---|---|
| Local configuration validation: `python3 scripts/fiserv_trial_preflight.py` | Pass | Approved CERT host and required fields present; no request made. |
| Local runner tests: `python3 -m unittest discover -s scripts -p 'test_fiserv_trial_preflight.py'` | Pass | 3 tests: destination refusal, missing credentials, config parsing. |
| One token request: `python3 scripts/fiserv_trial_preflight.py --authenticate` | Pass | HTTPS POST to the documented CERT token URL using Basic key/secret and `grant_type=client_credentials`; response contained a Bearer token and reported `expires_in=899000` milliseconds. Token was discarded and never printed or retained. |
| Premier account/hold/transfer API | Not run | No Fiserv-approved synthetic account fixture is configured; public API descriptions do not establish financial guarantees. |

No credential, token, full provider response, account identifier, customer data, or request header value is included here. This result establishes **token authentication only**. It does not show authorization for any specific API, account balance accuracy, a durable reservation, idempotency, or live core access. The trial may simulate API behavior; this has not been classified by observation.

`SHA256SUMS` hashes the listed files at the final PR revision. The authentication observation remains tied to the evaluated revision above; refreshing these hashes does not mean the token request was rerun. The earlier local-evaluation bundle remains separate.
