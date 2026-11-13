# Release 0.1.0: evidence

Evaluated commit: `589ae8d1f2e1a701fe8496f20d0a5b2d42563f7b` on `release/v0.1.0`, based on `2e6dfd6` (`origin/main` when the branch was created). The working tree was clean for every run. The release commit that adds this bundle changes only files under this directory. All targets, accounts, balances, rail events and faults are synthetic and local. Nothing here is a FedNow, vendor, bank-core or production result.

Environment: macOS 27.2 on Apple Silicon, Homebrew JDK 17.0.20.1, Maven 3.9.16, Python 3.9, Docker Engine 29.7.2. Full details are in [evaluation-environment.json](evaluation-environment.json).

## Runs performed on November 12, 2026

| Check | Command | Result |
| --- | --- | --- |
| Java unit suite | `mvn -B --no-transfer-progress test` | 628 tests, 0 failures, 0 errors, 0 skipped |
| Selected controls, adapter tests, integration tests and negative controls | `python3 scripts/evaluate.py --integration --negative-control` | Passed. Controls 48, adapters 38 and integration 64 tests, with 0 failures, errors or skips. Each of the six deliberately broken builds failed its one named assertion. [results](evaluation-results.json) |
| Python profile, fresh SQLite state | `python3 harness/smoke_profile.py --all --output target/release-evidence/python-reference.json` | S01, S02, S06 and S16 PASS; [report](python-reference.json) |
| Java profile, disposable PostgreSQL, Redis, RabbitMQ and separate rail simulator | Start `./demo/payment-integration.sh`, then `python3 harness/portable_profile.py --target-url http://127.0.0.1:18080 --rail-url http://127.0.0.1:18099 --target-revision 589ae8d --environment-type synthetic-local --username admin --password changeme --output target/release-evidence/java-reference.json` | S01, S02, S06 and S16 PASS; [report](java-reference.json) |
| Payment Integration walkthrough | `python3 demo/verify-payment-integration.py` while the Java demo was running | Normal payment and lost confirmation passed |
| Paired S02 comparison | `python3 harness/compare_s02.py --mode both --trials 3 --output target/release-evidence/s02-comparison.json` while the Java demo was running | 3 Java/PostgreSQL and 3 Python/SQLite trials, 0 execution problems. Status-only check detected 0 of 12 faults; the independent checklist and the portable evaluator each detected 12 of 12; 0 clean false alarms. [report](s02-comparison.json) |
| Harness unit tests | `python3 -m unittest discover -s harness -p 'test_portable_profile.py'`, then the same with `test_s02_comparison.py` and `test_reference_target_guards.py` | 8, 4 and 2 tests passed |
| Test kit from the zip | Build the zip with the file list in `.github/workflows/release.yml`, extract it into an empty directory, and run `python3 harness/smoke_profile.py --all` | S01, S02, S06 and S16 PASS without a repository checkout or Java build |

The demo's Java service, rail simulator and containers were stopped after the runs. `SHA256SUMS` lists the files in this bundle and the evaluator files they were produced with.

## Not run for this release

- **The external four-target step** (`scripts/evaluate.py --external`) was not run. It needs port 8080, which was in use by an unrelated local process on the evaluation machine. A first attempt with `--external` failed for that reason and for a missing PostgreSQL on port 5432, before any scenario ran; it is not counted as a result. The last archived external run is in [`practical-2026-10-01`](../practical-2026-10-01/README.md).
- **The TigerBeetle S16 experiment** was not rerun. Its evidence is in [`tigerbeetle-s16-2026-11-10`](../tigerbeetle-s16-2026-11-10/).
- **The Banking Hub trial adapter** was not exercised. It needs trial credentials that are not stored in this repository.
- No outside engineer ran these checks, and no provider-hosted or bank test core was used.

## What this release is not

A release is a tagged, tested version of the repository. It is not a certification, and it does not make the software ready for production. See [reliability scope](../../scope.md).
