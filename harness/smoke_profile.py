#!/usr/bin/env python3
"""One-command portable profile smoke run, using only project-built Python fixtures."""
import argparse
import hashlib
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def ready(url, process):
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        if process.poll() is not None:
            return False
        try:
            with urlopen(url, timeout=0.5) as response:
                if response.status == 200:
                    return True
        except Exception:
            time.sleep(0.1)
    return False


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", default="S02", choices=("S01", "S02", "S06", "S16"))
    parser.add_argument("--all", action="store_true", help="Run every case in send reliability v1")
    parser.add_argument("--output", default="target/portable/send-v1-smoke.json")
    args = parser.parse_args(argv)
    target_revision = "reference_target.py:sha256:" + hashlib.sha256(
        (ROOT / "harness/reference_target.py").read_bytes()).hexdigest()[:16]
    with tempfile.TemporaryDirectory(prefix="openfednow-profile-") as directory:
        state = Path(directory)
        rail_port, target_port = free_port(), free_port()
        while target_port == rail_port:
            target_port = free_port()
        rail_url = f"http://127.0.0.1:{rail_port}"
        target_url = f"http://127.0.0.1:{target_port}"
        processes = []
        try:
            with (state / "rail.log").open("wb") as log:
                rail = subprocess.Popen([sys.executable, str(ROOT / "harness/rail_simulator.py"),
                    "--port", str(rail_port), "--db", str(state / "rail.sqlite")],
                    cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
                processes.append(rail)
                rail_ok = ready(rail_url + "/health", rail)
            if rail_ok:
                with (state / "target.log").open("wb") as log:
                    target = subprocess.Popen([sys.executable, str(ROOT / "harness/reference_target.py"),
                        "--port", str(target_port), "--db", str(state / "reference.sqlite"),
                        "--rail-url", rail_url], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
                    processes.append(target)
                    ready(target_url + "/health", target)
            command = [sys.executable, str(ROOT / "harness/portable_profile.py"),
                "--target-url", target_url, "--rail-url", rail_url,
                "--target-revision", target_revision, "--environment-type", "synthetic-local",
                "--output", args.output]
            if not args.all:
                command.extend(["--case", args.case])
            return subprocess.run(command, cwd=ROOT, check=False).returncode
        finally:
            for process in reversed(processes):
                stop(process)


if __name__ == "__main__":
    raise SystemExit(main())
