# Independent evaluator quick start

**Version:** this documentation branch retains the executable source tested at `99b7ffb641ee00c5a3df7e72e5607f1b440f15fc` (main `9d8dbbe` plus trace-only harness observations). Use the [source archive](evidence/practical-2026-10-01/final-full/source-snapshot.zip) or that Git commit for an exact-source rerun; the [October 1 practical report](practical-evaluation-2026-10-01.md) gives hashes. This is a synthetic local exercise. No FedNow account, bank data or vendor credentials are needed or used.

**Prerequisites:** Git, JDK 17, Maven 3.9.x, Python 3.9+, Docker Engine with Compose, available host ports 5432, 6379, 5672, 8080–8082 and 8099–8100, and sufficient resources to run PostgreSQL 16, Redis 7, RabbitMQ 3 and two local targets. On macOS/Homebrew, set `JAVA_HOME` to the JDK 17 home. Check `java -version`, `mvn -version` and `docker info`. The Compose file uses fixed `openfednow-*` container names; stop name conflicts or supply a Compose override with unique names. Do not remove another project's containers or volumes just to run this evaluation.

From a clean checkout of the documented branch or tested commit, run:

```sh
export JAVA_HOME=/path/to/jdk-17
# If fixed container names are available:
docker compose -p openfednow-evaluator up -d --wait postgres redis rabbitmq
python3 scripts/evaluate.py --integration --negative-control --external
```

The complete run writes `target/evaluation/results.json`, scenario JSON, logs, JUnit XML, `source-manifest.json`, `source-snapshot.zip` and `environment.json`. It will take several minutes; dependency downloads can add time. Check `results.json` for `evaluation_passed: true` and `external-results.json` for every executed case. A process/build failure is inconclusive, not a defect detected. The six deliberate negative-control variants should each have exactly one named test failure and zero build or setup errors. Stop only your own Compose project with `docker compose -p openfednow-evaluator down`.

**Starting scenario:** in `openfednow-sync.json`, find S02. The simulator settles a synthetic 10,000-minor-unit payment but discards the response. The local operation should first stay `OUTCOME_UNKNOWN`, with a HOLD and unchanged 100,000-minor-unit ledger. A repeated request must retrieve the same operation. A synthetic inquiry then reports settlement: one remote submit, one HOLD, one POST, final ledger 90,000, held balance zero. The archived JSON preserves the final effects; the runner's assertions and [worked examples](worked-examples-2026-10-01.md) explain the intermediate checks. `serviceStatus` is simulator evidence, while POST is the separate local financial effect.

To exercise another implementation, implement the seven operations in [driver contract v1](../../harness/contract-v1.md), expose inspectable local effects, and run the same `harness/scenarios-v1.json` and oracle. The included Python/SQLite reference target demonstrates this contract, but was built by the same development agent; it is portability evidence, not independent adoption. The Java bridge is explicitly non-production. Legacy outbound and return routes are disabled by default; vendor-shaped adapters have no verified live integration. Restricted rail specifications, real core reservation controls and authenticated status are outside this exercise.

If you evaluate it, [the feedback form](evaluator-feedback-form.md) accepts negative or inconclusive findings. No favorable statement or permission to attribute your review is requested as a condition of evaluating.
