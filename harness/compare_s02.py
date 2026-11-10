#!/usr/bin/env python3
"""Paired S02 comparison on project-built synthetic targets and rail."""
import argparse
import copy
import hashlib
import json
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from portable_profile import PROFILE, RUNNER_VERSION
from run import Client, OpenFedNowDriver, assert_effects, final_snapshot, require, run_case
from s02_ledger_rail_comparator import EXPECTED, evaluate, status_only
from smoke_profile import free_port, ready, stop

ROOT = Path(__file__).resolve().parents[1]
CASE = {"id": "S02"}


class RecordingDriver(OpenFedNowDriver):
    def submit(self, payment):
        self.last_payment = dict(payment)
        return super().submit(payment)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def events_for(rail, message_id):
    code, events = rail.call("GET", "/events")
    if code != 200 or not isinstance(events, list):
        raise RuntimeError("rail event export unavailable")
    return [event for event in events if event.get("messageId") == message_id]


def normalize(trace, snapshot, events):
    """Allowlist equivalent pre-inquiry, final-ledger and rail observations."""
    recovery = trace.get("recovery", {})
    before = recovery.get("account_before_inquiry") or {}
    pending = recovery.get("before_inquiry") or {}
    local = snapshot.get("local") or {}
    account = snapshot.get("account") or {}
    effects = snapshot.get("effects")
    remote = snapshot.get("remote") or {}
    if isinstance(effects, list):
        counts = {kind: sum(e.get("effectType") == kind for e in effects if isinstance(e, dict))
                  for kind in ("HOLD", "POST", "RELEASE")}
        amounts = {kind: sum(e.get("amountMinor", 0) for e in effects
                         if isinstance(e, dict) and e.get("effectType") == kind)
                   for kind in ("HOLD", "POST")}
    else:
        counts, amounts = {}, {}
    settlement_events = (sum(e.get("kind") == "REMOTE_EFFECT" and e.get("status") == "SETTLED"
                             for e in events) if isinstance(events, list) else None)
    duplicate_events = (sum(e.get("kind") == "DUPLICATE_SUBMIT" for e in events)
                        if isinstance(events, list) else None)
    return {
        "pending_state": pending.get("state"),
        "pending_booked_minor": before.get("ledgerMinor"),
        "pending_held_minor": before.get("heldMinor"),
        "same_operation_on_repeat": (recovery.get("duplicate_operation_id") ==
            local.get("operationId") if recovery.get("duplicate_operation_id") and
            local.get("operationId") else None),
        "final_state": local.get("state"),
        "final_booked_minor": account.get("ledgerMinor"),
        "final_held_minor": account.get("heldMinor"),
        "hold_count": counts.get("HOLD"),
        "hold_amount_minor": amounts.get("HOLD"),
        "post_count": counts.get("POST"),
        "post_amount_minor": amounts.get("POST"),
        "release_count": counts.get("RELEASE"),
        "rail_status": remote.get("status"),
        "submission_attempts": remote.get("submitCount"),
        "settlement_events": settlement_events,
        "duplicate_submission_events": duplicate_events,
    }


def portable_decision(trace, snapshot):
    """Reuse the shared S02 assertions for a captured checkpoint or replay."""
    recovery = trace.get("recovery")
    if not isinstance(recovery, dict) or not isinstance(snapshot.get("local"), dict):
        return {"result": "UNAVAILABLE", "reason": "operation_observation_unavailable"}
    if not isinstance(snapshot.get("account"), dict) or not isinstance(snapshot.get("effects"), list):
        return {"result": "UNAVAILABLE", "reason": "ledger_observation_unavailable"}
    if not isinstance(snapshot.get("remote"), dict):
        return {"result": "UNAVAILABLE", "reason": "rail_observation_unavailable"}
    if not isinstance(recovery.get("account_before_inquiry"), dict):
        return {"result": "UNAVAILABLE", "reason": "pending_ledger_observation_unavailable"}
    try:
        require(recovery["before_inquiry"]["state"] == "OUTCOME_UNKNOWN",
                "pending_state")
        before = recovery["account_before_inquiry"]
        require(before["ledgerMinor"] == 100000 and before["heldMinor"] == 10000,
                "pending_balance_or_hold")
        require(recovery["duplicate_operation_id"] == snapshot["local"]["operationId"],
                "business_request_identity")
        require(snapshot["local"]["state"] == "SETTLED", "final_state")
        assert_effects(snapshot, "SETTLED", 10000)
    except KeyError:
        return {"result": "UNAVAILABLE", "reason": "required_field_unavailable"}
    except (TypeError, ValueError):
        return {"result": "UNAVAILABLE", "reason": "required_observation_malformed"}
    except AssertionError as error:
        message = str(error)
        if message.startswith("financial effects"):
            reason = "financial_effects"
        elif message in ("duplicate remote submission", "final hold remains",
                         "ledger balance differs from independent expected effect",
                         "local final state disagrees with remote"):
            reason = message.replace(" ", "_")
        else:
            reason = message if message in ("pending_state", "pending_balance_or_hold",
                "business_request_identity", "final_state") else "shared_s02_invariant"
        return {"result": "DETECT", "reason": reason}
    return {"result": "PASS"}


