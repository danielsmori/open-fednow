#!/usr/bin/env python3
"""Start three synthetic processes and run the external scenario oracle."""
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "target" / "evaluation"
JAR = ROOT / "target" / "openfednow-0.1.0-SNAPSHOT.jar"


def wait(url, process):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"process exited {process.returncode} before {url} became healthy")
        try:
            with urlopen(url, timeout=1) as response:
                if response.status == 200:
                    return
        except Exception:
            time.sleep(0.2)
    raise RuntimeError(f"health check timed out: {url}")


def start(command, log_name, env=None):
    log = (OUT / log_name).open("wb")
    process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=log,
                               stderr=subprocess.STDOUT)
    return process, log


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=12)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def main():
    started = time.monotonic()
    OUT.mkdir(parents=True, exist_ok=True)
    if not JAR.exists():
        raise RuntimeError("Build the current jar first: mvn -B package -DskipTests")
    for base in ("rail.sqlite", "reference.sqlite"):
        for suffix in ("", "-wal", "-shm"):
            (OUT / (base + suffix)).unlink(missing_ok=True)
    logs = []
    processes = []
    results = {}
    setup_seconds = None
    rss_kib = None
    try:
        rail, log = start([sys.executable, "harness/rail_simulator.py", "--port", "8099",
                           "--db", str(OUT / "rail.sqlite")], "rail-simulator.log")
        processes.append(rail); logs.append(log)
        wait("http://127.0.0.1:8099/health", rail)
        reference, log = start([sys.executable, "harness/reference_target.py", "--port", "8100",
                                "--db", str(OUT / "reference.sqlite"),
                                "--rail-url", "http://127.0.0.1:8099"], "reference-target.log")
        processes.append(reference); logs.append(log)
        wait("http://127.0.0.1:8100/health", reference)
        env = dict(os.environ)
        env.update({"DB_URL": env.get("DB_URL", "jdbc:postgresql://localhost:5432/openfednow"),
                    "DB_USERNAME": env.get("DB_USERNAME", "openfednow"),
                    "DB_PASSWORD": env.get("DB_PASSWORD", "openfednow"),
                    "SPRING_APPLICATION_JSON": json.dumps({"server": {"port": 8080},
                         "openfednow": {"reliability": {
                             "synthetic-rail-url": "http://127.0.0.1:8099",
                             "fixture-api-enabled": True,
                             "legacy-entry-bridge-enabled": True}}})})
        java = str(Path(env["JAVA_HOME"]) / "bin/java") if env.get("JAVA_HOME") else "java"
        target, log = start([java, "-jar", str(JAR)], "openfednow-target.log", env)
        processes.append(target); logs.append(log)
        wait("http://127.0.0.1:8080/fednow/health", target)
        setup_seconds = time.monotonic() - started
        rss_sample = subprocess.run(["ps", "-o", "rss=", "-p", str(target.pid)],
                                    text=True, capture_output=True)
        if rss_sample.returncode == 0 and rss_sample.stdout.strip().isdigit():
            rss_kib = int(rss_sample.stdout.strip())
        for name, url, mode in [("openfednow-sync", "http://127.0.0.1:8080", "SYNC"),
                                ("openfednow-async", "http://127.0.0.1:8080", "ASYNC"),
                                ("openfednow-bridge", "http://127.0.0.1:8080", "SYNC"),
                                ("reference", "http://127.0.0.1:8100", "SYNC")]:
            output = OUT / (name + ".json")
            command = [sys.executable, "harness/run.py", "--target-url", url,
                       "--rail-url", "http://127.0.0.1:8099",
                       "--core-mode", mode, "--output", str(output)]
            if name == "openfednow-bridge":
                command.extend(["--submit-path", "/fednow/send"])
            run_started = time.monotonic()
            run = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
            results[name] = {"exit_code": run.returncode, "stdout": run.stdout,
                             "stderr": run.stderr, "result_file": str(output),
                             "elapsed_seconds": time.monotonic() - run_started}
        output = OUT / "edge-cases.json"
        command = [sys.executable, "harness/edge_cases.py", "--output", str(output)]
        run_started = time.monotonic()
        run = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
        results["java-edge-cases"] = {"exit_code": run.returncode, "stdout": run.stdout,
                                       "stderr": run.stderr, "result_file": str(output),
                                       "elapsed_seconds": time.monotonic() - run_started}
        stop(target)
        command = [sys.executable, "harness/two_worker_case.py", "--jar", str(JAR),
                   "--rail-url", "http://127.0.0.1:8099",
                   "--output", str(OUT / "two-worker-s11.json")]
        run_started = time.monotonic()
        run = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, env=env)
        results["two-worker-s11"] = {"exit_code": run.returncode, "stdout": run.stdout,
                                      "stderr": run.stderr,
                                      "result_file": str(OUT / "two-worker-s11.json"),
                                      "elapsed_seconds": time.monotonic() - run_started}
        command = [sys.executable, "harness/restart_case.py", "--jar", str(JAR),
                   "--rail-url", "http://127.0.0.1:8099", "--output", str(OUT / "restart-s05.json"),
                   "--investigation-evidence", str(OUT / "edge-cases.json")]
        run_started = time.monotonic()
        run = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, env=env)
        results["restart-s05"] = {"exit_code": run.returncode, "stdout": run.stdout,
                                   "stderr": run.stderr,
                                   "result_file": str(OUT / "restart-s05.json"),
                                   "elapsed_seconds": time.monotonic() - run_started}
    except Exception as error:
        results["execution_error"] = repr(error)
    finally:
        for process in reversed(processes):
            stop(process)
        for log in logs:
            log.close()
    results["measurements"] = {"setup_seconds": setup_seconds,
                                "java_rss_kib_after_start": rss_kib,
                                "total_elapsed_seconds": time.monotonic() - started,
                                "resource_limitations": "One macOS ps RSS sample; no throughput or peak-memory benchmark"}
    results["all_passed"] = "execution_error" not in results and all(
        item["exit_code"] == 0 for item in results.values()
        if isinstance(item, dict) and "exit_code" in item)
    (OUT / "external-results.json").write_text(json.dumps(results, indent=2) + "\n")
    print(json.dumps({"all_passed": results["all_passed"],
                      "runs": {key: value.get("exit_code") for key, value in results.items()
                               if isinstance(value, dict)}}, indent=2))
    return 0 if results["all_passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
