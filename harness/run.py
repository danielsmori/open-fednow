#!/usr/bin/env python3
"""Run manifest scenarios against an external target and independent rail fixture."""
import argparse
import base64
import json
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent


class Client:
    def __init__(self, base, username=None, password=None):
        self.base = base.rstrip("/")
        self.auth = None if username is None else "Basic " + base64.b64encode(
            f"{username}:{password}".encode()).decode()

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        headers = {"Content-Type": "application/json"}
        if self.auth:
            headers["Authorization"] = self.auth
        request = Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=12) as response:
                raw = response.read()
                if not raw:
                    return response.status, None
                try:
                    value = json.loads(raw)
                except ValueError:
                    value = raw.decode(errors="replace")
                return response.status, value
        except HTTPError as error:
            raw = error.read()
            try:
                value = json.loads(raw)
            except ValueError:
                value = {"raw": raw.decode(errors="replace")}
            return error.code, value


class OpenFedNowDriver:
    def __init__(self, client, submit_path="/reference/v1/payments"):
        self.client = client
        self.submit_path = submit_path

    def seed(self, account, amount, exclusive=True, core_mode="SYNC"):
        return self.client.call("POST", "/reference/v1/fixtures/accounts",
                                {"accountId": account, "ledgerMinor": amount,
                                 "exclusiveControl": exclusive, "coreMode": core_mode})

    def external_debit(self, account, amount):
        return self.client.call("POST", f"/reference/v1/fixtures/accounts/{account}/external-debits",
                                {"amountMinor": amount})

    def submit(self, payment):
        return self.client.call("POST", self.submit_path, payment)

    def lookup(self, operation):
        return self.client.call("GET", f"/reference/v1/payments/{operation}")

    def effects(self, operation):
        return self.client.call("GET", f"/reference/v1/fixtures/payments/{operation}/effects")

    def account(self, account):
        return self.client.call("GET", f"/reference/v1/fixtures/accounts/{account}")

    def inquiry_due(self, operation):
        return self.client.call("POST", f"/reference/v1/fixtures/payments/{operation}/inquiry-due")

    def ack_core(self, operation):
        return self.client.call("POST", f"/reference/v1/fixtures/payments/{operation}/core-ack")


def payment(case, account, amount="100.00"):
    key = uuid.uuid4().hex[:16]
    return {"messageId": "M-" + key, "creationDateTime": datetime.now(timezone.utc).isoformat(),
            "numberOfTransactions": 1, "endToEndId": "E-" + key,
            "transactionId": "T-" + key, "interbankSettlementAmount": amount,
            "interbankSettlementCurrency": "USD", "debtorAgentRoutingNumber": "021000021",
            "creditorAgentRoutingNumber": "026009593", "debtorAccountNumber": account,
            "creditorAccountNumber": "SYN-RECEIVER", "debtorName": "Synthetic Sender",
            "creditorName": "Synthetic Receiver", "remittanceInformation": case}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def final_snapshot(driver, rail, view, message):
    _, effects = driver.effects(view["operationId"])
    _, account = driver.account(message["debtorAccountNumber"])
    _, remote = rail.call("GET", "/payments/" + message["messageId"])
    return {"local": view, "effects": effects, "account": account, "remote": remote}


def assert_effects(snapshot, state, amount):
    observed = sorted(effect["effectType"] for effect in snapshot["effects"])
    expected = ["HOLD", "POST"] if state == "SETTLED" else ["HOLD", "RELEASE"]
    require(observed == sorted(expected), f"financial effects {observed} != {expected}")
    account = snapshot["account"]
    require(account["heldMinor"] == 0, "final hold remains")
    require(account["ledgerMinor"] == (100000 - amount if state == "SETTLED" else 100000),
            "ledger balance differs from independent expected effect")
    require(snapshot["remote"]["status"] == state, "local final state disagrees with remote")
    require(snapshot["remote"]["submitCount"] == 1, "duplicate remote submission")


