# Portable send reliability v1: local evidence

Evaluated implementation commit: `9c479551c63cbe1fb6fa03d253bdef35466c79d1` on `feature/portable-send-reliability-v1`. The Java service code is the `5c0fcf8` main baseline; this change adds the profile, runner, tests, CI gate, and documentation. The Python reference target revision is its file SHA prefix recorded in its report. Both targets and the rail simulator are project-built and synthetic. Neither report is a Fiserv, bank, or live FedNow result.

## Runs performed

| Check | Command or method | Result |
| --- | --- | --- |
| Python profile, fresh SQLite state | `python3 harness/smoke_profile.py --all --output target/portable/all-python.json` | S01, S02, S06, S16 PASS; [report](python-reference.json) |
| Java profile, disposable PostgreSQL/Redis/RabbitMQ and separate rail | Start `./demo/payment-integration.sh`, then `python3 harness/portable_profile.py --target-url http://127.0.0.1:18080 --rail-url http://127.0.0.1:18099 --target-revision 5c0fcf8 --environment-type synthetic-local --output target/portable/all-java.json` | S01, S02, S06, S16 PASS; [report](java-reference.json). Demo processes/containers were stopped after the run. |
| Existing Payment Integration walkthrough | `python3 demo/verify-payment-integration.py` while the Java demo was running | Normal payment and lost confirmation PASS |
| Python checks | `python3 -m unittest discover -s harness -p 'test_portable_profile.py'` | 8 PASS, including deliberate duplicate rail submission, mismatched financial effect, unavailable target and observation, and report redaction |
| Java unit suite | `mvn -q test` | 624 tests, 0 failures, 0 errors, 0 skipped |
| Clean-directory smoke | Copy only `harness/` into a temporary directory and run `python3 harness/smoke_profile.py --case S02` | PASS without Java build artifacts |
| Unavailable target | Profile against unused loopback ports | S02 BLOCKED, exit 1; unit test checks report output |

The Java run used a disposable local demo stack. The reports record the profile SHA, runner SHA, shared oracle SHA, scenario-manifest SHA, environment label, target revision, expected outcomes, and separate local and rail observations. See `SHA256SUMS` for the exact files included in this evidence bundle and the shared evaluator files.

## Boundaries and unperformed checks

- No external engineer ran the unchanged profile, and no provider-hosted or bank test core was used. Third-target portability beyond the two project-built implementations remains unverified.
- S16 observes competition within one synthetic exclusive-account fixture. It does not establish cross-channel funds control at an institution.
- The local rail simulator and its JSON observations are synthetic. This run does not establish FedNow certification, production connectivity, or vendor conformance.
- The existing full `scripts/evaluate.py --integration --negative-control --external` path was not rerun in this finishing pass. Its commands and archived results were left intact. The Java unit suite and Payment Integration walkthrough were run as noted above.
