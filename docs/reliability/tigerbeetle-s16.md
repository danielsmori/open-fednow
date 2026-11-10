# TigerBeetle S16 portability experiment

This opt-in experiment keeps the Python target's HTTP contract and the published S16 scenario/oracle unchanged. TigerBeetle is the authority for the synthetic account's posted and pending balances. SQLite still stores payment identity and workflow status; the rail simulator independently records submissions and outcomes. This is a project-run local demonstration, not bank, vendor, or FedNow validation.

## Plan and implementation

1. Pin TigerBeetle server and Python SDK to **0.17.9**. Start one development replica on loopback with a fresh disposable data file.
2. Create a synthetic payer with `debits_must_not_exceed_credits`, plus synthetic funding and destination accounts. Post a 100,000 minor-unit funding transfer.
3. Preserve the existing exact-cent and USD validation. Persist stable pending, post, and void transfer IDs with the payment identity in SQLite, then ask TigerBeetle to create the pending transfer. An explicit balance-invariant refusal becomes HTTP 409. An ambiguous result returns 503 and cannot dispatch to the rail.
4. After an independently read simulated outcome, post or void the pending transfer with its own stable ID. Confirm matching duplicate IDs and lookup records before treating them as successful.
5. Read booked and held balances from TigerBeetle accounts; derive `HOLD`, `POST`, and `RELEASE` from TigerBeetle transfer records. Run unchanged S16 plus a separate deterministic check that attempts the second reservation before the first posts.

The default `python3 harness/smoke_profile.py --all` remains SQLite-only and requires no TigerBeetle package. The new mode is selected only by `--tigerbeetle-address 127.0.0.1:PORT` on `harness/reference_target.py`; its fixture API binds to loopback. In this experiment, external debit fixtures are unavailable, so cases depending on them are outside this run.

## Reproduce locally

Use Python 3.9+, `uv`, and a macOS or Linux TigerBeetle 0.17.9 binary. The following example is for macOS; use `tigerbeetle-aarch64-linux.zip` or `tigerbeetle-x86_64-linux.zip` from the same release on Linux.

```sh
mkdir -p target/tigerbeetle
uv venv target/tigerbeetle/.venv
uv pip install --python target/tigerbeetle/.venv/bin/python -r harness/requirements-tigerbeetle.txt
curl -fL -o target/tigerbeetle/tigerbeetle-universal-macos.zip \
  https://github.com/tigerbeetle/tigerbeetle/releases/download/0.17.9/tigerbeetle-universal-macos.zip
unzip -q target/tigerbeetle/tigerbeetle-universal-macos.zip -d target/tigerbeetle
target/tigerbeetle/.venv/bin/python harness/smoke_tigerbeetle.py \
  --binary target/tigerbeetle/tigerbeetle
```

The final command formats a single-replica development ledger in a fresh temporary directory, starts it with the target and simulator on ephemeral loopback ports, runs S16 with `harness/portable_profile.py`, performs the separate pending-contention check, and stops only those three child processes. Temporary data is removed when the run exits. The two sanitized reports are `target/tigerbeetle/s16-report.json` and `target/tigerbeetle/s16-report-contention.json`. A TigerBeetle development data file may allocate about 1 GiB while the command runs. The script checks the server and SDK versions before starting.

Targeted optional SDK checks:

```sh
target/tigerbeetle/.venv/bin/python -m unittest discover -s harness -p 'test_tigerbeetle_ledger.py'
```

## Result boundaries

The S16 report separates local state and TigerBeetle-derived balances/effects from simulator submission counts. The contention report checks that the first 70,000-unit hold is still pending when the second 70,000-unit reservation reaches TigerBeetle, which refuses it. The local payment status and a successful HTTP response are not used as proof of a ledger effect.

SQLite and TigerBeetle are separate stores. The current experiment persists transfer IDs before reservation and blocks dispatch on an unconfirmed hold, but it does not implement restart reconciliation after a TigerBeetle post and before the SQLite completion update. S02 lost-confirmation and cross-store restart recovery are follow-on work, and should be evidenced separately. The synthetic rail is not FedNow, and a single-replica development ledger has no production availability guarantee.

TigerBeetle's [local setup](https://docs.tigerbeetle.com/start/), [Python client](https://docs.tigerbeetle.com/coding/clients/python/), [two-phase transfer](https://docs.tigerbeetle.com/coding/two-phase-transfers/), and [reliable submission](https://docs.tigerbeetle.com/coding/reliable-transaction-submission/) documentation informed this local mapping.
