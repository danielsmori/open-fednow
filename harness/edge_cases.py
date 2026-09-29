#!/usr/bin/env python3
"""Java-target-only malformed-response, inquiry, aging and delayed-core cases."""
import argparse
import json
import time
import uuid
from pathlib import Path

from run import Client, OpenFedNowDriver, payment, final_snapshot, assert_effects, require


def wait_for(driver, operation, predicate, seconds=12):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        code, view = driver.lookup(operation)
        require(code == 200, "payment disappeared")
        if predicate(view):
            return view
        time.sleep(0.2)
    raise AssertionError("timed out waiting for expected state")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target-url", default="http://127.0.0.1:8080")
    parser.add_argument("--rail-url", default="http://127.0.0.1:8099")
    parser.add_argument("--output", default="target/evaluation/edge-cases.json")
    args = parser.parse_args()
    driver = OpenFedNowDriver(Client(args.target_url, "admin", "changeme"))
    rail = Client(args.rail_url)
    cases = []

    def account():
        value = "A-" + uuid.uuid4().hex[:18]
        require(driver.seed(value, 100000)[0] == 200, "account seed failed")
        return value

    for mode in ("empty", "malformed", "failed", "uncorrelated"):
        name = "S04-submit-" + mode
        try:
            message = payment(name, account())
            rail.call("POST", "/control/" + message["messageId"],
                      {"status": "SETTLED", "submit_reply": mode})
            code, view = driver.submit(message)
            expected = "INVESTIGATION" if mode == "uncorrelated" else "OUTCOME_UNKNOWN"
            require(code == 202 and view["state"] == expected,
                    "submission fault fabricated a final status")
            _, held = driver.account(message["debtorAccountNumber"])
            _, effects = driver.effects(view["operationId"])
            _, events = rail.call("GET", "/events")
            matching = [event for event in events if event["messageId"] == message["messageId"]]
            require(held["ledgerMinor"] == 100000 and held["heldMinor"] == 10000,
                    "unknown payment changed available funds incorrectly")
            require([effect["effectType"] for effect in effects] == ["HOLD"],
                    "unknown payment acquired a final financial effect")
            require(len(matching) == 1 and matching[0]["kind"] == "REMOTE_EFFECT",
                    "independent rail effect count is wrong")
            cases.append({"id": name, "result": "PASS", "local": view,
                          "account": held, "effects": effects, "remoteEvents": matching})
        except Exception as error:
            cases.append({"id": name, "result": "FAIL", "error": repr(error)})

    for mode in ("empty", "malformed", "failed", "uncorrelated"):
        name = "S04-inquiry-" + mode
        try:
            message = payment(name, account())
            rail.call("POST", "/control/" + message["messageId"],
                      {"status": "SETTLED", "lose_response": True,
                       "inquiry_reply": mode})
            code, pending = driver.submit(message)
            require(code == 202 and pending["state"] == "OUTCOME_UNKNOWN",
                    "lost response did not create unknown obligation")
            driver.inquiry_due(pending["operationId"])
            view = wait_for(driver, pending["operationId"],
                            lambda row: row["inquiryCount"] >= 1 or row["state"] == "INVESTIGATION")
            require(view["state"] in ("OUTCOME_UNKNOWN", "INVESTIGATION"),
                    "inquiry fault fabricated finality")
            _, held = driver.account(message["debtorAccountNumber"])
            _, effects = driver.effects(view["operationId"])
            require(held["heldMinor"] == 10000 and held["ledgerMinor"] == 100000,
                    "failed inquiry released or posted funds")
            require([effect["effectType"] for effect in effects] == ["HOLD"],
                    "failed inquiry produced final financial effect")
            cases.append({"id": name, "result": "PASS", "local": view,
                          "account": held, "effects": effects})
        except Exception as error:
            cases.append({"id": name, "result": "FAIL", "error": repr(error)})

    try:
        message = payment("S12-aged-investigation", account())
        rail.call("POST", "/control/" + message["messageId"],
                  {"status": "SETTLED", "lose_response": True, "inquiry_reply": "failed"})
        code, pending = driver.submit(message)
        require(code == 202 and pending["state"] == "OUTCOME_UNKNOWN", "unknown not retained")
        require(pending["inquiryCount"] == 0 and pending["nextInquiryAt"] is not None,
                "inquiry incorrectly due before synthetic wait")
        code, _ = driver.client.call("POST", f"/reference/v1/fixtures/payments/{pending['operationId']}/age")
        require(code == 200, "could not age synthetic obligation")
        investigated = wait_for(driver, pending["operationId"],
                                lambda row: row["state"] == "INVESTIGATION")
        _, held = driver.account(message["debtorAccountNumber"])
        _, effects = driver.effects(pending["operationId"])
        require(investigated["lastInquiryAt"] is not None and investigated["inquiryCount"] == 1,
                "aged obligation omitted inquiry attempt record")
        require(held["heldMinor"] == 10000 and held["ledgerMinor"] == 100000,
                "aged investigation erased the obligation")
        require([effect["effectType"] for effect in effects] == ["HOLD"],
                "aged investigation applied a final effect")
        cases.append({"id": "S12-aged-investigation", "result": "PASS",
                      "local": investigated, "account": held, "effects": effects})
    except Exception as error:
        cases.append({"id": "S12-aged-investigation", "result": "FAIL", "error": repr(error)})

    try:
        offline_account = account()
        code, _ = driver.client.call("POST",
            f"/reference/v1/fixtures/accounts/{offline_account}/availability",
            {"available": False})
        require(code == 200, "could not make synthetic core unavailable")
        offline_message = payment("S15-core-unavailable", offline_account)
        code, _ = driver.submit(offline_message)
        require(code == 409, "send proceeded while synthetic core unavailable")
        _, offline_balance = driver.account(offline_account)
        remote_code, _ = rail.call("GET", "/payments/" + offline_message["messageId"])
        require(offline_balance["heldMinor"] == 0 and remote_code == 404,
                "unavailable core created hold or remote effect")
        cases.append({"id": "S15-core-unavailable", "result": "PASS",
                      "account": offline_balance, "remoteHttp": remote_code})
    except Exception as error:
        cases.append({"id": "S15-core-unavailable", "result": "FAIL", "error": repr(error)})

    try:
        value = "A-" + uuid.uuid4().hex[:18]
        require(driver.seed(value, 100000, True, "ASYNC")[0] == 200,
                "async core account seed failed")
        message = payment("S15-delayed-core-ack", value)
        code, pending = driver.submit(message)
        require(code == 202 and pending["state"] == "CORE_PENDING",
                "unavailable core acknowledgment did not defer dispatch")
        _, held = driver.account(value)
        remote_code, _ = rail.call("GET", "/payments/" + message["messageId"])
        require(held["heldMinor"] == 10000 and remote_code == 404,
                "payment sent before core acknowledgment")
        code, duplicate = driver.submit(message)
        require(code == 202 and duplicate["operationId"] == pending["operationId"],
                "delayed core acknowledgment lost ownership")
        driver.client.call("POST", f"/reference/v1/fixtures/accounts/{value}/availability",
                           {"available": False})
        code, still_pending = driver.ack_core(pending["operationId"])
        require(code == 200 and still_pending["state"] == "CORE_PENDING",
                "unavailable core acknowledgment caused dispatch")
        driver.client.call("POST", f"/reference/v1/fixtures/accounts/{value}/availability",
                           {"available": True})
        code, settled = driver.ack_core(pending["operationId"])
        require(code == 200 and settled["state"] == "SETTLED",
                "recovered core acknowledgment did not dispatch")
        snapshot = final_snapshot(driver, rail, settled, message)
        assert_effects(snapshot, "SETTLED", 10000)
        cases.append({"id": "S15-delayed-core-ack", "result": "PASS",
                      "pending": pending, "final": snapshot})
    except Exception as error:
        cases.append({"id": "S15-delayed-core-ack", "result": "FAIL", "error": repr(error)})

    result = {"target": args.target_url, "cases": cases,
              "all_passed": all(case["result"] == "PASS" for case in cases)}
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"pass": sum(case["result"] == "PASS" for case in cases),
                      "fail": sum(case["result"] == "FAIL" for case in cases)}, indent=2))
    return 0 if result["all_passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
