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
INTEGRATION = "OutboundPaymentIntegrationTest,SagaRecoveryServiceIntegrationTest,SagaTimeoutIntegrationTest,PostgresIntegrationTest,FraudTimeoutIntegrationTest,FraudRoutingIntegrationTest,ReliablePaymentIntegrationTest"
ADAPTERS = "VendorReliabilityCapabilityContractTest,FisAdapterTest,FiservAdapterTest,JackHenryAdapterTest"


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


def run_reliability_mutant(files, name, relative_path, replacements, test_name):
    """An isolated build must fail one named financial/state assertion."""
    with tempfile.TemporaryDirectory(prefix="openfednow-" + name + "-") as temp:
        mutant = Path(temp)
        for file in files:
            dest = mutant / file
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / file, dest)
        target = mutant / relative_path
        source = target.read_text()
        for old, new in replacements:
            if source.count(old) != 1:
                raise RuntimeError(f"Mutation target changed: {name}: {old[:70]}")
            source = source.replace(old, new)
        target.write_text(source)
        command = ["mvn", "-B", "--no-transfer-progress", "-DexcludedGroups=",
                   "-Dtest=" + test_name, "test"]
        code = run(command, mutant, name)
        counts = report(mutant, OUT / name)
        detected = code != 0 and counts == dict(tests=1, failures=1, errors=0, skipped=0)
        return {"command": command, "exit_code": code,
                "mutation_detected": detected, **counts}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--integration", action="store_true", help="also require Docker infrastructure tests")
    parser.add_argument("--negative-control", action="store_true", help="require a fail-open mutation to fail its regression test")
    parser.add_argument("--external", action="store_true", help="run the external synthetic harness against two processes and both core fixtures")
    args = parser.parse_args()
    if args.external:
        args.integration = True
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
    selections = [("controls", UNIT, []), ("adapters", ADAPTERS, [])]
    if args.integration:
        selections.append(("integration", INTEGRATION,
                           ["-Dgroups=integration", "-DexcludedGroups="]))
    for name, selection, options in selections:
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
        # Deliberately restore the old unsafe action after a lost response.
        # The delayed-acceptance regression must fail if compensation returns.
        with tempfile.TemporaryDirectory(prefix="openfednow-uncertainty-mutant-") as temp:
            mutant = Path(temp)
            for f in files:
                dest = mutant / f
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / f, dest)
            router = mutant / "src/main/java/io/openfednow/gateway/MessageRouter.java"
            source = router.read_text()
            old = 'sagaOrchestrator.markOutcomeUnknown(saga);'
            if source.count(old) != 3:
                raise RuntimeError("Uncertainty mutation target changed; review the script")
            router.write_text(source.replace(old,
                    'sagaOrchestrator.compensate(saga.getSagaId(), "NARR");', 1))
            command = ["mvn", "-B", "--no-transfer-progress",
                       "-Dtest=UncertainSubmissionReproducerTest#delayedAcceptanceRetainsReservationAndNeedsReview", "test"]
            code = run(command, mutant, "uncertainty-negative-control")
            counts = report(mutant, OUT / "uncertainty-negative-control")
            detected = code != 0 and counts == dict(tests=1, failures=1, errors=0, skipped=0)
            results["uncertainty_negative_control"] = {
                "command": command, "exit_code": code,
                "mutation_detected": detected, **counts}
            ok &= detected
        service = "src/main/java/io/openfednow/reliability/ReliablePaymentService.java"
        migration = "src/main/resources/db/migration/V9__create_reliable_payment_reference.sql"
        mutants = [
            ("atomic-claim-negative-control", migration, [
                ("    CONSTRAINT uq_reliability_business UNIQUE (institution_id, direction, rail, business_key),\n", ""),
                ("    CONSTRAINT uq_reliability_transaction UNIQUE (institution_id, direction, rail, transaction_id),\n", ""),
                ("    CONSTRAINT uq_reliability_message UNIQUE (institution_id, direction, rail, message_id),\n", "")],
             "ReliablePaymentIntegrationTest#acceptancePostsOnceAndDuplicateRetrievesSameOperation"),
            ("premature-release-negative-control", service, [
                ('if ("SUBMITTING".equals(current.state()) || "RESERVED".equals(current.state())) {',
                 'if ("SUBMITTING".equals(current.state()) || "RESERVED".equals(current.state())) {\n'
                 '            jdbc.update("UPDATE reliability_account SET held_minor = held_minor - ? WHERE account_id = ?", current.amountMinor(), current.accountId());')],
             "ReliablePaymentIntegrationTest#lostAcceptanceStaysUnknownUntilInquiryAndNeverSubmitsAgain"),
            ("duplicate-event-negative-control", service, [
                ("if (eventInserted == 0) {",
                 'if (eventInserted == 0) {\n'
                 '            jdbc.update("UPDATE reliability_account SET ledger_minor = ledger_minor - ? WHERE account_id = ?", current.amountMinor(), current.accountId());')],
             "ReliablePaymentIntegrationTest#lostAcceptanceStaysUnknownUntilInquiryAndNeverSubmitsAgain"),
            ("missing-intent-negative-control", service, [
                ("UPDATE reliability_payment SET state = 'SUBMITTING', attempt_id = ?,",
                 "UPDATE reliability_payment SET state = 'RESERVED', attempt_id = ?,")],
             "ReliablePaymentIntegrationTest#possibleSendIntentSurvivesWorkerCrashWithoutReplay"),
        ]
        for name, path, replacements, test_name in mutants:
            result = run_reliability_mutant(files, name, path, replacements, test_name)
            results[name] = result
            ok &= result["mutation_detected"]
    if args.external:
        package_command = ["mvn", "-B", "--no-transfer-progress", "-DskipTests", "package"]
        package_code = run(package_command, ROOT, "external-package")
        external_command = ["python3", "harness/evaluate_external.py"]
        external_code = run(external_command, ROOT, "external-orchestrator") if package_code == 0 else 1
        external_results = OUT / "external-results.json"
        results["external"] = {"package_command": package_command,
                               "package_exit_code": package_code,
                               "command": external_command,
                               "exit_code": external_code,
                               "results_file": str(external_results),
                               "results_present": external_results.is_file()}
        ok &= package_code == 0 and external_code == 0 and external_results.is_file()
    results["evaluation_passed"] = bool(ok)
    (OUT / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    print(json.dumps(results, indent=2))
    print("Reports:", OUT)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
