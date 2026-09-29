#!/usr/bin/env python3
"""Independent, stateful synthetic rail. No FedNow wire protocol is implemented."""
import argparse
import json
import socket
import sqlite3
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--db", required=True, help="SQLite file; retained across simulator restart")
    args = parser.parse_args()
    db = sqlite3.connect(args.db, check_same_thread=False)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("""CREATE TABLE IF NOT EXISTS payments
        (message_id TEXT PRIMARY KEY, business_key TEXT, transaction_id TEXT,
         status TEXT, submit_count INTEGER, attempt_id TEXT)""")
    db.execute("""CREATE TABLE IF NOT EXISTS events
        (id INTEGER PRIMARY KEY AUTOINCREMENT, message_id TEXT, kind TEXT, status TEXT)""")
    db.commit()
    lock = threading.Lock()
    faults = {}
    barriers = {}

    class Handler(BaseHTTPRequestHandler):
        def json(self, code, value):
            body = json.dumps(value).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def read_json(self):
            if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
                chunks = []
                while True:
                    size = int(self.rfile.readline().split(b";", 1)[0].strip(), 16)
                    if size == 0:
                        self.rfile.readline()
                        break
                    chunks.append(self.rfile.read(size))
                    self.rfile.read(2)
                return json.loads(b"".join(chunks))
            length = int(self.headers.get("Content-Length", "0"))
            return json.loads(self.rfile.read(length))

        def do_POST(self):
            if self.path.startswith("/control/"):
                key = unquote(self.path.split("/", 2)[2])
                with lock:
                    faults[key] = self.read_json()
                return self.json(200, {"configured": key})
            if self.path.startswith("/barriers/") and self.path.endswith("/release"):
                key = unquote(self.path.split("/")[2])
                with lock:
                    barriers.setdefault(key, threading.Event()).set()
                return self.json(200, {"released": key})
            if self.path != "/submit":
                return self.json(404, {"error": "unknown path"})
            request = self.read_json()
            payment = request["message"]
            key = payment["messageId"]
            with lock:
                fault = faults.get(key, {})
            barrier = fault.get("hold_before_effect")
            if barrier:
                with lock:
                    gate = barriers.setdefault(barrier, threading.Event())
                gate.wait(30)
            with lock:
                row = db.execute("SELECT status, submit_count FROM payments WHERE message_id=?", (key,)).fetchone()
                if row:
                    status, count = row
                    db.execute("UPDATE payments SET submit_count=? WHERE message_id=?", (count + 1, key))
                    db.execute("INSERT INTO events (message_id,kind,status) VALUES (?,?,?)",
                               (key, "DUPLICATE_SUBMIT", status))
                else:
                    status = fault.get("status", "SETTLED")
                    if status not in ("SETTLED", "REJECTED", "PENDING"):
                        return self.json(400, {"error": "invalid synthetic status"})
                    db.execute("INSERT INTO payments VALUES (?,?,?,?,?,?)",
                               (key, payment["endToEndId"], payment["transactionId"],
                                status, 1, request["attemptId"]))
                    db.execute("INSERT INTO events (message_id,kind,status) VALUES (?,?,?)",
                               (key, "REMOTE_EFFECT", status))
                db.commit()
            barrier = fault.get("hold_after_effect")
            if barrier:
                with lock:
                    gate = barriers.setdefault(barrier, threading.Event())
                gate.wait(30)
            if fault.get("lose_response"):
                try:
                    self.connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                self.connection.close()
                return
            self.json(200, observation(payment, status, "SIM-" + key))

        def do_GET(self):
            if self.path == "/health":
                return self.json(200, {"ok": True, "mode": "synthetic"})
            if self.path == "/events":
                with lock:
                    rows = db.execute("SELECT id,message_id,kind,status FROM events ORDER BY id").fetchall()
                return self.json(200, [{"id": r[0], "messageId": r[1], "kind": r[2],
                                        "status": r[3]} for r in rows])
            if self.path.startswith("/barriers/"):
                key = unquote(self.path.split("/", 2)[2])
                with lock:
                    gate = barriers.get(key)
                return self.json(200, {"released": bool(gate and gate.is_set()),
                                       "created": gate is not None})
            if self.path.startswith("/payments/"):
                key = unquote(self.path.split("/", 2)[2])
                with lock:
                    row = db.execute("SELECT business_key,transaction_id,status,submit_count "
                                     "FROM payments WHERE message_id=?", (key,)).fetchone()
                if not row:
                    return self.json(404, {"error": "untraceable"})
                return self.json(200, {"transactionId": row[1], "businessKey": row[0],
                                       "messageId": key, "status": row[2],
                                       "source": "SYNTHETIC_RAIL", "reference": "SIM-" + key,
                                       "submitCount": row[3]})
            return self.json(404, {"error": "unknown path"})

        def log_message(self, format_string, *values):
            pass

    def observation(payment, status, reference):
        return {"transactionId": payment["transactionId"],
                "businessKey": payment["endToEndId"], "messageId": payment["messageId"],
                "status": status, "source": "SYNTHETIC_RAIL", "reference": reference}

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    try:
        server.serve_forever()
    finally:
        db.close()


if __name__ == "__main__":
    main()
