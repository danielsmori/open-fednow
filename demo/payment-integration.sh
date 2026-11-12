#!/usr/bin/env bash
# One-command local, disposable Payment Integration demo. No live rail/core.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
command -v mvn >/dev/null || { echo 'Maven is required.' >&2; exit 1; }
command -v docker >/dev/null || { echo 'Docker Compose is required.' >&2; exit 1; }
command -v python3 >/dev/null || { echo 'Python 3 is required.' >&2; exit 1; }
java_cmd="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$java_cmd" -version 2>&1 | head -1
docker info >/dev/null || { echo 'Start the Docker daemon first.' >&2; exit 1; }
python3 - <<'PY'
import socket
for port in (18080, 18099, 55432, 56379, 55672):
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind(("127.0.0.1", port))
        except OSError as error:
            raise SystemExit(f"Port {port} is unavailable: {error}")
PY

project=openfednow-payment-demo
compose=(docker compose -p "$project" -f demo/payment-integration.compose.yml)
rail_pid=''
app_pid=''
cleanup() {
  [[ -z "$app_pid" ]] || kill "$app_pid" 2>/dev/null || true
  [[ -z "$rail_pid" ]] || kill "$rail_pid" 2>/dev/null || true
  [[ -z "$app_pid" ]] || wait "$app_pid" 2>/dev/null || true
  [[ -z "$rail_pid" ]] || wait "$rail_pid" 2>/dev/null || true
  "${compose[@]}" down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

"${compose[@]}" up -d --wait
mvn -B --no-transfer-progress -DskipTests package
# The jar name follows the project version in pom.xml, so a release or a new
# snapshot version needs no change here.
project_version="$(python3 -c "import xml.etree.ElementTree as E; print(E.parse('pom.xml').getroot().findtext('{http://maven.apache.org/POM/4.0.0}version'))")"
app_jar="target/openfednow-${project_version}.jar"
[[ -f "$app_jar" ]] || { echo "Built jar not found: $app_jar" >&2; exit 1; }
mkdir -p target/payment-integration
rm -f target/payment-integration/rail.sqlite{,-wal,-shm}
python3 harness/rail_simulator.py --port 18099 --db target/payment-integration/rail.sqlite \
  >target/payment-integration/rail.log 2>&1 &
rail_pid=$!
for _ in {1..50}; do
  if curl -fsS http://127.0.0.1:18099/health >/dev/null 2>&1; then break; fi
  kill -0 "$rail_pid" 2>/dev/null || { echo 'Rail simulator exited; see target/payment-integration/rail.log' >&2; exit 1; }
  sleep 0.2
done
curl -fsS http://127.0.0.1:18099/health >/dev/null

SERVER_PORT=18080 SERVER_ADDRESS=127.0.0.1 \
DB_URL=jdbc:postgresql://127.0.0.1:55432/openfednow \
DB_USERNAME=openfednow DB_PASSWORD=openfednow \
REDIS_HOST=127.0.0.1 REDIS_PORT=56379 \
RABBITMQ_HOST=127.0.0.1 RABBITMQ_PORT=55672 \
RELIABILITY_LEGACY_ENTRY_BRIDGE_ENABLED=true \
RELIABILITY_FIXTURE_API_ENABLED=true \
RELIABILITY_SYNTHETIC_RAIL_URL=http://127.0.0.1:18099 \
  "$java_cmd" -jar "$app_jar" \
  >target/payment-integration/java.log 2>&1 &
app_pid=$!
for _ in {1..150}; do
  if curl -fsS http://127.0.0.1:18080/fednow/health >/dev/null 2>&1; then break; fi
  kill -0 "$app_pid" 2>/dev/null || { echo 'Java service exited; see target/payment-integration/java.log' >&2; exit 1; }
  sleep 0.2
done
curl -fsS http://127.0.0.1:18080/fednow/health >/dev/null
echo 'Payment Integration console: http://127.0.0.1:18080/demo/console.html'
echo 'Synthetic core and rail only. Press Ctrl+C to stop and remove disposable containers.'
wait "$app_pid"
