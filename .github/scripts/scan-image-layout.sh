#!/usr/bin/env bash
# Scan one single-platform OCI image layout, write its SBOM, and fail on any
# HIGH or CRITICAL finding not covered by .trivyignore.
#
#   scan-image-layout.sh <layout-dir> <arch> <sbom-out> [sarif-out]
#
# The layout must hold exactly one platform. Trivy ignores --platform when it
# reads a multi-platform OCI layout and scans whichever image it finds first,
# so a multi-platform layout silently scans one architecture twice and labels
# the copy as the other. The SBOM is therefore checked before the scan result
# is trusted: its OS packages must all be built for <arch>.
#
# Run from the repository root so .trivyignore applies.
set -euo pipefail

layout="${1:?usage: scan-image-layout.sh <layout-dir> <arch> <sbom-out> [sarif-out]}"
arch="${2:?usage: scan-image-layout.sh <layout-dir> <arch> <sbom-out> [sarif-out]}"
sbom="${3:?usage: scan-image-layout.sh <layout-dir> <arch> <sbom-out> [sarif-out]}"
sarif="${4:-}"

trivy=(docker run --rm -v "$PWD:/work" -w /work
       "aquasec/trivy:${TRIVY_VERSION:-0.74.0}" image --input "$layout")

"${trivy[@]}" --format cyclonedx --quiet --output "$sbom"

python3 - "$sbom" "$arch" <<'PY'
import json
import sys

path, wanted = sys.argv[1], sys.argv[2]
with open(path) as handle:
    bom = json.load(handle)
found = {
    component["purl"].split("arch=", 1)[1].split("&", 1)[0]
    for component in bom.get("components", [])
    if component.get("purl", "").startswith("pkg:deb/") and "arch=" in component["purl"]
}
found.discard("all")  # architecture-independent packages say nothing either way
if found != {wanted}:
    sys.exit(f"{path}: OS packages are built for {sorted(found) or 'nothing'}, "
             f"expected only {wanted}. This scan did not look at the {wanted} image.")
print(f"{path}: OS packages are {wanted}, as expected")
PY

# Written before the gating scan, so a failing scan still leaves a report.
if [ -n "$sarif" ]; then
    "${trivy[@]}" --scanners vuln --severity HIGH,CRITICAL --quiet \
        --format sarif --output "$sarif"
fi

"${trivy[@]}" --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --format table