def score(label, target, trial, trace, snapshot, events):
    observed = normalize(trace, snapshot, events)
    return {"target": target, "trial": trial, "case": "S02", "condition": label,
            "fault_kind": ("none" if label == "clean" else
                           "executable_rail_replay" if label == "extra_submission" else
                           "trace_mutation" if label == "missing_local_post" else
                           "evidence_removed"),
            "observed": observed,
            "workflows": {"historical_status_only": status_only(observed),
                          "ledger_rail_comparator": evaluate(observed),
                          "portable_evaluator": portable_decision(trace, snapshot)}}


def trial_records(target, trial, driver, rail):
    trace = run_case(CASE, driver, rail, "SYNC")
    payment = driver.last_payment
    message_id = payment["messageId"]
    clean_events = events_for(rail, message_id)
    clean_snapshot = {key: trace[key] for key in ("local", "account", "effects", "remote")}
    clean = normalize(trace, clean_snapshot, clean_events)
    if evaluate(clean)["result"] != "PASS" or portable_decision(trace, clean_snapshot)["result"] != "PASS":
        raise RuntimeError("clean S02 fixture did not satisfy predeclared checks")
    records = [score("clean", target, trial, trace, clean_snapshot, clean_events)]

    code, _ = rail.call("POST", "/control/" + message_id, {"status": "SETTLED"})
    if code != 200:
        raise RuntimeError("rail replay fixture setup failed")
    code, _ = rail.call("POST", "/submit", {"message": payment,
                              "attemptId": "comparison-replay-" + message_id})
    if code != 200:
        raise RuntimeError("rail replay injection failed")
    code, local = driver.lookup(trace["local"]["operationId"])
    if code != 200:
        raise RuntimeError("post-replay operation lookup failed")
    replay_snapshot = final_snapshot(driver, rail, local, payment)
    replay_events = events_for(rail, message_id)
    if (sum(e.get("kind") == "REMOTE_EFFECT" for e in clean_events) != 1 or
        sum(e.get("kind") == "DUPLICATE_SUBMIT" for e in clean_events) != 0 or
        sum(e.get("kind") == "REMOTE_EFFECT" for e in replay_events) != 1 or
        sum(e.get("kind") == "DUPLICATE_SUBMIT" for e in replay_events) != 1 or
        replay_snapshot["account"] != clean_snapshot["account"] or
        replay_snapshot["effects"] != clean_snapshot["effects"] or
        replay_snapshot["local"]["operationId"] != clean_snapshot["local"]["operationId"]):
        raise RuntimeError("replay fixture did not isolate one extra submission")
    records.append(score("extra_submission", target, trial, trace, replay_snapshot, replay_events))

    mutated = copy.deepcopy(clean_snapshot)
    mutated["effects"] = [effect for effect in mutated["effects"] if effect.get("effectType") != "POST"]
    if (sum(e.get("effectType") == "POST" for e in clean_snapshot["effects"]) != 1 or
            any(e.get("effectType") == "POST" for e in mutated["effects"])):
        raise RuntimeError("posting trace mutation invalid")
    records.append(score("missing_local_post", target, trial, trace, mutated, clean_events))

    unavailable = copy.deepcopy(clean_snapshot)
    unavailable["remote"] = None
    records.append(score("missing_rail_evidence", target, trial, trace, unavailable, None))
    return records


