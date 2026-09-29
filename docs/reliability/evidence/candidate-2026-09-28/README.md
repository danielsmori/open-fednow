# Candidate evidence bundle

This directory preserves the local synthetic evaluation of clean source commit `96dfcb7e53b3901be430969b647aadcbc78647c4` (2026-09-28 local / 2026-09-29 UTC). `source-snapshot.zip` and `source-manifest.json` identify the evaluated tracked files; `environment.json` records the clean tree and tool versions. The report and this bundle are later documentation, so they are not inside that source ZIP.

- `results.json`, `external-results.json`: selected test counts, mutation results and external target outcomes.
- `openfednow-sync.json`, `openfednow-async.json`, `reference.json`, `restart-s05.json`: case-level expected/observed local state, account/effect rows, independent simulator status/counts and the process-kill trace.
- `*.log` and per-suite `TEST-*.xml`: raw Maven, process, baseline, negative-control and selected JUnit records. `full-unit.log` and `full-integration.log` are separate full runs before the last crash-intent test was added; the final selected run included that test.
- `dependencies.log`, `container-images.json`: resolved dependency tree and local container image IDs. Image IDs identify exact local images; registry digests were not available from this run.
- `ci-records.json`: GitHub PR check snapshot. Stacked PRs had no main-targeted CI checks at archive time.

SQLite runtime database files were excluded; the independent rail and reference observations needed for each evaluated scenario are in the JSON traces. No live payment or customer data was used. To verify the archived files from this directory, run `shasum -a 256 -c SHA256SUMS`. To regenerate from source, follow [the reproduction guide](../../reproduce.md).