def concurrent_case(driver, rail, message, core_mode):
    barrier = "B-" + message["messageId"]
    rail.call("POST", "/control/" + message["messageId"],
              {"status": "SETTLED", "hold_after_effect": barrier})

    def first_submit():
        code, result = driver.submit(message)
        if core_mode == "ASYNC" and code in (200, 202):
            _, result = driver.ack_core(result["operationId"])
        return result

    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(first_submit)
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            remote_code, _ = rail.call("GET", "/payments/" + message["messageId"])
            if remote_code == 200:
                break
            time.sleep(0.05)
        require(remote_code == 200, "remote effect barrier was not reached")
        second_code, duplicate = driver.submit(message)
        require(second_code in (200, 202), "concurrent duplicate failed")
        rail.call("POST", "/barriers/" + barrier + "/release")
        original = first.result(timeout=8)
    require(original["operationId"] == duplicate["operationId"],
            "concurrent requests created distinct business operations")
    _, final = driver.lookup(original["operationId"])
    if final["state"] == "OUTCOME_UNKNOWN":
        driver.inquiry_due(final["operationId"])
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            _, final = driver.lookup(final["operationId"])
            if final["state"] == "SETTLED":
                break
            time.sleep(0.2)
    require(final["state"] == "SETTLED", "race did not reach settlement")
    snapshot = final_snapshot(driver, rail, final, message)
    assert_effects(snapshot, "SETTLED", 10000)
    return {**snapshot, "concurrency": {
        "first_operation_id": original["operationId"],
        "duplicate_operation_id": duplicate["operationId"],
        "duplicate_http": second_code,
        "first_observed_state": original["state"],
        "duplicate_observed_state": duplicate["state"],
        "barrier": barrier,
        "assertion": "same operation, one HOLD, one POST, one remote submit"}}


