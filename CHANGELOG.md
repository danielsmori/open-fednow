# Changelog

Notable changes to OpenFedNow, newest first. The project has not yet published a tagged release; entries before the first tag are grouped by the date they were merged to `main`. From the first tag onward, versions follow [Semantic Versioning](https://semver.org/) and each release publishes its evidence under [`docs/reliability/evidence/`](docs/reliability/evidence/). See [docs/releasing.md](docs/releasing.md).

## Unreleased

### Added
- README now leads with the one-command test run and a plain description of the three failures tested.
- Affiliation and trademark notice in the README and a `NOTICE` file.
- `SECURITY.md`, `CODE_OF_CONDUCT.md`, `GOVERNANCE.md`, `CITATION.cff`, issue templates and a pull-request template.
- Release process ([docs/releasing.md](docs/releasing.md)) and a tag-triggered release workflow.

### Changed
- The full component table moved from the README to [docs/status.md](docs/status.md), and the development phases to [docs/roadmap.md](docs/roadmap.md).
- `docs/whitepaper.pdf` replaced by the revised November 9, 2026 preprint.

## 2026-11-10

### Added
- TigerBeetle-backed competing-payments experiment: the unchanged S16 test runs with an independently maintained ledger holding the funds ([#111](https://github.com/danielsmori/open-fednow/pull/111)).

## 2026-11-09

### Added
- Portable Send Reliability Profile v1: four scenarios, a sample driver, a one-command smoke run and a standard report ([#110](https://github.com/danielsmori/open-fednow/pull/110)).
- Read-only Banking Hub Premier adapter and trial evidence ([#107](https://github.com/danielsmori/open-fednow/pull/107)).

## 2026-11-05

### Added
- Banking Hub contract documentation and an outbound core safety boundary ([#106](https://github.com/danielsmori/open-fednow/pull/106)).

## 2026-11-04

### Added
- Local payment integration walkthrough with a browser console and a command-line verifier ([#105](https://github.com/danielsmori/open-fednow/pull/105)).

## 2026-10-02

### Added
- Pinned practical reliability evaluation of October 1, 2026, with worked examples and traces ([#90](https://github.com/danielsmori/open-fednow/pull/90)).

## 2026-09-29

### Added
- Synthetic payment reliability service: durable operation ownership, fund holds, status inquiry and recovery, with an external scenario evaluator ([#87](https://github.com/danielsmori/open-fednow/pull/87)).

### Changed
- Outbound payments are quarantined when the rail status is unknown ([#82](https://github.com/danielsmori/open-fednow/pull/82)).
- Public claims aligned with the merged reliability release ([#88](https://github.com/danielsmori/open-fednow/pull/88), [#89](https://github.com/danielsmori/open-fednow/pull/89)).

## 2026-09-25

### Changed
- Payment evaluation controls hardened and CI repaired ([#80](https://github.com/danielsmori/open-fednow/pull/80)).
- Dependency scanning moved to Trivy ([#79](https://github.com/danielsmori/open-fednow/pull/79)).

## 2026-07-27

### Added
- ADR-0010 on bridge-mode fraud risk and spending authorized through other channels ([#74](https://github.com/danielsmori/open-fednow/pull/74)).

## 2026-07-24

### Added
- `CardAuthorizationEventListener` port for card-authorization balance events, contributed by [@AbhiP47](https://github.com/AbhiP47) ([#71](https://github.com/danielsmori/open-fednow/pull/71)).

## 2026-07-16 to 2026-07-17

### Added
- `openfednow.bridge-mode.allow-sends` guard, off by default ([#66](https://github.com/danielsmori/open-fednow/pull/66)).

### Fixed
- Offsetting general-ledger entry added to the Jack Henry-shaped `TrnAdd` request so the transaction balances ([#70](https://github.com/danielsmori/open-fednow/pull/70)).

## 2026-07-01 to 2026-07-03

### Added
- Detached JWS message signing, outbound return submission, a Kafka dead-letter queue, PII redaction in logs, a non-USD currency guard, rotatable admin credentials and the browser demo console.

## 2026-05-15 to 2026-05-26

### Added
- Vendor-shaped Fiserv, FIS and Jack Henry adapters with local fixture tests.
- RTP reference transport utilities.
- Saga recovery, timeout monitoring and compensation retry; admin query endpoints and audit log; rate limiting; cancellation handling; fraud pre-screening port.
- Transaction boundaries, idempotent reversals, security headers and graceful shutdown.

## 2026-04-20

### Added
- Initial commit: architecture, ISO 20022-shaped message models, sandbox adapter and Shadow Ledger.
