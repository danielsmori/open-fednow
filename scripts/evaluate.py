#!/usr/bin/env python3
"""Synthetic controls evaluation with source manifest and an isolated negative control."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "target" / "evaluation"
UNIT = "MessageRouterScreeningPolicyTest,MessageRouterBridgeSendsGuardTest,MessageRouterCurrencyGuardTest,RtpGatewayTest,JackHenryAdapterTest,UncertainSubmissionReproducerTest"
INTEGRATION = "OutboundPaymentIntegrationTest,FraudTimeoutIntegrationTest,FraudRoutingIntegrationTest"


def capture(args):
    result = subprocess.run(args, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    return {"exit_code": result.returncode, "output": result.stdout}


def run(args, cwd, name):
    with (OUT / (name + ".log")).open("w") as log:
        return subprocess.run(args, cwd=cwd, stdout=log, stderr=subprocess.STDOUT).returncode


def report(cwd, destination):
    destination.mkdir(parents=True, exist_ok=True)
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    for file in (cwd / "target" / "surefire-reports").glob("TEST-*.xml"):
        suite = ET.parse(file).getroot()
        for key in counts:
            counts[key] += int(suite.get(key, "0"))
        shutil.copy2(file, destination / file.name)
    return counts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--integration", action="store_true", help="also require Docker infrastructure tests")
    parser.add_argument("--negative-control", action="store_true", help="require a fail-open mutation to fail its regression test")
    args = parser.parse_args()
    if not shutil.which("mvn"):
        parser.error("Maven is required; use JDK 17 and Maven 3.9.x")
    # Remove only this script's generated reports so prior runs cannot count as evidence.
    shutil.rmtree(OUT, ignore_errors=True)
    OUT.mkdir(parents=True)
    files = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT).decode().split("\0")
    files = sorted({f for f in files if f and (ROOT / f).is_file()})
    manifest = {f: hashlib.sha256((ROOT / f).read_bytes()).hexdigest() for f in files}
    (OUT / "source-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    with zipfile.ZipFile(OUT / "source-snapshot.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for f in files:
            archive.write(ROOT / f, f)
    metadata = {"started_utc": datetime.now(timezone.utc).isoformat(), "platform": platform.platform(),
                "git_head": capture(["git", "rev-parse", "HEAD"]),
                "git_status": capture(["git", "status", "--short"]),
                "maven": capture(["mvn", "--version"]), "java_home": os.environ.get("JAVA_HOME"),
                "docker": capture(["docker", "version"]) if args.integration else "not requested"}
    (OUT / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
    dep_code = run(["mvn", "-B", "--no-transfer-progress", "dependency:list"], ROOT, "dependencies")
    results = {"dependency_resolution_exit_code": dep_code}
    ok = dep_code == 0
    for name, selection, options in [("controls", UNIT, [])] + ([("integration", INTEGRATION, ["-Dgroups=integration", "-DexcludedGroups="])] if args.integration else []):
        shutil.rmtree(ROOT / "target" / "surefire-reports", ignore_errors=True)
        command = ["mvn", "-B", "--no-transfer-progress", "-Dtest=" + selection, *options, "test"]
        code = run(command, ROOT, name)
        counts = report(ROOT, OUT / name)
        results[name] = {"command": command, "exit_code": code, **counts}
        ok &= code == 0 and counts["tests"] > 0 and counts["skipped"] == 0 and counts["errors"] == 0 and counts["failures"] == 0
    if args.negative_control:
        with tempfile.TemporaryDirectory(prefix="openfednow-negative-") as temp:
            mutant = Path(temp)
            for f in files:
                dest = mutant / f
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / f, dest)
            router = mutant / "src/main/java/io/openfednow/gateway/MessageRouter.java"
            old = "return screeningFailOpen ? ScreeningResult.pass()"
            source = router.read_text()
            if source.count(old) != 1:
                raise RuntimeError("Negative-control mutation target changed; review the script")
            router.write_text(source.replace(old, "return true ? ScreeningResult.pass()"))
            command = ["mvn", "-B", "--no-transfer-progress", "-Dtest=MessageRouterScreeningPolicyTest#outageMustNotReachFundsCheck", "test"]
            code = run(command, mutant, "negative-control")
            counts = report(mutant, OUT / "negative-control")
            detected = code != 0 and counts == dict(tests=1, failures=1, errors=0, skipped=0)
            results["negative_control"] = {"command": command, "exit_code": code, "mutation_detected": detected, **counts}
            ok &= detected
    results["evaluation_passed"] = bool(ok)
    (OUT / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    print(json.dumps(results, indent=2))
    print("Reports:", OUT)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
