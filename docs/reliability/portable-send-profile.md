# Portable send reliability profile v1

This is an engineering evaluation of selected **bank-side send reliability** in controlled environments. It is not FedNow certification, a FedNow transport test, or evidence of institution-wide funds control. The first profile uses the existing S01, S02, S06 and S16 case logic in `harness/run.py` and `harness/scenarios-v1.json`. The versioned case selection and expected outcomes are in `harness/profiles/send-reliability-v1.json`.

## One-command smoke run

From a clean checkout with Python 3.9+ and no Java build or third-party Python packages:

```sh
python3 harness/smoke_profile.py --case S02
```

The command starts the project-built Python/SQLite reference target and separate SQLite rail simulator on ephemeral loopback ports, creates disposable files in a temporary directory, runs S02 and stops only its child processes. It leaves `target/portable/send-v1-smoke.json` as the report. Exit 0 means every selected case passed; exit 1 means a required case failed or was blocked. `python3 harness/smoke_profile.py --all` runs the full four-case profile, while `--case S06` runs the duplicate-request check. Each smoke invocation uses fresh synthetic state.

For an already running project-built Java reference API, use the same profile and oracle:

```sh
python3 harness/portable_profile.py \
  --target-url http://127.0.0.1:8080 --rail-url http://127.0.0.1:8099 \
  --target-revision YOUR_JAVA_COMMIT --environment-type synthetic-local \
  --username admin --password changeme --case S02 \
  --output target/portable/java-s02.json
```

The Java target needs the non-production fixture API, PostgreSQL, Redis, RabbitMQ and the configured synthetic rail, as described in [driver contract v1](../../harness/contract-v1.md). The existing full evaluation remains `python3 scripts/evaluate.py --integration --negative-control --external`; this profile does not replace that path.

## A third target

Copy [the minimal driver](../../harness/example_target_driver.py) outside the repository and map its methods to an approved sandbox. Its `create_driver(base_url, username, password, submit_path)` factory is selected with `--driver-file /path/to/driver.py`. The driver returns normalized `(status, value)` pairs for synthetic account setup, submission, operation lookup, independently observed account and financial effects, and controlled inquiry timing. It must direct its test submissions to the separately running synthetic rail simulator. A different endpoint name alone does not provide these observations.

```sh
python3 harness/portable_profile.py \
  --target-url https://YOUR-CONTROLLED-SANDBOX \
  --rail-url http://127.0.0.1:YOUR-RAIL-PORT \
  --driver-file /path/to/driver.py \
  --target-revision YOUR-TARGET-REVISION \
  --environment-type provider-sandbox --case S02 \
  --output target/portable/third-target-s02.json
```

The caller supplies the target revision and environment label; the tool records them but cannot authenticate those claims. A target must provide synthetic accounts and permitted controls. S02 requires a lost-response fault and inquiry; S06 requires a submission barrier; S16 requires two competing payment requests plus account and effect observations. If a target cannot expose a required observation or control, the result is **BLOCKED**. A bank's other-channel funds control needs separate evidence; S16 covers only the target's declared fixture boundary.

## Result and claim boundaries

The JSON report records profile, runner, shared oracle and scenario-manifest versions or hashes; target revision, driver and environment label; each case's expected local and rail effects; and an allowlisted projection of observed states, effect types, balances and simulator submission counts. It omits credentials, tokens, account IDs, operation IDs and raw target/provider payloads. The human summary prints one line per case.

- **PASS:** the shared case oracle observed matching local and independent rail effects.
- **FAIL:** a reachable target provided enough evidence to verify an invariant violation, such as two simulator submissions or a wrong financial effect.
- **BLOCKED:** a capability, observation, target or runner dependency was unavailable. It is neither a pass nor a verified defect.

S02 has two distinct controls: repeating the *local business request* must return the same operation, while the *rail simulator* must record one submission. The [FedNow Service Operating Procedures v3.7, section 13.d](https://www.frbservices.org/wp-content/uploads/101326-fednow-service-operating-procedures.pdf) informs the general need to inquire when message status is uncertain after timeout. This local simulator is not a pacs.028 implementation, and its rail JSON is not a FedNow message. The other profile assertions are engineering invariants under synthetic fixture assumptions, listed case by case in the profile manifest.

For a reproducible issue, include the profile version and SHA, runner SHA, target revision and environment type, case ID, report JSON, the capability or assertion that failed, and sanitized independent observations. Remove secrets and customer data before sharing. A successful project-built Python or Java run is portability evidence within this repository; an unchanged case run by an outside engineer and provider-backed execution are separate milestones.
