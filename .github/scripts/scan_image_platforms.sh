#!/usr/bin/env bash
# Scan every platform of a multi-platform OCI image layout with Trivy.
#
# For each platform this splits the layout down to that one image (see
# oci_platform_layout.py for why that is needed), scans it, and then checks
# that the image Trivy reports having scanned is the one expected. A scan of
# the wrong image fails instead of passing. HIGH and CRITICAL findings fail the
# run, but only after every platform has been scanned and reported. The
# repository's .trivyignore applies.
#
# Writes to OUTDIR, per platform (for example "amd64", "arm64"):
#   trivy-<platform>.json     the full scan result
#   trivy-<platform>.sarif    the same, for GitHub code scanning
#   sbom-<platform>.cdx.json  a CycloneDX SBOM of that image
#
# Run from the repository root; LAYOUT and OUTDIR are paths relative to it.
#
#   scan_image_platforms.sh LAYOUT OUTDIR PLATFORM [PLATFORM...]
#   scan_image_platforms.sh target/image target/scan linux/amd64 linux/arm64
set -euo pipefail

usage="usage: scan_image_platforms.sh LAYOUT OUTDIR PLATFORM [PLATFORM...]"
layout="${1:?$usage}"
outdir="${2:?$usage}"
shift 2
[ "$#" -gt 0 ] || { echo "$usage" >&2; exit 2; }
for path in "$layout" "$outdir"; do
    case "$path" in
        /*|../*) echo "$path must be relative to the repository root" >&2; exit 2 ;;
    esac
done

# Pinned so the scan is reproducible. Keep the version in step with the
# filesystem scan in .github/workflows/trivy-scan.yml.
trivy_image="aquasec/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969"
# Trivy's exit status when it finds something. Distinct from 1, which Trivy
# also uses for its own errors, so "found vulnerabilities" and "could not scan"
# are never confused.
findings_status=3

here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$outdir/trivy-cache"

trivy() {
    docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp \
        -v "$PWD:/work" -w /work \
        "$trivy_image" --cache-dir "$outdir/trivy-cache" "$@"
}

failed=()
for platform in "$@"; do
    name="${platform#linux/}"
    name="${name//\//-}"
    split="$outdir/layout-$name"
    expected="$(python3 "$here/oci_platform_layout.py" "$layout" "$platform" "$split")"

    status=0
    trivy image --quiet --input "$split" --scanners vuln --severity HIGH,CRITICAL \
        --exit-code "$findings_status" --format json --output "$outdir/trivy-$name.json" \
        || status=$?
    if [ "$status" -ne 0 ] && [ "$status" -ne "$findings_status" ]; then
        echo "$platform: Trivy could not complete the scan (exit $status)" >&2
        exit "$status"
    fi

    scanned="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["Metadata"]["ImageID"])' \
        "$outdir/trivy-$name.json")"
    if [ "$scanned" != "$expected" ]; then
        echo "$platform: Trivy scanned image $scanned but the layout's $platform image is $expected" >&2
        exit 1
    fi

    trivy convert --format sarif --output "$outdir/trivy-$name.sarif" "$outdir/trivy-$name.json"
    trivy image --quiet --input "$split" --format cyclonedx --output "$outdir/sbom-$name.cdx.json"

    if [ "$status" -eq "$findings_status" ]; then
        failed+=("$platform")
        echo "$platform: HIGH or CRITICAL findings in image $expected"
        trivy convert --format table "$outdir/trivy-$name.json"
    else
        echo "$platform: no HIGH or CRITICAL findings in image $expected"
    fi
    # The split layout is hard links into LAYOUT; the bytes stay there.
    rm -rf "$split"
done

if [ "${#failed[@]}" -gt 0 ]; then
    echo "HIGH or CRITICAL findings on: ${failed[*]}" >&2
    exit 1
fi
