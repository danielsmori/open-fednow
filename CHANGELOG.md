# Changelog

Notable changes to OpenFedNow, newest first. The first tagged release is 0.1.0. Entries before it are grouped by the date they were merged to `main`. From 0.1.0 onward, versions follow [Semantic Versioning](https://semver.org/) and each release publishes its evidence under [`docs/reliability/evidence/`](docs/reliability/evidence/). See [docs/releasing.md](docs/releasing.md).

## Unreleased

### Added
- The component for the per-account aggregate send cap during bridge mode ([#68](https://github.com/danielsmori/open-fednow/issues/68)): a configurable ceiling (`openfednow.bridge-mode.max-aggregate-send-amount`, unset = disabled) on the total a single account can send against the cached Shadow Ledger balance in one maintenance window, counted atomically with the debit. Fails closed — no time-based reset; `expected-window-hours` only alerts ([#139](https://github.com/danielsmori/open-fednow/pull/139)).
- Enforcement of that cap on outbound sends ([#140](https://github.com/danielsmori/open-fednow/pull/140)). Sends that would exceed it are rejected with ISO 20022 `AM04` while the core is offline, and until reconciliation confirms the balance after it returns. A send is released from the cap when it is reversed, or when reconciliation confirms it and its rail outcome is final; unresolved sends stay counted. Accounts with an open window are not reconciled while the core is offline. Adds the `bridge_mode.aggregate.cap_exceeded` counter and `GET`/`DELETE /admin/accounts/{accountId}/bridge-aggregate` to inspect a window and, as an audited override, clear one. Automatic reconciliation on core recovery follows below; the remaining metrics follow in the last PR on the issue.
- Automatic reconciliation when the core comes back online ([#146](https://github.com/danielsmori/open-fednow/pull/146)), so accounts capped during a maintenance window are released without an operator. A durable marker in Redis records that a reconciliation is owed; any instance that sees the core online starts a cycle, and a PostgreSQL advisory lock makes sure only one runs at a time across instances. The marker is cleared only when a cycle reconciles every account and leaves no account capped; otherwise the cycle is retried with exponential backoff. New settings `openfednow.reconciliation.auto-on-core-recovery` (default `true`) and `auto-retry-backoff-initial-seconds` / `-max-seconds`; new metrics `reconciliation.auto.cycles` and `reconciliation.auto.consecutive_unsettled`. Requires session pooling for PostgreSQL connections (not PgBouncer transaction mode).
- The remaining #68 observability and docs ([#PR](https://github.com/danielsmori/open-fednow/pull/PR)): a `bridge_mode.aggregate.pct_of_cap` distribution recorded at each counted send (no per-account tag), and sampled gauges `bridge_mode.aggregate.open_windows`, `.windows_near_cap` (≥80%) and `.windows_overdue` (open longer than `expected-window-hours`, which now feeds this alert); a new [`docs/bridge-mode-aggregate-cap.md`](docs/bridge-mode-aggregate-cap.md) with the semantics, configuration, metrics, operator runbook and deployment requirements (Redis eviction/persistence, PgBouncer session pooling, pgjdbc socket timeouts); updates to [ADR-0010](docs/adr/0010-bridge-mode-fraud-risk.md), `docs/known-limitations.md` and `docs/shadow-ledger.md`. New setting `openfednow.bridge-mode.metrics-sample-interval-seconds` (default 60).

### Fixed
- Reconciliation no longer erases payments that land while it runs, and no longer confirms ledger rows the reconciled balance never included ([#142](https://github.com/danielsmori/open-fednow/issues/142), [#144](https://github.com/danielsmori/open-fednow/issues/144), [#148](https://github.com/danielsmori/open-fednow/pull/148)). Each account's balance carries a version that every write bumps; a correction applies only if nothing was written since reconciliation read the balance, otherwise the account is retried (up to 3 times in the cycle, then in the next cycle). Only rows covered by that read are confirmed.
- Balance corrections on accounts with ids longer than 11 characters failed to write their `RECONCILIATION` audit row (the synthetic transaction id overflowed its column), so those corrections went unrecorded ([#148](https://github.com/danielsmori/open-fednow/pull/148)).
- Fixes from a bug bash of the bridge-mode aggregate send cap ([#148](https://github.com/danielsmori/open-fednow/pull/148)): a capped debit whose audit row fails to insert is undone instead of leaving the account capped indefinitely; a fully reversed window no longer keeps the account capped; a cap that rounds to zero cents or overflows is rejected at startup; if the core goes offline between the bridge-mode guard and the cap, the send is rejected (`TS01`) or counted rather than sent uncounted; reconciliation counts each deferred or failed account once and, with the cap disabled, ignores leftover windows; automatic reconciliation's backoff and `reconciliation.auto.consecutive_unsettled` no longer stick after a Redis outage or once another instance has settled the backlog; inbound payments are marked as owing reconciliation even if the queue publish fails; the reconciliation lock's connection has a network timeout.

### Changed
- A reconciliation run is now reported `successful` (in the `/admin/reconcile` response and `reconciliation_run.successful`) only if no account was deferred or failed, not merely if no discrepancy was found. Runs during an outage, or that meet a busy account, now report `successful=false`, and the summary says how many accounts were deferred, failed, or remain capped. Alerting on `successful = FALSE` will see these runs ([#148](https://github.com/danielsmori/open-fednow/pull/148)).

## 0.1.0 - 2026-11-13

First tagged release, published as [v0.1.0](https://github.com/danielsmori/open-fednow/releases/tag/v0.1.0) with the container image `ghcr.io/danielsmori/open-fednow:0.1.0` (`sha256:55c071c41a4989fef5ba53c4e29090c609c9adcb0bbc0fb55538e44b0cb833de`, `linux/amd64` and `linux/arm64`). It contains everything listed under the dated headings below, plus the changes in this section. The commands, source version and raw results for this release are in [`docs/reliability/evidence/release-0.1.0/`](docs/reliability/evidence/release-0.1.0/manifest.md).

The evidence bundle was recorded at `589ae8d`; the tag points at `a4034bd`. Between those two commits no Java source, `pom.xml`, scenario definition, profile, reference target or rail simulator changed. The differences are the harness's port allocation and database settings, two helper functions added to `run.py`, and the evaluator's console summary.

### Added
- Container image for the gateway, built from a pinned two-stage `Dockerfile` and published to `ghcr.io/danielsmori/open-fednow` for `linux/amd64` and `linux/arm64` on a release tag, with a CycloneDX SBOM per platform, a recorded image digest, and a Trivy image scan that blocks publishing on HIGH or CRITICAL findings ([#121](https://github.com/danielsmori/open-fednow/pull/121)).
- `compose.app.yml`, an opt-in Compose file that runs the gateway inside the Compose network. The backing services then publish no host ports and only the gateway's port is published, on `127.0.0.1`, moved by `APP_PORT`, so two checkouts can run the whole stack at once ([#127](https://github.com/danielsmori/open-fednow/pull/127)).
- `scripts/preflight.py`, a clean-checkout check for JDK 17, Maven, Python, Docker, Compose and the host ports that names the fix for each failure ([#118](https://github.com/danielsmori/open-fednow/pull/118)), with `--mode app` for the containerised stack and a Compose 2.24 minimum version check ([#127](https://github.com/danielsmori/open-fednow/pull/127)).
- [ADR-0011](docs/adr/0011-evaluate-the-host-run-jvm.md): the reliability evidence describes the host-run JVM, not the container, and the reasons that would have to be addressed before the evaluator could target a container.
- A weekly and pull-request Trivy scan of the container image, which sees transitive dependencies and base-image OS packages that the existing filesystem scan does not.
- README now leads with the one-command test run and a plain description of the three failures tested.
- Affiliation and trademark notice in the README and a `NOTICE` file.
- `SECURITY.md`, `CODE_OF_CONDUCT.md`, `GOVERNANCE.md`, `CITATION.cff`, issue templates and a pull-request template.
- Release process ([docs/releasing.md](docs/releasing.md)) and a tag-triggered release workflow.

### Changed
- The release workflow can be run manually as a dry run that builds, scans and writes SBOMs without pushing an image or creating a release.
- `docker-compose.yml` no longer fixes container names, so a second Compose project starts without renaming or removing another one's containers. Host ports bind `127.0.0.1` only and move with `POSTGRES_PORT`, `REDIS_PORT`, `RABBITMQ_PORT` and `RABBITMQ_MANAGEMENT_PORT` ([#118](https://github.com/danielsmori/open-fednow/pull/118)).
- The external evaluator chooses its application ports at run time, and `restart_case.py` and `two_worker_case.py` no longer overwrite `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` ([#118](https://github.com/danielsmori/open-fednow/pull/118)). Its console summary lists only actual runs and shows the execution error when there is one ([#132](https://github.com/danielsmori/open-fednow/pull/132)).
- The full component table moved from the README to [docs/status.md](docs/status.md), and the development phases to [docs/roadmap.md](docs/roadmap.md).
- `docs/whitepaper.pdf` replaced by the revised November 9, 2026 preprint.
- Project version set to `0.1.0`. `demo/payment-integration.sh` and `harness/evaluate_external.py` now read the jar name from `pom.xml` instead of a fixed `0.1.0-SNAPSHOT` name.
- The README DOI badge is now labelled as the preprint's DOI. The DOI identifies the architecture preprint; the software is cited by repository and commit or release tag.

## 2026-11-12

### Added
- Lost-confirmation (S02) comparison: a status-only check, an independent ledger-and-rail checklist and the portable evaluator scored on the same runs against two synthetic targets, with an evidence bundle ([#112](https://github.com/danielsmori/open-fednow/pull/112)).

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
