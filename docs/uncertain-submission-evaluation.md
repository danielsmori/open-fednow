# Reproduce the outbound submission uncertainty check

This is a synthetic evaluation of a specific failure: a payment request reaches a simulated rail, but its response arrives after the client deadline. No real payment is sent and no rail or vendor has certified this behavior.

## Run

Use the revision and environment recorded by the generated report. Install JDK 17, Maven 3.9.x, Docker, and Python 3. Run from the repository root:

```sh
python3 scripts/evaluate.py --integration --negative-control
```

The command writes `target/evaluation/results.json`, source hashes, a source ZIP, environment and dependency records, Maven logs, and JUnit XML. It fails if a selected suite runs zero tests, skips tests, reports errors or failures, or if either deliberately broken version is not detected. Preserve the entire output directory with the revision under review; generated files are not committed.

## What to inspect

`UncertainSubmissionReproducerTest` uses WireMock to accept an HTTP request and delay the response beyond the client timeout. It checks that one request was sent, the API returns 503 without a fabricated pacs.002 rejection, the debit is applied, and no compensation or final idempotency outcome is recorded. `OutboundPaymentIntegrationTest` uses Redis and PostgreSQL containers to check the durable `OUTCOME_UNKNOWN` state, retained balance, absent reversal, and duplicate suppression. `SagaRecoveryServiceIntegrationTest` checks that an interrupted `SUBMITTING` state is quarantined after restart and remains outside automatic timeout compensation. The mutated version replaces quarantine with compensation; the evaluation passes only if the reproducer catches that regression.

The positive tests establish behavior in this repository's simulated environment. They do not establish whether FedNow accepted or settled a real payment, whether the request format matches current rail specifications, or whether the held funds can be safely released. That requires authoritative status evidence and an implemented resolution path. Record any outside reviewer's identity, expertise, exact revision, commands, observations, and limitations separately; a successful maintainer run is not independent reproduction.
