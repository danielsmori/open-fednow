#!/usr/bin/env python3
"""Check that a clean checkout can run the synthetic evaluation before it is started.

Verifies JDK 17, Maven, Python and Docker, and that the Compose host ports this
checkout would publish are free. Every failure names the fix. Nothing here
contacts a network service or a payment rail; it only inspects the local
toolchain.

    python3 scripts/preflight.py                   # human-readable report
    python3 scripts/preflight.py --json out.json   # also record the result
    python3 scripts/preflight.py --mode app        # for the containerised stack

`--mode host` (the default) is for running the application yourself against the
Compose backing services, so it checks their published host ports. `--mode app`
is for `compose.app.yml`, where only the application publishes a port.

Exit status is 0 when every required check passes, 1 otherwise.
"""
import argparse
import json
import os
import platform
import re
import socket
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

REQUIRED_JAVA_MAJOR = 17
MINIMUM_MAVEN = (3, 9)
MINIMUM_PYTHON = (3, 9)
# compose.app.yml resets the backing services' ports with the `!reset` tag,
# which Compose understands from v2.24.
MINIMUM_COMPOSE = (2, 24)

# Host ports this checkout would publish, with the variable that moves each one.
# Keep in step with docker-compose.yml.
COMPOSE_PORTS = (("postgres", "POSTGRES_PORT", 5432),
                 ("redis", "REDIS_PORT", 6379),
                 ("rabbitmq", "RABBITMQ_PORT", 5672),
                 ("rabbitmq-management", "RABBITMQ_MANAGEMENT_PORT", 15672))

# In app mode the gateway reaches the backing services over the Compose network
# and only its own port is published.
APP_PORTS = (("app", "APP_PORT", 8080),)

COMPOSE_IMAGES = ("postgres:16-alpine", "redis:7-alpine", "rabbitmq:3-management-alpine")


class Check:
    """One preflight result. `required=False` reports without failing the run."""

    def __init__(self, name, ok, detail, remedy=None, required=True):
        self.name = name
        self.ok = ok
        self.detail = detail
        self.remedy = remedy
        self.required = required

    @property
    def failed(self):
        return self.required and not self.ok

    def as_dict(self):
        return {"name": self.name, "ok": self.ok, "detail": self.detail,
                "remedy": self.remedy, "required": self.required}


