# S02 ledger and rail comparison evidence

Evaluated harness commit: `1541704f1314e4b39bc98c8428bd7b5adb98db57`, based on `2d467144ca4bfa73689fd6bc823dd4e6850432bc` (`origin/main` when the branch was created). The [machine-readable report](report.json) pins the runner, comparator, shared oracle, portable profile, scenario manifest, and Python target hashes. The Java service is the main-branch baseline. All targets, accounts, balances, rail events, and faults are synthetic and local.

## Exact verification

| Check | Command | Result |
| --- | --- | --- |
| Independent comparator tests | `python3 -m unittest discover -s harness -p 'test_s02_comparison.py'` | 3 passed |
| Portable evaluator tests | `python3 -m unittest discover -s harness -p 'test_portable_profile.py'` | 8 passed |
| Existing S02 smoke | `python3 harness/smoke_profile.py --case S02 --output target/s02-comparison/default-smoke.json` | S02 passed |
| Paired comparison | Start `./demo/payment-integration.sh` in one terminal; in another run `python3 harness/compare_s02.py --mode both --trials 3 --output target/s02-comparison/full.json` | 3 fresh Java/PostgreSQL and 3 fresh Python/SQLite S02 trials; 0 execution problems |

Each clean trial passed. The direct rail replay raised the simulator's submission count from one to two while retaining one settlement event and unchanged local financial effects. The missing local POST is a trace mutation, not a service failure. The missing rail record is an evidence-removal check and was scored UNAVAILABLE by both richer methods. The status-only rule detected 0/12 faults; the independent comparator and portable evaluator each detected 12/12, with no clean false alarms. These cases show no incremental detection over the independent checklist; the portable profile's benefit here is a reusable evaluation contract across the two local targets. Setup and run time were not measured, so no time-savings claim is made.

The report contains only allowlisted observations and no payment identifiers, account identifiers, credentials, or raw service logs. The historical archive was left unchanged. This run does not validate a vendor environment, a bank core, a live rail, or TigerBeetle S02 recovery.
