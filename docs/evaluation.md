# Historical controls evaluation — September 23, 2026

## Scope

This dated first package implemented the narrower FedNow evaluation path from the September 23, 2026 action plan. It does not certify financial correctness, vendor compatibility, live connectivity, or production readiness. RTP outbound initiation is disabled. Rule-based screening remains optional; when a configured port fails, the router rejects by default. Downtime sends default to disabled.

## Run

Prerequisites: JDK 17, Maven 3.9.x, Python 3, Git, and network access for Maven dependencies. Docker is required for the infrastructure option. No bank credentials or customer data are needed. On macOS with Homebrew JDK 17, set `JAVA_HOME` to `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.

```sh
python3 scripts/evaluate.py --integration --negative-control
```

For controls plus a deliberately failing mutation without Docker:

```sh
python3 scripts/evaluate.py --negative-control
```

The script records environment details, Git HEAD, dirty status, dependency versions, source SHA-256 hashes, a complete source ZIP, commands, JUnit XML, and counts under `target/evaluation/`. It rejects zero-test and skipped-test runs. Two negative controls each require exactly one assertion failure: one changes screening failure to PASS; the other changes lost-response quarantine back to compensation. A compile error does not count as detection; the working source is unchanged. See the [uncertain submission evaluation](uncertain-submission-evaluation.md) for the focused scenario and its limits.

Preserve the source ZIP and manifest with the results. Pin the tested commit and confirm the recorded working-tree status; HEAD alone does not identify uncommitted changes. To reproduce locally, extract the ZIP in a clean directory, run `git init`, and repeat the command; compare its source hashes to the supplied manifest. A clean compilation alone does not prove independent reproduction.

## Expected behavior and simulated dependencies

| Scenario | Expected result | Boundary |
|---|---|---|
| Screening BLOCK | Reject before funds check, ledger mutation, saga or transport | Unit mocks; actual router code |
| Screening timeout/error/null | TS01 with fail-closed policy; explicit fail-open reaches next funds check | Synthetic port; caller wait bounded, underlying task cancellation not guaranteed |
| Core offline | New outbound sends rejected; cached completed result retained on retry | Mock availability signal; no real card processor |
| RTP send | HTTP 503 / TS01; no router or client submission even with an HTTP client configured | Reference gateway only |
| FedNow outbound success, duplicate, insufficient funds, rejection | Existing balance/saga assertions hold | Real Redis/PostgreSQL/RabbitMQ containers; mocked FedNow service |
| Jack Henry request | Existing request assertions pass | Mock vendor endpoint; compatibility not independently verified |
| Delayed HTTP acceptance | HTTP 503, retained debit, no compensation or second send; restart quarantines a submission with no status | WireMock, mocked rail and real test databases; no live settlement or final-status inquiry |
| Negative controls | Each deliberate bug produces one assertion failure recognized by the runner | Temporary screening and lost-response mutations |

Infrastructure image tags are inherited from existing tests (`redis:7-alpine`, `postgres:16-alpine`, `rabbitmq:3-management`), not immutable image digests. Maven/plugin dependencies and the source manifest are recorded; a fully hermetic, image-pinned evaluation remains follow-up work. Results from the authoring environment are recorded separately in [evaluation results](evaluation-results.md).

## Follow-up identified at the time

The items below describe the September 23 snapshot. The later [SQL reliability evaluation](reliability/evaluation-report.md) closes some synthetic gaps; its [scenario register](reliability/requirements.csv) is the current coverage record.

1. **Submission uncertainty:** add authoritative status inquiry, verified release/completion, and idempotent late-status handling against applicable rail specifications. The package now checks held reservations and restart quarantine, but cannot establish a real rail outcome.
2. **Concurrent effects:** validate concurrent duplicates, Redis/SQL crash windows, replay and out-of-order external events. Sequential duplicate tests are narrower evidence.
3. **Adapter review:** obtain the applicable jXchange document/version and assess the exact balanced request. No reviewer has verified this correction.
4. **Claims outside this source tree:** reconcile the existing binary whitepaper, CV and petition with this matrix before outreach. These are not updated by the code changes. The existing whitepaper is historical, not current capability evidence.

## Source register

Reviewed September 23, 2026:

- [Federal Reserve: Understanding the Payment Timeout Clock](https://explore.fednow.org/resources/readiness-guide-understanding-the-payment-timeout-clock.pdf), pages 2–3. The accessible readiness guide describes acceptance without posting as ACWP and status requests using pacs.028 when the outcome message has not arrived. It warns that the guide can change and is not the final operating agreement. Its effective version is not stated in the retrieved document. This is a basis for identifying the gap, not enough to certify an implementation. Obtain the applicable current operating procedures and ISO message specifications before changing live rail semantics.
- [Redis transactions](https://redis.io/docs/latest/develop/using-commands/transactions/), living documentation, no fixed effective date. WATCH detects modifications by other clients. This corrects the prior documentation claim; it does not prove cross-store consistency.

Official rail test/certification resources, proposed application-level tests, and actual independent reproduction are different forms of evidence. This dated package supplied application-level tests and a defect reproducer; see the later [release candidate](reliability/evaluation-report.md) for expanded synthetic results.
