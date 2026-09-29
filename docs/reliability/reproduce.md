# Reproduce the synthetic reliability evidence

Tested on macOS 27 / Apple Silicon with Homebrew JDK 17.0.20.1, Maven 3.9.16, Python 3.9.6, Docker Engine 29.7.2, PostgreSQL 16, Redis 7, and RabbitMQ 3. Results are source-specific and should be regenerated from a clean checkout. The runner records exact versions, source manifest, ZIP, command logs and JUnit XML under `target/evaluation/`.

From the repository root, with Docker Desktop running and ports 5432, 6379, 5672, 8080, 8081, 8099 and 8100 free:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
docker compose -p openfednow-reliability up -d postgres redis rabbitmq
python3 scripts/evaluate.py --integration --negative-control --external
```

The external option packages the current source, starts an independent synthetic rail, the OpenFedNow target, and a separate Python/SQLite reference target, then runs the unchanged manifest in sync and async core modes plus a hard-kill restart case. It terminates those processes when finished. `docker compose -p openfednow-reliability down` stops the dependencies without deleting their volumes. To reset the synthetic database entirely for a fresh run, use `docker compose -p openfednow-reliability down -v` **only if you intentionally want to delete that Compose project's test data**. The evaluator uses unique account and payment IDs, so prior synthetic rows do not alter expected case effects; a clean database remains preferable.

The suite intentionally builds two isolated broken variants under `--negative-control`; each must fail one named assertion. A mutant build failure or harness timeout is not accepted as a detected defect. The script's aggregate `results.json` and `external-results.json` identify commands, exit codes and output paths. The external JSON files contain observable local state, account balances, operation effect rows and independent remote simulator status/counts. `restart-s05.json` records the pre-inquiry hold and final reconciliation, with target logs beside it.

Failures should be investigated from `target/evaluation/*.log`, JUnit XML and the per-case JSON before any pass claim. If `docker compose` cannot start because ports are occupied, choose an isolated host or stop the conflicting local service; do not count skipped integration work as passing. The H2 migration test is useful for syntax but does not replace the PostgreSQL run. The simulator and reference target bind loopback only and use synthetic identifiers. Never point their JSON routes at a live payment rail or use real account data.

To run just the external oracle against already running processes, see `harness/contract-v1.md` and use `python3 harness/run.py --target-url ... --rail-url ...`. The repo is Apache-2.0 licensed; all new harness fixtures use Python's standard library.
