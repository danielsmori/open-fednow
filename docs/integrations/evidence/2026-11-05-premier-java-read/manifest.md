# Premier Trial Java read-only result

Observed 2026-11-05. Evaluated implementation revision: `8a75c74aca7f077ac97c6a7a0d64a99c002af006` on `feature/fiserv-premier-readonly-adapter`, based on merged PR #106 (`5c0fcf8`). Target: Banking Hub **shared Premier Trial sandbox**, not a named dedicated test core or institution environment. API family: Premier-DDA Get Account Details. Source: [Fiserv API reference](https://developer.fiserv.com/product/BankingHub/api/post/acctservice/acctmgmt/accounts/secured?branch=main&version=Premier&core=Premier-DDA) and [published Trial collection](https://developer.fiserv.com/product/BankingHub/docs/resources/resources.md).

Toolchain: macOS 27.2 aarch64, Maven 3.9.16, Homebrew OpenJDK 27. The repository targets Java 17; CI provides that separate check. No credential, token, account number, balance amount, full provider response, or request header value is in this bundle.

| Scenario | Result | Observation and limit |
|---|---|---|
| `mvn -q -Dtest=PremierTrialAdapterTest test` | Pass | 4 local tests: Premier request shape, response identity, HTTP failure, and strict CERT destination. Synthetic responses only. |
| `mvn -q test` | Pass | Surefire totals: 628 tests, 0 failures, 0 errors, 0 skipped. Default excludes tagged integration tests. Existing negative-case logs appeared but Maven exited 0. |
| `mvn -q spring-boot:run -Dspring-boot.run.main-class=io.openfednow.acl.adapters.fiserv.PremierTrialProbe -Dspring-boot.run.arguments=--read-sample-dda` | Pass | One token request and one read-only account-details request. Account identity matched the configured published sample DDA; 30 `AcctBal` entries returned. Token and response were discarded. |
| Available-funds mapping | Unverified | No `AcctBal` type, currency/amount semantics or freshness guarantee has been established as authoritative available funds. The adapter returns no `ReliableOutboundCorePort.Balance`. |
| Hold creation, lookup, release, transfer and lost-response recovery | Not run | No mutation test fixture or named test core is available. All financial operations remain `UNSUPPORTED`; no provider mutation request was made. |
| Core plus synthetic rail | Not run | Atomic reservation, recoverability, expiry, hold-consuming post and other-channel coordination remain unverified. |

`SHA256SUMS` hashes the implementation, test, and integration documentation at the evaluated source revision. The bundle records a provider-hosted sandbox account read, not test-core financial behavior, Fiserv validation, live banking or FedNow connectivity. No success claim is based solely on HTTP status: the account ID and response structure were also checked. The result does not prove balance accuracy or state changes.
