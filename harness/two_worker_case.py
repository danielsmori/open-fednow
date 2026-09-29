#!/usr/bin/env python3
"""Two JVMs contend for one SQL inquiry lease; stale completion is fenced."""
import argparse
import json
import os
import subprocess
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from run import Client, OpenFedNowDriver, payment, final_snapshot, assert_effects, require


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--rail-url", default="http://127.0.0.1:8099")
    parser.add_argument("--output", default="target/evaluation/two-worker-s11.json")
    args = parser.parse_args()
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    rail = Client(args.rail_url)
    clients = [Client("http://127.0.0.1:8081", "admin", "changeme"),
               Client("http://127.0.0.1:8082", "admin", "changeme")]
    processes = []
    logs = []
    result = {"id": "S11", "result": "FAIL", "workers": 2, "leaseSeconds": 15}
    try:
        for index, port in enumerate((8081, 8082)):
            log = out.with_suffix(f".worker{index + 1}.log").open("wb")
            logs.append(log)
            env = dict(os.environ)
            env.update({"DB_URL": "jdbc:postgresql://localhost:5432/openfednow",
                        "DB_USERNAME": "openfednow", "DB_PASSWORD": "openfednow",
                        "SPRING_APPLICATION_JSON": json.dumps({"server": {"port": port},
                            "openfednow": {"reliability": {
                                "synthetic-rail-url": args.rail_url,
                                "fixture-api-enabled": True,
                                "inquiry-worker-enabled": False}}})})
            java = str(Path(env["JAVA_HOME"]) / "bin/java") if env.get("JAVA_HOME") else "java"
            processes.append(subprocess.Popen([java, "-jar", args.jar], env=env,
                                            stdout=log, stderr=subprocess.STDOUT))
        for client, process in zip(clients, processes):
            deadline = time.monotonic() + 35
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f"worker JVM exited {process.returncode}")
                try:
                    if client.call("GET", "/fednow/health")[0] == 200:
                        break
                except Exception:
                    time.sleep(0.2)
            else:
                raise RuntimeError("worker JVM did not start")
        driver = OpenFedNowDriver(clients[0])
        account = "A-" + uuid.uuid4().hex[:18]
        require(driver.seed(account, 100000)[0] == 200, "account seed failed")
        message = payment("S11", account)
        rail.call("POST", "/control/" + message["messageId"],
                  {"status": "SETTLED", "lose_response": True})
        code, pending = driver.submit(message)
        require(code == 202 and pending["state"] == "OUTCOME_UNKNOWN", "unknown not retained")
        operation = pending["operationId"]
        driver.inquiry_due(operation)
        path = f"/reference/v1/fixtures/payments/{operation}/claim-inquiry"
        with ThreadPoolExecutor(max_workers=2) as pool:
            claims = list(pool.map(lambda client: client.call("POST", path), clients))
        winners = [(i, value) for i, value in enumerate(claims) if value[0] == 200]
        require(len(winners) == 1 and sum(value[0] == 409 for value in claims) == 1,
                "two JVMs both claimed one inquiry")
        first_index, first = winners[0]
        first_token = first[1]["token"]
        _, held = driver.account(account)
        require(held["heldMinor"] == 10000 and held["ledgerMinor"] == 100000,
                "lease claim moved funds")
        time.sleep(16.2)
        second_index = 1 - first_index
        code, second = clients[second_index].call("POST", path)
        require(code == 200 and second["token"] != first_token,
                "expired lease was not claimable by other JVM")
        _, remote = rail.call("GET", "/payments/" + message["messageId"])
        finish = f"/reference/v1/fixtures/payments/{operation}/finish-inquiry"
        code, stale = clients[first_index].call("POST", finish,
                                               {"token": first_token, "observation": remote})
        require(code == 200 and stale["state"] == "OUTCOME_UNKNOWN",
                "stale worker applied a final result")
        code, final = clients[second_index].call("POST", finish,
                                                {"token": second["token"],
                                                 "observation": remote})
        require(code == 200 and final["state"] == "SETTLED",
                "active worker did not reconcile")
        snapshot = final_snapshot(driver, rail, final, message)
        assert_effects(snapshot, "SETTLED", 10000)
        result.update({"result": "PASS", "firstWorker": first_index + 1,
                       "secondWorker": second_index + 1, "stale": stale,
                       "firstToken": first_token, "secondToken": second["token"],
                       "snapshot": snapshot})
    except Exception as error:
        result["error"] = repr(error)
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
        for log in logs:
            log.close()
    out.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"result": result["result"], "error": result.get("error")}, indent=2))
    return 0 if result["result"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