def run_case(case, driver, rail, core_mode):
    case_id = case["id"]
    account = "A-" + uuid.uuid4().hex[:18]
    exclusive = case_id != "S14"
    code, _ = driver.seed(account, 100000, exclusive, core_mode)
    require(code == 200, f"account seed failed: HTTP {code}")
    message = payment(case_id, account)
    if case_id == "S14":
        code, _ = driver.external_debit(account, 95000)
        require(code == 200, "synthetic external debit failed")
        code, _ = driver.submit(message)
        require(code in (409, 422, 503), "unsafe shared-account send was allowed")
        _, account_view = driver.account(account)
        require(account_view["heldMinor"] == 0, "unsafe hold created")
        remote_code, _ = rail.call("GET", "/payments/" + message["messageId"])
        require(remote_code == 404, "unsafe remote effect occurred")
        return {"account": account_view, "remote_http": remote_code}
    if case_id == "S16":
        invalid = dict(message, interbankSettlementAmount="1000.01")
        code, _ = driver.submit(invalid)
        require(code in (409, 422), "insufficient funds were not refused")
        invalid = dict(message, interbankSettlementAmount="0.001")
        code, _ = driver.submit(invalid)
        require(code in (400, 422), "fractional-cent amount was not refused")
        _, account_view = driver.account(account)
        require(account_view["heldMinor"] == 0, "invalid payment created a hold")
        first = payment("S16-first", account, "700.00")
        second = payment("S16-second", account, "700.00")
        with ThreadPoolExecutor(max_workers=2) as pool:
            responses = list(pool.map(driver.submit, (first, second)))
        accepted = [(code, body, msg) for (code, body), msg in zip(responses, (first, second))
                    if code in (200, 202)]
        refused = [(code, body, msg) for (code, body), msg in zip(responses, (first, second))
                   if code in (409, 422)]
        require(len(accepted) == 1 and len(refused) == 1,
                "distinct simultaneous reservations overspent or both failed")
        winner = accepted[0][2]
        if core_mode == "ASYNC":
            _, settled = driver.ack_core(accepted[0][1]["operationId"])
        else:
            settled = accepted[0][1]
        snapshot = final_snapshot(driver, rail, settled, winner)
        require(sorted(effect["effectType"] for effect in snapshot["effects"]) == ["HOLD", "POST"],
                "winning reservation did not post exactly once")
        require(snapshot["account"]["ledgerMinor"] == 30000
                and snapshot["account"]["heldMinor"] == 0,
                "simultaneous reservation balance is wrong")
        require(snapshot["remote"]["submitCount"] == 1, "winning payment submitted twice")
        lost_code, _ = rail.call("GET", "/payments/" + refused[0][2]["messageId"])
        require(lost_code == 404, "refused payment reached the rail")
        return {"winner": snapshot, "refusedHttp": refused[0][0],
                "refusedRemoteHttp": lost_code}
    if case_id == "S06":
        return concurrent_case(driver, rail, message, core_mode)
    if case_id in ("S02", "S03"):
        rail.call("POST", "/control/" + message["messageId"],
                  {"status": "REJECTED" if case_id == "S03" else "SETTLED", "lose_response": True})
    elif case_id == "S01":
        rail.call("POST", "/control/" + message["messageId"], {"status": "SETTLED"})
    elif case_id == "S17":
        rail.call("POST", "/control/" + message["messageId"], {"status": "REJECTED"})
    code, view = driver.submit(message)
    require(code in (200, 202), f"submit failed: HTTP {code}: {view}")
    if core_mode == "ASYNC":
        require(view["state"] == "CORE_PENDING", "async core did not defer rail send")
        remote_code, _ = rail.call("GET", "/payments/" + message["messageId"])
        require(remote_code == 404, "rail effect occurred before core acknowledgement")
        code, view = driver.ack_core(view["operationId"])
        require(code == 200, f"core acknowledgement failed: HTTP {code}")
    if case_id in ("S02", "S03"):
        require(view["state"] == "OUTCOME_UNKNOWN", "lost reply was treated as final")
        _, account_view = driver.account(account)
        require(account_view["heldMinor"] == 10000 and account_view["ledgerMinor"] == 100000,
                "funds released or posted before inquiry")
        pending_view = dict(view)
        code, duplicate = driver.submit(message)
        duplicate_http = code
        require(duplicate["operationId"] == view["operationId"], "duplicate did not retrieve original")
        code, _ = driver.inquiry_due(view["operationId"])
        require(code == 200, "could not advance synthetic inquiry clock")
        deadline = time.monotonic() + 15
        expected = "REJECTED" if case_id == "S03" else "SETTLED"
        while time.monotonic() < deadline:
            _, view = driver.lookup(view["operationId"])
            if view["state"] == expected:
                break
            time.sleep(0.2)
        require(view["state"] == expected, f"inquiry did not resolve: {view['state']}")
        snapshot = final_snapshot(driver, rail, view, message)
        assert_effects(snapshot, expected, 10000)
        return {**snapshot, "recovery": {
            "before_inquiry": pending_view,
            "account_before_inquiry": account_view,
            "duplicate_http": duplicate_http,
            "duplicate_operation_id": duplicate["operationId"],
            "expected_after_inquiry": expected}}
    if case_id == "S07":
        changed = dict(message, interbankSettlementAmount="101.00")
        code, _ = driver.submit(changed)
        require(code == 409, "changed payload did not conflict")
        changed_payee = dict(message, creditorAccountNumber="OTHER-RECEIVER")
        code, _ = driver.submit(changed_payee)
        require(code == 409, "changed payee did not conflict")
        snapshot = final_snapshot(driver, rail, view, message)
        assert_effects(snapshot, "SETTLED", 10000)
        other_account = "A-" + uuid.uuid4().hex[:18]
        code, _ = driver.seed(other_account, 100000, True, core_mode)
        require(code == 200, "second institution account seed failed")
        distinct_scope = dict(message, debtorAgentRoutingNumber="123456789",
                              debtorAccountNumber=other_account,
                              messageId="M-" + uuid.uuid4().hex[:16],
                              transactionId="T-" + uuid.uuid4().hex[:16])
        code, other_view = driver.submit(distinct_scope)
        require(code in (200, 202), "same business key in another institution was refused")
        if core_mode == "ASYNC":
            _, other_view = driver.ack_core(other_view["operationId"])
        require(other_view["operationId"] != view["operationId"],
                "different institution reused the original operation")
        other_snapshot = final_snapshot(driver, rail, other_view, distinct_scope)
        assert_effects(other_snapshot, "SETTLED", 10000)
        return {"original": snapshot, "other_institution": other_snapshot,
                "amountConflictHttp": 409, "payeeConflictHttp": 409}
    if case_id == "S17":
        snapshot = final_snapshot(driver, rail, view, message)
        corrupt = dict(snapshot, local=dict(view, state="SETTLED"))
        detected = corrupt["local"]["state"] != corrupt["remote"]["status"]
        require(detected, "independent oracle missed corrupted local success")
        assert_effects(snapshot, "REJECTED", 10000)
        return {"negative_control_detected": detected, "actual": snapshot,
                "mutated_local_state": corrupt["local"]["state"],
                "same_input_remote_state": corrupt["remote"]["status"],
                "mutation": "isolated corruption of the observed local state, not a production code path"}
    snapshot = final_snapshot(driver, rail, view, message)
    assert_effects(snapshot, "SETTLED", 10000)
    if case_id == "S01":
        rejected_account = "A-" + uuid.uuid4().hex[:18]
        code, _ = driver.seed(rejected_account, 100000, True, core_mode)
        require(code == 200, "could not seed rejection fixture")
        rejected_message = payment("S01-rejection", rejected_account)
        rail.call("POST", "/control/" + rejected_message["messageId"], {"status": "REJECTED"})
        code, rejected = driver.submit(rejected_message)
        require(code in (200, 202), "rejection fixture submission failed")
        if core_mode == "ASYNC":
            require(rejected["state"] == "CORE_PENDING", "async rejection was sent before core ack")
            _, rejected = driver.ack_core(rejected["operationId"])
        rejected_snapshot = final_snapshot(driver, rail, rejected, rejected_message)
        assert_effects(rejected_snapshot, "REJECTED", 10000)
        return {"accepted": snapshot, "rejected": rejected_snapshot}
    return snapshot


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target-url", required=True)
    parser.add_argument("--rail-url", required=True)
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="changeme")
    parser.add_argument("--output", default="target/evaluation/external-harness.json")
    parser.add_argument("--core-mode", choices=("SYNC", "ASYNC"), default="SYNC")
    parser.add_argument("--submit-path", default="/reference/v1/payments")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "scenarios-v1.json").read_text())
    driver = OpenFedNowDriver(Client(args.target_url, args.username, args.password), args.submit_path)
    rail = Client(args.rail_url)
    cases = []
    for case in (item for item in manifest["cases"] if item.get("executable", True)):
        try:
            trace = run_case(case, driver, rail, args.core_mode)
            cases.append({"id": case["id"], "result": "PASS", "trace": trace})
        except Exception as error:
            cases.append({"id": case["id"], "result": "FAIL", "error": str(error)})
    result = {"schema_version": manifest["schema_version"], "source": "synthetic",
              "target_url": args.target_url, "rail_url": args.rail_url,
              "core_mode": args.core_mode,
              "cases": cases,
              "not_in_this_runner": [{"id": item["id"], "coverage": item["coverage"]}
                                     for item in manifest["cases"] if not item.get("executable", True)]}
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"pass": sum(c["result"] == "PASS" for c in cases),
                      "fail": sum(c["result"] == "FAIL" for c in cases),
                      "output": str(out)}, indent=2))
    return 0 if all(c["result"] == "PASS" for c in cases) else 1


if __name__ == "__main__":
    raise SystemExit(main())