def run_tool(command):
    """Run a version command, returning (ok, combined output). Never raises."""
    try:
        finished = subprocess.run(command, cwd=ROOT, text=True, timeout=60,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    except FileNotFoundError:
        return False, ""
    except (OSError, subprocess.SubprocessError) as error:
        return False, str(error)
    return finished.returncode == 0, finished.stdout or ""


def parse_version(output):
    """First dotted version in a tool's banner, as a tuple of integers.

    Handles `openjdk version "17.0.20.1"`, `Apache Maven 3.9.16`, and
    `Docker version 29.7.2, build ...`.
    """
    match = re.search(r"(\d+)(?:\.(\d+))?(?:\.(\d+))?", output or "")
    if not match:
        return None
    return tuple(int(part) for part in match.groups() if part is not None)


def java_command():
    """Path to the java binary, preferring JAVA_HOME over whatever is on PATH."""
    home = os.environ.get("JAVA_HOME")
    if home:
        candidate = Path(home) / "bin" / "java"
        if candidate.exists():
            return str(candidate)
        return None
    return "java"


def check_java():
    command = java_command()
    if command is None:
        return Check("JDK 17", False, f"JAVA_HOME={os.environ['JAVA_HOME']} has no bin/java",
                     "Point JAVA_HOME at a JDK 17 installation, or unset it to use PATH.")
    ok, output = run_tool([command, "-version"])
    if not ok:
        return Check("JDK 17", False, "no Java runtime found",
                     "Install JDK 17 and set JAVA_HOME. On macOS with Homebrew: "
                     "brew install openjdk@17 && export JAVA_HOME="
                     "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home")
    version = parse_version(output)
    if not version or version[0] != REQUIRED_JAVA_MAJOR:
        found = ".".join(str(part) for part in version) if version else "unknown"
        return Check("JDK 17", False, f"found Java {found}",
                     f"This checkout builds with Java {REQUIRED_JAVA_MAJOR} "
                     f"(pom.xml java.version). Set JAVA_HOME to a JDK "
                     f"{REQUIRED_JAVA_MAJOR} installation and re-run.")
    return Check("JDK 17", True, "Java " + ".".join(str(part) for part in version))


def check_maven():
    ok, output = run_tool(["mvn", "-version"])
    if not ok:
        return Check("Maven", False, "mvn not found on PATH",
                     "Install Maven 3.9.x (brew install maven, or apt install maven).")
    version = parse_version(output)
    if not version or version[:2] < MINIMUM_MAVEN:
        found = ".".join(str(part) for part in version) if version else "unknown"
        return Check("Maven", False, f"found Maven {found}",
                     "Install Maven %d.%d or newer." % MINIMUM_MAVEN)
    return Check("Maven", True, "Maven " + ".".join(str(part) for part in version))


def check_python():
    version = sys.version_info[:3]
    if version[:2] < MINIMUM_PYTHON:
        return Check("Python", False,
                     "running Python %d.%d.%d" % version,
                     "The harness needs Python %d.%d or newer." % MINIMUM_PYTHON)
    return Check("Python", True, "Python %d.%d.%d" % version)


def check_docker():
    ok, output = run_tool(["docker", "version", "--format", "{{.Server.Version}}"])
    if not ok:
        return Check("Docker", False, "Docker daemon is not responding",
                     "Install Docker Engine (or Docker Desktop) and start it, "
                     "then confirm with: docker info")
    return Check("Docker", True, "Docker engine " + (output.strip() or "available"))


def check_compose():
    ok, output = run_tool(["docker", "compose", "version"])
    if not ok:
        return Check("Docker Compose", False, "docker compose is unavailable",
                     "Install the Compose v2 plugin; this checkout uses "
                     "`docker compose`, not the legacy docker-compose binary.")
    version = parse_version(output)
    if not version or version[:2] < MINIMUM_COMPOSE:
        found = ".".join(str(part) for part in version) if version else "unknown"
        return Check("Docker Compose", False, f"found Compose {found}",
                     "compose.app.yml needs Compose %d.%d or newer for the `!reset` "
                     "tag that drops the backing services' host ports. Upgrade the "
                     "Compose plugin." % MINIMUM_COMPOSE)
    return Check("Docker Compose", True, "Compose " + ".".join(str(p) for p in version))


def port_is_free(port, host="127.0.0.1"):
    """True when a listener can bind this port right now."""
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind((host, port))
        except OSError:
            return False
    return True


def resolve_port(variable, default):
    """Compose port for a service, honouring the environment override."""
    raw = os.environ.get(variable, "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        return None
    return value if 1 <= value <= 65535 else None


def check_ports(mode="host"):
    results = []
    for service, variable, default in (APP_PORTS if mode == "app" else COMPOSE_PORTS):
        port = resolve_port(variable, default)
        if port is None:
            results.append(Check(f"port {service}", False,
                                 f"{variable}={os.environ.get(variable)!r} is not a valid port",
                                 f"Set {variable} to a number between 1 and 65535."))
            continue
        if port_is_free(port):
            results.append(Check(f"port {service}", True, f"{port} is free"))
        else:
            remedy = (f"Stop the process holding {port}, or run with "
                      f"{variable}=<free port>.")
            if service == "postgres":
                remedy += " Point DB_URL at the same port when you start the app."
            results.append(Check(f"port {service}", False, f"{port} is already in use", remedy))
    return results


def check_image_cache():
    """Report whether the Compose images are already pulled.

    This is not a pass/fail condition. It records whether a run is cold or
    cached, so image-download time is not silently mixed into setup time.
    """
    present = []
    for image in COMPOSE_IMAGES:
        ok, _ = run_tool(["docker", "image", "inspect", image])
        if ok:
            present.append(image)
    missing = [image for image in COMPOSE_IMAGES if image not in present]
    maven_repo = Path.home() / ".m2" / "repository"
    state = "cached" if not missing and maven_repo.exists() else "cold"
    detail = (f"{len(present)}/{len(COMPOSE_IMAGES)} Compose images pulled, "
              f"Maven repository {'present' if maven_repo.exists() else 'absent'}")
    return Check("cache state (" + state + ")", True, detail, required=False)


def collect(mode="host"):
    checks = [check_java(), check_maven(), check_python(), check_docker()]
    if mode == "app":
        # compose.app.yml builds the jar inside the image, so a local JDK and
        # Maven are useful but not required. They are still reported, because a
        # missing JDK matters the moment you switch to running the application
        # yourself or run the evaluation.
        for check in checks[:2]:
            check.required = False
    if checks[-1].ok:
        checks.append(check_compose())
    else:
        checks.append(Check("Docker Compose", False, "not checked: Docker is unavailable",
                            "Start Docker, then re-run this preflight."))
    checks.extend(check_ports(mode))
    if checks[3].ok:
        checks.append(check_image_cache())
    return checks


def report(checks, stream=sys.stdout, mode="host"):
    width = max(len(check.name) for check in checks)
    for check in checks:
        if check.ok:
            mark = "ok  "
        elif check.required:
            mark = "FAIL"
        else:
            mark = "note"
        print(f"{mark}  {check.name.ljust(width)}  {check.detail}", file=stream)
        if check.remedy and not check.ok:
            print(f"      {' ' * width}  -> {check.remedy}", file=stream)
    failures = [check for check in checks if check.failed]
    print("", file=stream)
    if failures:
        print(f"{len(failures)} check(s) failed; fix the items above before running "
              f"the evaluation.", file=stream)
    elif mode == "app":
        print("Preflight passed. Next:", file=stream)
        print("  docker compose -p openfednow-app -f docker-compose.yml -f compose.app.yml "
              "up -d --wait", file=stream)
        print("  curl http://127.0.0.1:${APP_PORT:-8080}/fednow/health", file=stream)
    else:
        print("Preflight passed. Next:", file=stream)
        print("  docker compose -p openfednow-eval up -d --wait postgres redis rabbitmq",
              file=stream)
        print("  python3 scripts/evaluate.py --integration --negative-control --external",
              file=stream)
    return 1 if failures else 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--json", metavar="PATH",
                        help="Write the result, including cold/cached cache state, to a JSON file")
    parser.add_argument("--mode", choices=("host", "app"), default="host",
                        help="host (default): you run the application and the Compose "
                             "backing services publish host ports. app: compose.app.yml "
                             "runs the application too and only it publishes a port.")
    args = parser.parse_args(argv)
    checks = collect(args.mode)
    status = report(checks, mode=args.mode)
    if args.json:
        destination = Path(args.json)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps({
            "recorded_at": datetime.now(timezone.utc).isoformat(),
            "platform": platform.platform(),
            "mode": args.mode,
            "passed": status == 0,
            "checks": [check.as_dict() for check in checks]}, indent=2) + "\n")
    return status


if __name__ == "__main__":
    raise SystemExit(main())
