#!/usr/bin/env python3
"""Run a versioned reliability profile through the existing scenario oracle."""
import argparse
import hashlib
import importlib.util
import json
import re
import sys
from pathlib import Path

from run import Client, OpenFedNowDriver, run_case

ROOT = Path(__file__).resolve().parent
PROFILE = ROOT / "profiles/send-reliability-v1.json"
RUNNER_VERSION = "1.0.0"
ALLOWED_ENVIRONMENTS = ("synthetic-local", "provider-sandbox", "provider-test-core")


def load_profile(path=PROFILE):
    profile = json.loads(Path(path).read_text())
    manifest = json.loads((ROOT / profile["scenario_manifest"]).read_text())
    existing = {case["id"] for case in manifest["cases"] if case.get("executable", True)}
    ids = [case["id"] for case in profile["cases"]]
    if len(ids) != len(set(ids)) or any(case_id not in existing for case_id in ids):
        raise ValueError("Profile case IDs must be unique executable scenario IDs")
    return profile


def load_driver(path, base_url, username, password, submit_path):
    if path is None:
        return OpenFedNowDriver(Client(base_url, username, password), submit_path)
    location = Path(path).resolve()
    spec = importlib.util.spec_from_file_location("portable_target_driver", location)
    if spec is None or spec.loader is None:
        raise ValueError("Driver file could not be loaded")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.create_driver(base_url, username, password, submit_path)


def _snapshot(value):
    if not isinstance(value, dict):
        raise ValueError("Independent observation missing")
    local, account, remote, effects = (value[key] for key in ("local", "account", "remote", "effects"))
    if not isinstance(effects, list) or not isinstance(account, dict) or not isinstance(remote, dict):
        raise ValueError("Independent observation malformed")
    return {
        "local": {"state": local["state"], "ledger_minor": account["ledgerMinor"],
                  "held_minor": account["heldMinor"],
                  "effect_types": sorted(effect["effectType"] for effect in effects)},
        "rail": {"status": remote["status"], "submission_count": remote["submitCount"]},
    }


def observed(case_id, trace):
    if case_id == "S01":
        return {"settled": _snapshot(trace["accepted"]),
                "rejected": _snapshot(trace["rejected"])}
    if case_id == "S02":
        result = _snapshot(trace)
        before = trace["recovery"]
        held = before["account_before_inquiry"]
        result["local"]["before_inquiry_state"] = before["before_inquiry"]["state"]
        result["local"]["before_inquiry_ledger_minor"] = held["ledgerMinor"]
        result["local"]["before_inquiry_held_minor"] = held["heldMinor"]
        result["local"]["repeat_same_operation"] = (
            before["duplicate_operation_id"] == trace["local"]["operationId"])
        return result
    if case_id == "S06":
        result = _snapshot(trace)
        concurrent = trace["concurrency"]
        result["local"]["duplicate_same_operation"] = (
            concurrent["first_operation_id"] == concurrent["duplicate_operation_id"])
        return result
    if case_id == "S16":
        result = _snapshot(trace["winner"])
        result["local"]["refused_http"] = trace["refusedHttp"]
        result["rail"]["refused_payment_http"] = trace["refusedRemoteHttp"]
        return result
    raise ValueError("Case is not in this profile")


def validate_projection(case_id, projection):
    snapshots = (projection["settled"], projection["rejected"]) if case_id == "S01" else (projection,)
    expected_states = ("SETTLED", "REJECTED") if case_id == "S01" else ("SETTLED",)
    for snapshot, state in zip(snapshots, expected_states):
        if snapshot["local"]["state"] != state or snapshot["rail"]["status"] != state:
            raise AssertionError("local or rail final state differs from expected outcome")


def classify(error):
    if isinstance(error, AssertionError):
        message = str(error)
        if message.startswith(("account seed failed", "could not seed", "could not advance synthetic inquiry clock")):
            return "BLOCKED", "fixture_setup_unavailable"
        if message.startswith("submit failed"):
            return "BLOCKED", "target_submission_unavailable"
        return "FAIL", "invariant_violation"
    return "BLOCKED", "execution_or_observation_unavailable"


class ObservationRecorder:
    """Keep only safe fields needed to explain an incomplete or failed run."""
    def __init__(self):
        self.local = {}
        self.rail = {}

    def capture(self, kind, result):
        if not isinstance(result, tuple) or len(result) != 2:
            return
        code, value = result
        if kind == "submit" and type(code) is int:
            self.local["last_submit_http"] = code
        if kind in ("submit", "lookup", "ack_core") and isinstance(value, dict):
            state = value.get("state")
            if isinstance(state, str):
                self.local["last_state"] = state if state in (
                    "RESERVED", "SUBMITTING", "CORE_PENDING", "OUTCOME_UNKNOWN",
                    "SETTLED", "REJECTED", "INVESTIGATION") else "unrecognized"
        if kind == "account" and isinstance(value, dict):
            for source, target in (("ledgerMinor", "ledger_minor"), ("heldMinor", "held_minor")):
                if type(value.get(source)) is int:
                    self.local[target] = value[source]
        if kind == "effects" and isinstance(value, list):
            self.local["effect_types"] = sorted(
                item.get("effectType") if isinstance(item, dict) and item.get("effectType") in
                ("HOLD", "POST", "RELEASE") else "unrecognized" for item in value)

    def capture_rail(self, method, path, result):
        if method != "GET" or not path.startswith("/payments/"):
            return
        self.rail["last_lookup_http"] = result[0] if type(result[0]) is int else None
        value = result[1]
        if isinstance(value, dict):
            status = value.get("status")
            if isinstance(status, str):
                self.rail["status"] = status if status in ("SETTLED", "REJECTED") else "unrecognized"
            if type(value.get("submitCount")) is int:
                self.rail["submission_count"] = value["submitCount"]

    def snapshot(self):
        return {"local": dict(self.local), "rail": dict(self.rail)}


