# S02 comparison against ledger and rail checks

This harness-only experiment runs the unchanged S02 lost-confirmation case against two project-built synthetic targets. It compares three checks on the same observations: a historical status-only rule, an independent ledger-and-rail checklist, and the existing portable evaluator's shared S02 assertions. It is not a live FedNow, vendor, or bank-core result.

## Reproduce

For a quick disposable Python/SQLite and separate rail simulator check:

```sh
python3 -m unittest discover -s harness -p 'test_s02_comparison.py'
python3 harness/compare_s02.py --mode python --trials 1 --output target/s02-comparison/quick.json
```

For the paired run, use two terminals in a checkout with Java 17, Maven, Docker, and Python 3. The first command starts the disposable Java/PostgreSQL demo with its separate rail simulator. Stop it with Ctrl-C after the second command finishes.

```sh
./demo/payment-integration.sh
```

```sh
python3 harness/compare_s02.py --mode both --trials 3 --output target/s02-comparison/full.json
```

The runner checks local health, creates fresh synthetic accounts for every trial, invokes the existing S02 case, and records the pending operation, ledger hold, final operation, financial effects, and rail events. It then makes one executable extra submission to the rail simulator for the same message. The simulator records a second submission attempt but only one synthetic settlement effect. A copy of the clean trace separately removes the POST financial effect. A final copy removes rail evidence to test honest unavailability. Only the extra submission is executed against a running service; the missing-post and missing-evidence conditions are labeled trace changes in the report. The S02 runner validates the clean target fixture, while the comparison records each check's clean verdict before fault injection. A false alarm remains in the report and makes the command fail; a broken fixture or replay isolation failure appears separately under execution problems.

## Checks fixed before execution

The historical status-only rule asks whether the final local state is SETTLED. The independent checklist in `harness/s02_ledger_rail_comparator.py` checks the pending hold, same operation on retry, final booked balance and zero hold, HOLD and POST effect counts and amounts, no RELEASE, settled rail result, one submission attempt, one settlement event, and no duplicate-submission event. It does not import the S02 runner or portable evaluator. The portable check reuses `harness/run.py` S02 assertions, including `assert_effects`. Missing required ledger or rail observations are UNAVAILABLE, never counted as a detected fault.

Scores count detection of the executable extra rail attempt and the missing-post trace change. Clean false alarms, unavailable clean verdicts, and unavailable-evidence outcomes are reported separately. A status-only rule can pass both fault cases because final local status remains SETTLED; it is deliberately narrow, not a full implementation of historical evaluation code. The two richer checks should detect both faults. Equal detection on these predeclared cases establishes no incremental fault coverage for the portable evaluator over the independent checklist. It does establish that the profile's shared evaluation contract can be reused with these synthetic targets.

## Boundaries and evidence

The JSON report contains allowlisted observations, results, source and artifact hashes, target labels, trial counts, and execution problems. It omits payment and account identifiers, credentials, and raw logs. The checked-in [evidence bundle](evidence/s02-comparison-2026-11-10/manifest.md) pins the evaluated revision and exact commands. The older comparison archive was not edited. This experiment does not test TigerBeetle S02 recovery or establish provider interoperability, FedNow conformance, or performance.
