# Fiserv discovery and local contract result

Recorded 2026-11-05. Evaluated implementation revision: `d8f6e12` (on `feature/fiserv-banking-hub-foundation`, based on `9c7d292`). This bundle documents public-source review and local tests only. It contains no provider responses or credentials.

## Environment and provenance

- Target described in public documentation: Fiserv Banking Hub, Premier API family, `Premier-Immediate` option; shared Premier sandbox trial. No workspace, test account, entitlement, or actual environment connection was available.
- Public sources: [getting started](https://developer.fiserv.com/product/BankingHub/docs/get-started.md) and [Add Transfer](https://developer.fiserv.com/product/BankingHub/api/post/xferservice/payments/transfers?branch=main&version=Premier&core=Premier-Immediate), read 2026-11-05. The public pages establish a token flow and describe a transfer, but not the reservation guarantees needed for the combined send.
- Local toolchain: Maven 3.9.16 with Homebrew OpenJDK 27 on macOS 27.2, aarch64. The standalone `java` launcher did not resolve on this shell; Maven selected the Homebrew runtime.

## Execution

| Check | Result | Evidence/limit |
|---|---|---|
| `mvn -q -Dtest=OutboundCoreGateTest,FiservFixtureUrlTest,VendorReliabilityCapabilityContractTest,FiservAdapterTest test` | Pass | Local tests only; fixture network-error logs are expected negative cases. |
| `mvn -q test` | Pass | Maven exit 0; Surefire XML totals: 624 tests, 0 failures, 0 errors, 0 skipped. Default excludes the `integration` group. |
| Container-backed integration | Unavailable | `docker info` could not connect to the local Docker daemon. |
| Banking Hub authentication/balance/mutation/lookup | Not run | User confirmed no developer access or synthetic test accounts. No external runner exists until an exact contract and test workspace are selected. |
| Core plus simulated rail | Not run | Atomic reservation, recovery, expiry, hold-consuming post, and other-channel coordination have not been established. |

The pass criteria for the new local tests cover unsupported mutations and balance reads, unknown/expired hold gating, missing institution channel evidence, production-send denial, invalid mutation construction, and fixture host confinement. A constructed positive gate test proves only the Boolean condition of the local model, not real provider capability.

`SHA256SUMS` hashes the listed source and discovery documents at the evaluated revision. The manifest and checksum file were added after the evaluated implementation commit. No source or test file changed during evidence packaging.

## Next gate

Obtain an authorized Banking Hub workspace for an identified core and API version, with approved synthetic account fixtures and current agreement/fee details. Store credentials outside Git. Verify official operation schemas and permitted testing before writing or running a provider client. The external result must record a separate provider lookup and balance/hold observation; an HTTP success alone is insufficient.