class ObservedDriver:
    def __init__(self, target, recorder):
        self.target, self.recorder = target, recorder

    def __getattr__(self, name):
        method = getattr(self.target, name)
        if not callable(method) or name not in ("submit", "lookup", "ack_core", "account", "effects"):
            return method
        def invoke(*args, **kwargs):
            result = method(*args, **kwargs)
            self.recorder.capture(name, result)
            return result
        return invoke


class ObservedRail:
    def __init__(self, target, recorder):
        self.target, self.recorder = target, recorder

    def call(self, method, path, body=None):
        result = self.target.call(method, path, body)
        self.recorder.capture_rail(method, path, result)
        return result


def case_result(case, driver, rail):
    missing = [name for name in case["required_driver_operations"] if not callable(getattr(driver, name, None))]
    if missing:
        return {"id": case["id"], "result": "BLOCKED", "reason": "driver_capability_unavailable",
                "missing_capabilities": missing, "expected": case["expected"],
                "observed": {"local": {}, "rail": {}}}
    recorder = ObservationRecorder()
    try:
        trace = run_case(case, ObservedDriver(driver, recorder), ObservedRail(rail, recorder), "SYNC")
        projection = observed(case["id"], trace)
        validate_projection(case["id"], projection)
        return {"id": case["id"], "result": "PASS", "expected": case["expected"],
                "observed": projection}
    except Exception as error:
        status, reason = classify(error)
        return {"id": case["id"], "result": status, "reason": reason,
                "expected": case["expected"], "observed": recorder.snapshot()}


def build_report(profile, cases, target_revision, driver_name, environment_type, profile_hash):
    return {"report_schema": "portable-send-result-v1", "profile_id": profile["profile_id"],
            "profile_version": profile["profile_version"], "profile_sha256": profile_hash,
            "runner_version": RUNNER_VERSION,
            "runner_sha256": hashlib.sha256((ROOT / "portable_profile.py").read_bytes()).hexdigest(),
            "oracle_sha256": hashlib.sha256((ROOT / "run.py").read_bytes()).hexdigest(),
            "scenario_manifest_sha256": hashlib.sha256((ROOT / "scenarios-v1.json").read_bytes()).hexdigest(),
            "target_revision": target_revision, "driver": driver_name,
            "environment_type": environment_type, "cases": cases,
            "summary": {status.lower(): sum(case["result"] == status for case in cases)
                        for status in ("PASS", "FAIL", "BLOCKED")}}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target-url", required=True)
    parser.add_argument("--rail-url", required=True)
    parser.add_argument("--target-revision", required=True)
    parser.add_argument("--environment-type", choices=ALLOWED_ENVIRONMENTS, required=True)
    parser.add_argument("--driver-file", help="Python file exposing create_driver(base_url, username, password, submit_path)")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="changeme")
    parser.add_argument("--submit-path", default="/reference/v1/payments")
    parser.add_argument("--case", action="append", help="Run one or more profile case IDs; default is all")
    parser.add_argument("--output", default="target/portable/send-v1-report.json")
    args = parser.parse_args(argv)
    if not re.fullmatch(r"[A-Za-z0-9._:-]{1,120}", args.target_revision):
        parser.error("--target-revision must be a short, non-sensitive revision label")
    profile = load_profile()
    selected = set(args.case or [case["id"] for case in profile["cases"]])
    if not selected or selected - {case["id"] for case in profile["cases"]}:
        parser.error("--case must name a case in this profile")
    driver_name = "openfednow-http-v1" if args.driver_file is None else Path(args.driver_file).stem
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,80}", driver_name):
        parser.error("driver filename must have a short, non-sensitive stem")
    try:
        driver = load_driver(args.driver_file, args.target_url, args.username, args.password, args.submit_path)
        rail = Client(args.rail_url)
        cases = [case_result(case, driver, rail) for case in profile["cases"] if case["id"] in selected]
    except Exception:
        cases = [{"id": case["id"], "result": "BLOCKED", "reason": "driver_or_environment_unavailable",
                  "expected": case["expected"], "observed": {"local": {}, "rail": {}}}
                 for case in profile["cases"] if case["id"] in selected]
    report = build_report(profile, cases, args.target_revision, driver_name,
                          args.environment_type, hashlib.sha256(PROFILE.read_bytes()).hexdigest())
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    for case in cases:
        print(f'{case["id"]}: {case["result"]}' + (f' ({case["reason"]})' if "reason" in case else ""))
    print(f'Report: {output}')
    return 0 if all(case["result"] == "PASS" for case in cases) else 1


if __name__ == "__main__":
    sys.exit(main())
