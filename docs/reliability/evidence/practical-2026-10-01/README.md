# October 1 practical evaluation evidence

This directory is a new bundle; the September 28 and 29 candidate bundles remain unchanged. All inputs are synthetic. The [report](../../practical-evaluation-2026-10-01.md) interprets these files, and [worked examples](../../worked-examples-2026-10-01.md) point to exact trace lines.

| Directory | Source and command | Observed result and wall time |
|---|---|---|
| `main-9d8dbbe-full/` | Clean `9d8dbbe`; `python3 scripts/evaluate.py --integration --negative-control --external` | Pass; 210.86 s. This is the pre-trace-enhancement October main run. |
| `main-9d8dbbe-external-repeat/` | Same main source; `python3 harness/evaluate_external.py` | Pass; 114.71 s. |
| `final-full/` | Clean `99b7ffb`; complete evaluator command above | Pass; 199.88 s. The six mutations failed only their expected assertions. Full separate unit and integration suites also passed. |
| `final-repeat-2/` | Same `99b7ffb`; external command above | Pass; 116.83 s. |
| `final-repeat-3/` | Same `99b7ffb`; external command above | Pass; 114.75 s. |

The three final-source external runs used the same committed scenario manifest and driver; generated payment identifiers differed. The local simulator/SQLite files were reset between repeats, and Java SQL state was retained with new unique identifiers. Repeats 2 and 3 are external-oracle runs only, not repeats of the six mutation builds or full Maven suites. Their `external-results.json` and per-scenario traces provide all results; no run was dropped. The two main-source runs are version-reconciliation evidence, not interchangeable with the final-source results.

`final-full/environment.json` records clean Git status, SHA and tools. `source-snapshot.zip` contains the 409 source files at `99b7ffb`; `source-manifest.json` lists their hashes. ZIP contents and all manifest hashes were checked. `final-full/results.json`, `external-results.json`, JUnit XML, evaluator stdout and command logs are raw evidence. `full-unit.log` and `full-integration.log` show the broad 618- and 207-test suites. Logs for deliberate mutants are expected to contain one assertion failure each. SQLite runtime database files were excluded because the JSON traces and independent simulator observations preserve the evaluated effects without bundling mutable databases.

`setup-attempts.log` records the failed first Compose command, the manual override, cached startup and unavailable cold-download timing. `compose.override.yml` is the exact name override used; a reviewer may need to choose different unique names. The source-to-scenario matrix in this directory links each S01–S18 row to current evidence and its scope limit. No bank or external reviewer generated these results.

From this directory, verify archived file hashes with:

```sh
shasum -a 256 -c SHA256SUMS
```

The SHA file intentionally excludes itself. To verify the source ZIP against its manifest, hash each ZIP entry and compare it with the corresponding JSON value; there are 409 matching entries. The original candidate archives carry their own manifests and hashes and have not been rewritten.
