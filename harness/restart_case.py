#!/usr/bin/env python3
"""S05: kill the Java target after a synthetic remote effect, then recover."""
import argparse
import json
import os
import subprocess
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from run import Client, OpenFedNowDriver, assert_effects, final_snapshot, payment, require


def wait_healthy(client, deadline_seconds=25):
    deadline = time.monotonic() + deadline_seconds
    while time.monotonic() < deadline:
        try:
            code, _ = client.call("GET", "/fednow/health")
            if code == 200:
                return
        except Exception:
            pass
        time.sleep(0.2)
    raise RuntimeError("target did not become healthy")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--rail-url", default="http://127.0.0.1:8099")
    parser.add_argument("--target-port", type=int, default=8081)
    parser.add_argument("--output", default="target/evaluation/restart-s05.json")
    args = parser.parse_args()
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    log_path = out.with_suffix(".target.log")
    rail = Client(args.rail_url)
    target = Client(f"http://127.0.0.1:{args.target_port}", "admin", "changeme")
    driver = OpenFedNowDriver(target)
    app = None
    run_log = log_path.open("wb")

    def start():
        env = dict(os.environ)
        env.update({"DB_URL": "jdbc:postgresql://localhost:5432/openfednow",
                    "DB_USERNAME": "openfednow", "DB_PASSWORD": "openfednow",
                    "SPRING_APPLICATION_JSON": json.dumps({"server": {"port": args.target_port},
                        "openfednow": {"reliability": {
                            "synthetic-rail-url": args.rail_url,
                            "fixture-api-enabled": True}}})})
        java = Path(env.get("JAVA_HOME", "")) / "bin/java" if env.get("JAVA_HOME") else "java"
        return subprocess.Popen([str(java), "-jar", args.jar], env=env,
                                stdout=run_log, stderr=subprocess.STDOUT)

    result = {"id": "S05", "result": "FAIL"}
    try:
        app = start()
        wait_healthy(target)
        account = "A-" + uuid.uuid4().hex[:18]
        code, _ = driver.seed(account, 100000, True)
        require(code == 200, "could not seed account")
        message = payment("S05", account)
        barrier = "RESTART-" + message["messageId"]
        rail.call("POST", "/control/" + message["messageId"],
                  {"status": "SETTLED", "hold_after_effect": barrier})
        with ThreadPoolExecutor(max_workers=1) as pool:
            pending_request = pool.submit(driver.submit, message)
            deadline = time.monotonic() + 8
            observed = None
            while time.monotonic() < deadline:
                remote_code, observed = rail.call("GET", "/payments/" + message["messageId"])
                if remote_code == 200:
                    break
                time.sleep(0.05)
            require(remote_code == 200, "remote effect did not occur before kill")
            app.kill()  # deliberate hard termination at the post-effect boundary
            app.wait(timeout=5)
            rail.call("POST", "/barriers/" + barrier + "/release")
            try:
                pending_request.result(timeout=5)
            except Exception:
                pass
        app = start()
        wait_healthy(target)
        code, pending = target.call("GET", "/reference/v1/payments/by-key/" +
                                    message["debtorAgentRoutingNumber"] + "/" + message["endToEndId"])
        require(code == 200, "durable payment identity lost after process restart")
        require(pending["state"] in ("SUBMITTING", "OUTCOME_UNKNOWN"),
                "restart invented final payment status")
        _, held_account = driver.account(account)
        require(held_account["heldMinor"] == 10000 and held_account["ledgerMinor"] == 100000,
                "restart released or posted funds before inquiry")
        code, duplicate = driver.submit(message)
        require(duplicate["operationId"] == pending["operationId"],
                "retry after restart created a second operation")
        driver.inquiry_due(pending["operationId"])
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            _, current = driver.lookup(pending["operationId"])
            if current["state"] == "SETTLED":
                break
            time.sleep(0.2)
        require(current["state"] == "SETTLED", "authoritative synthetic inquiry did not settle")
        snapshot = final_snapshot(driver, rail, current, message)
        assert_effects(snapshot, "SETTLED", 10000)
        result = {"id": "S05", "result": "PASS", "pre_inquiry": pending,
                  "pre_inquiry_account": held_account, "final": snapshot,
                  "fault_boundary": "SIGKILL after remote effect and before local outcome persistence",
                  "target_log": str(log_path)}
    except Exception as error:
        result["error"] = str(error)
    finally:
        if app and app.poll() is None:
            app.terminate()
            try:
                app.wait(timeout=10)
            except subprocess.TimeoutExpired:
                app.kill()
        run_log.close()
    out.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"result": result["result"], "error": result.get("error"),
                      "output": str(out)}, indent=2))
    return 0 if result["result"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
