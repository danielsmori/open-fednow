#!/usr/bin/env python3
"""Split one platform out of a multi-platform OCI image layout, for scanning.

Trivy reads an OCI image layout directory, but for that input it ignores
--platform and scans the first image manifest it finds. Pointed at a
multi-platform layout, a "linux/arm64" scan silently re-scans linux/amd64.

This writes a new layout whose index.json names exactly one platform's image
manifest. Blobs are hard-linked from the source (copied if that is not
possible), so the split layout holds the very bytes that are later pushed: the
scan covers the published image, not a rebuild of it.

It prints the image's config digest. Trivy reports that digest as
Metadata.ImageID, and the release workflow compares the two, so a scan of the
wrong image fails instead of passing.

    oci_platform_layout.py SOURCE_LAYOUT PLATFORM DEST_LAYOUT
    oci_platform_layout.py target/image linux/arm64 target/image-arm64
"""
import json
import os
import shutil
import sys
from pathlib import Path


def load(path):
    with open(path) as handle:
        return json.load(handle)


def blob(layout, digest):
    algorithm, value = digest.split(":", 1)
    return layout / "blobs" / algorithm / value


def platform_manifests(layout):
    """Yield (platform, descriptor) for every image manifest in the layout.

    Follows nested image indexes, which is how buildx writes a multi-platform
    image, and skips attestation manifests (platform unknown/unknown).
    """
    pending = list(load(layout / "index.json")["manifests"])
    while pending:
        descriptor = pending.pop(0)
        media_type = descriptor.get("mediaType", "")
        if media_type.endswith("image.index.v1+json") or media_type.endswith("manifest.list.v2+json"):
            pending.extend(load(blob(layout, descriptor["digest"]))["manifests"])
            continue
        platform = descriptor.get("platform") or {}
        if platform.get("os") in (None, "unknown"):
            continue
        name = f"{platform['os']}/{platform['architecture']}"
        if platform.get("variant"):
            name += "/" + platform["variant"]
        yield name, descriptor


def link_blobs(source, dest):
    for path in (source / "blobs").rglob("*"):
        if path.is_dir():
            continue
        target = dest / path.relative_to(source)
        target.parent.mkdir(parents=True, exist_ok=True)
        try:
            os.link(path, target)
        except OSError:
            shutil.copy2(path, target)


def split(source, platform, dest):
    if not (source / "index.json").is_file():
        raise SystemExit(f"{source} is not an OCI image layout: it has no index.json")
    found = dict(platform_manifests(source))
    if platform not in found:
        raise SystemExit(f"{platform} is not in {source}; it has {sorted(found)}")
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True)
    shutil.copy2(source / "oci-layout", dest / "oci-layout")
    descriptor = found[platform]
    (dest / "index.json").write_text(json.dumps(
        {"schemaVersion": 2, "mediaType": "application/vnd.oci.image.index.v1+json",
         "manifests": [descriptor]}, indent=2) + "\n")
    link_blobs(source, dest)
    return load(blob(source, descriptor["digest"]))["config"]["digest"]


def main(argv):
    if len(argv) != 4:
        raise SystemExit(__doc__)
    print(split(Path(argv[1]), argv[2], Path(argv[3])))


if __name__ == "__main__":
    main(sys.argv)
