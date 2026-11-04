#!/usr/bin/env python3
"""Exercise both console flows against the running synthetic Java/core/rail stack."""
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "harness"))
from run import Client, payment

app = Client("http://127.0.0.1:18080", "admin", "changeme")
rail = Client("http://127.0.0.1:18099")


def call(client, method, path, body=None):
    code, result = client.call(method, path, body)
    if code not in (200, 202):
        raise AssertionError(f"{method} {path}: HTTP {code}: {result}")
    return result


def snapshot(message, operation_id):
    local = call(app, "GET", f"/reference/v1/payments/{operation_id}")
    core = call(app, "GET", f"/reference/v1/fixtures/accounts/{message['debtorAccountNumber']}")
    remote = call(rail, "GET", f"/payments/{message['messageId']}")
    proxied_remote = call(app, "GET", f"/reference/v1/fixtures/rail/payments/{message['messageId']}")
    assert proxied_remote == remote, (proxied_remote, remote)
    effects = call(app, "GET", f"/reference/v1/fixtures/payments/{operation_id}/effects")
    return {"local": local, "syntheticCore": core, "syntheticRail": remote, "effects": effects}


def verify(flow):
    message = payment("payment-integration-" + flow, "SYN-DEMO-" + __import__("uuid").uuid4().hex[:16])
    call(app, "POST", "/reference/v1/fixtures/accounts", {
        "accountId": message["debtorAccountNumber"], "ledgerMinor": 100000,
        "exclusiveControl": True, "coreMode": "SYNC"})
    if flow == "lost-confirmation":
        call(rail, "POST", "/control/" + message["messageId"],
             {"status": "SETTLED", "lose_response": True})
    sent = call(app, "POST", "/fednow/send", message)
    operation = sent["operationId"]
    first = snapshot(message, operation)
    if flow == "lost-confirmation":
        assert first["local"]["state"] == "OUTCOME_UNKNOWN", first
        assert first["syntheticCore"]["ledgerMinor"] == 100000, first
        assert first["syntheticCore"]["heldMinor"] == 10000, first
        duplicate = call(app, "POST", "/fednow/send", message)
        assert duplicate["operationId"] == operation, duplicate
        repeated = snapshot(message, operation)
        assert repeated["syntheticRail"]["submitCount"] == 1, repeated
        call(app, "POST", f"/reference/v1/fixtures/payments/{operation}/inquiry-due")
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            final = snapshot(message, operation)
            if final["local"]["state"] == "SETTLED":
                break
            time.sleep(0.25)
        else:
            raise AssertionError("synthetic inquiry did not settle")
    else:
        repeated = None
        final = first
    assert final["local"]["state"] == "SETTLED", final
    assert final["syntheticCore"]["ledgerMinor"] == 90000, final
    assert final["syntheticCore"]["heldMinor"] == 0, final
    assert final["syntheticRail"]["submitCount"] == 1, final
    assert sorted(effect["effectType"] for effect in final["effects"]) == ["HOLD", "POST"], final
    return {"first": first, "repeated": repeated, "final": final}


if __name__ == "__main__":
    evidence = {"normal": verify("normal"), "lostConfirmation": verify("lost-confirmation")}
    output = Path("target/payment-integration/verification.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(evidence, indent=2) + "\n")
    print(f"Both synthetic flows passed; service and simulator evidence: {output}")
