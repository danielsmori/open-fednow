#!/usr/bin/env bash
# Wait for a container's HEALTHCHECK to report healthy.
#
# Fails with the container's recent logs if it exits early or is still not
# healthy at the deadline, so a stuck start is never mistaken for a slow one.
#
#   wait-for-health.sh <container> [timeout-seconds]
set -euo pipefail

container="${1:?usage: wait-for-health.sh <container> [timeout-seconds]}"
timeout="${2:-240}"
deadline=$(( $(date +%s) + timeout ))

while :; do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container")"
    if [ "$status" = healthy ]; then
        echo "$container is healthy"
        exit 0
    fi
    if [ "$status" = none ]; then
        echo "$container has no healthcheck to wait on" >&2
        exit 1
    fi
    if [ "$(docker inspect -f '{{.State.Running}}' "$container")" != true ]; then
        echo "$container exited before it became healthy" >&2
        docker logs --tail 80 "$container" >&2
        exit 1
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
        echo "$container was still '$status' after ${timeout}s" >&2
        docker logs --tail 80 "$container" >&2
        exit 1
    fi
    sleep 3
done
