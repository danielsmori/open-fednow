#!/usr/bin/env python3
"""Independent minimal synthetic payment target for harness portability checks."""
import argparse
import base64
import hashlib
import json
import sqlite3
import threading
import uuid
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def now():
    return datetime.now(timezone.utc).isoformat()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8100)
    parser.add_argument("--db", required=True)
    parser.add_argument("--rail-url", required=True)
    args = parser.parse_args()
    db = sqlite3.connect(args.db, check_same_thread=False)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("""CREATE TABLE IF NOT EXISTS accounts
        (id TEXT PRIMARY KEY, ledger INTEGER, held INTEGER, exclusive INTEGER, version INTEGER)""")
    db.execute("""CREATE TABLE IF NOT EXISTS payments
        (id TEXT PRIMARY KEY, institution TEXT, business_key TEXT, fingerprint TEXT,
         transaction_id TEXT, message_id TEXT, account_id TEXT, amount INTEGER,
         state TEXT, attempt TEXT, created TEXT, updated TEXT,
         UNIQUE(institution,business_key), UNIQUE(institution,transaction_id),
         UNIQUE(institution,message_id))""")
    db.execute("""CREATE TABLE IF NOT EXISTS effects
        (payment_id TEXT, kind TEXT, account_id TEXT, amount INTEGER,
         PRIMARY KEY(payment_id,kind))""")
    db.commit()
    lock = threading.RLock()

    def rail(method, path, body=None):
        request = Request(args.rail_url + path,
                          data=None if body is None else json.dumps(body).encode(),
                          headers={"Content-Type": "application/json"}, method=method)
        try:
            with urlopen(request, timeout=2) as response:
                return json.loads(response.read())
        except HTTPError as error:
            if error.code == 404:
                return None
            raise

    def view(payment_id):
        row = db.execute("SELECT * FROM payments WHERE id=?", (payment_id,)).fetchone()
        if row is None:
            return None
        return {"operationId": row[0], "institutionId": row[1], "businessKey": row[2],
                "transactionId": row[4], "messageId": row[5], "accountId": row[6],
                "amountMinor": row[7], "state": row[8], "attemptId": row[9],
                "createdAt": row[10], "updatedAt": row[11]}

    def account_view(account_id):
        row = db.execute("SELECT * FROM accounts WHERE id=?", (account_id,)).fetchone()
        if row is None:
            return None
        return {"accountId": row[0], "ledgerMinor": row[1], "heldMinor": row[2],
                "exclusiveControl": bool(row[3]), "version": row[4]}

    def apply(payment_id, observation):
        with lock:
            payment = view(payment_id)
            if payment is None:
                return None
            if observation is None:
                return payment
            if (observation.get("source") != "SYNTHETIC_RAIL"
                    or observation.get("messageId") != payment["messageId"]
                    or observation.get("businessKey") != payment["businessKey"]
                    or observation.get("transactionId") != payment["transactionId"]):
                db.execute("UPDATE payments SET state='INVESTIGATION',updated=? WHERE id=?",
                           (now(), payment_id))
                db.commit()
                return view(payment_id)
            state = observation.get("status")
            if state not in ("SETTLED", "REJECTED"):
                return payment
            if payment["state"] in ("SETTLED", "REJECTED", "INVESTIGATION"):
                if payment["state"] != state:
                    db.execute("UPDATE payments SET state='INVESTIGATION',updated=? WHERE id=?",
                               (now(), payment_id))
                    db.commit()
                return view(payment_id)
            kind = "POST" if state == "SETTLED" else "RELEASE"
            inserted = db.execute("INSERT OR IGNORE INTO effects VALUES (?,?,?,?)",
                                  (payment_id, kind, payment["accountId"], payment["amountMinor"]))
            if inserted.rowcount:
                if kind == "POST":
                    db.execute("UPDATE accounts SET held=held-?,ledger=ledger-?,version=version+1 WHERE id=?",
                               (payment["amountMinor"], payment["amountMinor"], payment["accountId"]))
                else:
                    db.execute("UPDATE accounts SET held=held-?,version=version+1 WHERE id=?",
                               (payment["amountMinor"], payment["accountId"]))
            db.execute("UPDATE payments SET state=?,updated=? WHERE id=?", (state, now(), payment_id))
            db.commit()
            return view(payment_id)

    class Handler(BaseHTTPRequestHandler):
        def respond(self, code, value):
            payload = json.dumps(value).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def read_json(self):
            return json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))

        def authorized(self):
            expected = "Basic " + base64.b64encode(b"admin:changeme").decode()
            if self.headers.get("Authorization") != expected:
                self.respond(401, {"error": "admin authentication required"})
                return False
            return True

        def do_POST(self):
            if not self.authorized():
                return
            path = self.path
            if path == "/reference/v1/fixtures/accounts":
                request = self.read_json()
                with lock:
                    try:
                        db.execute("INSERT INTO accounts VALUES (?,?,?,?,0)",
                                   (request["accountId"], request["ledgerMinor"], 0,
                                    int(request["exclusiveControl"])))
                        db.commit()
                    except sqlite3.IntegrityError:
                        return self.respond(409, {"error": "account already exists"})
                    return self.respond(200, account_view(request["accountId"]))
            if path.endswith("/external-debits"):
                account_id = path.split("/")[-2]
                amount = self.read_json()["amountMinor"]
                with lock:
                    changed = db.execute("UPDATE accounts SET ledger=ledger-?,version=version+1 "
                                         "WHERE id=? AND ledger-held>=?", (amount, account_id, amount))
                    db.commit()
                    return self.respond(200 if changed.rowcount else 409,
                                        account_view(account_id) if changed.rowcount else {"error": "funds"})
            if path.endswith("/inquiry-due"):
                payment_id = path.split("/")[-2]
                with lock:
                    payment = view(payment_id)
                if payment is None:
                    return self.respond(404, {"error": "unknown payment"})
                if payment["state"] not in ("SUBMITTING", "OUTCOME_UNKNOWN"):
                    return self.respond(409, {"error": "not unresolved"})
                try:
                    observation = rail("GET", "/payments/" + payment["messageId"])
                except Exception:
                    observation = None
                apply(payment_id, observation)
                return self.respond(200, view(payment_id))
            if path != "/reference/v1/payments":
                return self.respond(404, {"error": "unknown path"})
            request = self.read_json()
            try:
                amount = Decimal(str(request["interbankSettlementAmount"])) * 100
                if request["interbankSettlementCurrency"] != "USD" or amount <= 0 or amount != amount.to_integral_value():
                    return self.respond(422, {"error": "invalid USD amount"})
                amount = int(amount)
            except (InvalidOperation, KeyError, ValueError):
                return self.respond(422, {"error": "invalid amount"})
            identity = [request[k] for k in ("debtorAgentRoutingNumber", "endToEndId",
                        "transactionId", "messageId", "debtorAccountNumber",
                        "creditorAgentRoutingNumber", "creditorAccountNumber", "debtorName",
                        "creditorName")]
            identity += [request.get("remittanceInformation") or "", str(amount), "USD"]
            fingerprint = hashlib.sha256("\x1f".join(["v1"] + identity).encode()).hexdigest()
            payment_id = str(uuid.uuid4())
            attempt_id = str(uuid.uuid4())
            with lock:
                old = db.execute("SELECT id,fingerprint FROM payments WHERE institution=? AND business_key=?",
                                 (request["debtorAgentRoutingNumber"], request["endToEndId"])).fetchone()
                if old:
                    return self.respond(200 if old[1] == fingerprint else 409,
                                        view(old[0]) if old[1] == fingerprint else {"error": "payload conflict"})
                account = account_view(request["debtorAccountNumber"])
                if account is None or not account["exclusiveControl"] or account["ledgerMinor"] - account["heldMinor"] < amount:
                    return self.respond(409, {"error": "no enforceable reservation"})
                try:
                    db.execute("INSERT INTO payments VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                               (payment_id, request["debtorAgentRoutingNumber"], request["endToEndId"],
                                fingerprint, request["transactionId"], request["messageId"],
                                request["debtorAccountNumber"], amount, "SUBMITTING", attempt_id, now(), now()))
                    db.execute("UPDATE accounts SET held=held+?,version=version+1 WHERE id=?",
                               (amount, request["debtorAccountNumber"]))
                    db.execute("INSERT INTO effects VALUES (?,?,?,?)",
                               (payment_id, "HOLD", request["debtorAccountNumber"], amount))
                    db.commit()
                except sqlite3.IntegrityError:
                    db.rollback()
                    return self.respond(409, {"error": "identifier collision"})
            try:
                observation = rail("POST", "/submit", {"message": request, "attemptId": attempt_id})
                apply(payment_id, observation)
            except Exception:
                with lock:
                    db.execute("UPDATE payments SET state='OUTCOME_UNKNOWN',updated=? "
                               "WHERE id=? AND state='SUBMITTING'", (now(), payment_id))
                    db.commit()
            current = view(payment_id)
            return self.respond(202 if current["state"] == "OUTCOME_UNKNOWN" else 200, current)

        def do_GET(self):
            if self.path == "/health":
                return self.respond(200, {"ok": True, "mode": "minimal-reference"})
            if not self.authorized():
                return
            parts = self.path.split("/")
            with lock:
                if self.path.startswith("/reference/v1/fixtures/accounts/"):
                    value = account_view(parts[-1])
                elif self.path.endswith("/effects"):
                    payment_id = parts[-2]
                    value = [{"operationId": r[0], "effectType": r[1], "accountId": r[2],
                              "amountMinor": r[3]} for r in db.execute(
                                  "SELECT * FROM effects WHERE payment_id=? ORDER BY kind", (payment_id,))]
                elif self.path.startswith("/reference/v1/payments/"):
                    value = view(parts[-1])
                else:
                    value = None
            return self.respond(200 if value is not None else 404, value or {"error": "not found"})

        def log_message(self, format_string, *values):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    try:
        server.serve_forever()
    finally:
        db.close()


if __name__ == "__main__":
    main()