def summarize(records):
    names = ("historical_status_only", "ledger_rail_comparator", "portable_evaluator")
    return {name: {
        "valid_faults_detected": sum(r["workflows"][name]["result"] == "DETECT"
            for r in records if r["condition"] in ("extra_submission", "missing_local_post")),
        "valid_faults_eligible": sum(r["workflows"][name]["result"] != "UNAVAILABLE"
            for r in records if r["condition"] in ("extra_submission", "missing_local_post")),
        "clean_false_alarms": sum(r["workflows"][name]["result"] == "DETECT"
            for r in records if r["condition"] == "clean"),
        "clean_trials": sum(r["condition"] == "clean" for r in records),
        "unavailable_cases": sum(r["workflows"][name]["result"] == "UNAVAILABLE"
            for r in records if r["condition"] == "missing_rail_evidence"),
    } for name in names}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("python", "both"), default="python")
    parser.add_argument("--trials", type=int, default=1)
    parser.add_argument("--java-url", default="http://127.0.0.1:18080")
    parser.add_argument("--rail-url", default="http://127.0.0.1:18099")
    parser.add_argument("--output", default="target/s02-comparison/report.json")
    args = parser.parse_args(argv)
    if not 1 <= args.trials <= 10:
        parser.error("--trials must be between 1 and 10")
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    try:
        baseline = subprocess.check_output(["git", "merge-base", "HEAD", "origin/main"],
                                           cwd=ROOT, text=True,
                                           stderr=subprocess.DEVNULL).strip()
    except subprocess.CalledProcessError:
        # CI's shallow pull-request checkout may not include origin/main.
        baseline = None
    report = {"schema": "s02-ledger-rail-comparison-v1", "source_revision": revision,
              "base_revision": baseline, "environment": "project-built synthetic local",
              "requested_trials_per_target": args.trials,
              "revisions": {"runner_sha256": sha(__file__),
                  "comparator_sha256": sha(ROOT / "harness/s02_ledger_rail_comparator.py"),
                  "shared_oracle_sha256": sha(ROOT / "harness/run.py"),
                  "profile_sha256": sha(PROFILE), "profile_runner_version": RUNNER_VERSION,
                  "scenario_manifest_sha256": sha(ROOT / "harness/scenarios-v1.json"),
                  "python_target_sha256": sha(ROOT / "harness/reference_target.py"),
                  "java_source_revision": baseline if args.mode == "both" else None},
              "predeclared_comparator_assertions": list(EXPECTED),
              "records": [], "execution_problems": []}
    with tempfile.TemporaryDirectory(prefix="openfednow-s02-comparison-") as directory:
        state = Path(directory)
        processes = []
        try:
            rail_url = args.rail_url
            if args.mode == "python":
                rail_port = free_port()
                rail_url = f"http://127.0.0.1:{rail_port}"
                with (state / "rail.log").open("wb") as log:
                    process = subprocess.Popen([sys.executable, str(ROOT / "harness/rail_simulator.py"),
                        "--port", str(rail_port), "--db", str(state / "rail.sqlite")],
                        cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
                    processes.append(process)
                    if not ready(rail_url + "/health", process):
                        raise RuntimeError("rail startup unavailable")
            rail = Client(rail_url)
            if rail.call("GET", "/health")[0] != 200:
                raise RuntimeError("rail health unavailable")
            targets = []
            if args.mode == "both":
                java = Client(args.java_url, "admin", "changeme")
                if java.call("GET", "/fednow/health")[0] != 200:
                    raise RuntimeError("Java target health unavailable")
                targets.append(("java_postgresql", RecordingDriver(java, "/fednow/send")))
            python_port = free_port()
            with (state / "python.log").open("wb") as log:
                process = subprocess.Popen([sys.executable, str(ROOT / "harness/reference_target.py"),
                    "--port", str(python_port), "--db", str(state / "reference.sqlite"),
                    "--rail-url", rail_url], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
                processes.append(process)
                if not ready(f"http://127.0.0.1:{python_port}/health", process):
                    raise RuntimeError("Python target startup unavailable")
            targets.append(("python_sqlite", RecordingDriver(
                Client(f"http://127.0.0.1:{python_port}", "admin", "changeme"))))
            for target_name, driver in targets:
                for trial in range(1, args.trials + 1):
                    try:
                        report["records"].extend(trial_records(target_name, trial, driver, rail))
                    except Exception as error:
                        report["execution_problems"].append({"target": target_name, "trial": trial,
                            "reason": "scenario_or_fixture_execution_failed",
                            "exception_type": type(error).__name__})
        except Exception as error:
            report["execution_problems"].append({"reason": "setup_failed",
                "exception_type": type(error).__name__})
        finally:
            for process in reversed(processes):
                stop(process)
    report["summary"] = summarize(report["records"])
    report["valid_clean_trials"] = sum(r["condition"] == "clean" for r in report["records"])
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    for name, values in report["summary"].items():
        print(f'{name}: {values["valid_faults_detected"]}/{values["valid_faults_eligible"]} '
              f'faults detected; {values["clean_false_alarms"]} clean false alarms')
    print(f'Valid S02 trials: {report["valid_clean_trials"]}; execution problems: '
          f'{len(report["execution_problems"])}; report: {output}')
    expected_trials = args.trials * (2 if args.mode == "both" else 1)
    valid_scoring = all(
        record["workflows"][method]["result"] == expected
        for record in report["records"]
        for method, expected in (
            ("historical_status_only", "PASS"),
            ("ledger_rail_comparator", "DETECT"),
            ("portable_evaluator", "DETECT"),
        ) if record["condition"] in ("extra_submission", "missing_local_post")
    ) and all(
        record["workflows"][method]["result"] == "UNAVAILABLE"
        for record in report["records"] if record["condition"] == "missing_rail_evidence"
        for method in ("ledger_rail_comparator", "portable_evaluator")
    )
    return 0 if (report["valid_clean_trials"] == expected_trials and
                 not report["execution_problems"] and valid_scoring) else 1


if __name__ == "__main__":
    raise SystemExit(main())
