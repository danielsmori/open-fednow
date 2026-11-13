"""Tests for oci_platform_layout.py, on a synthetic layout shaped like buildx output."""
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import oci_platform_layout as layout

INDEX = "application/vnd.oci.image.index.v1+json"
MANIFEST = "application/vnd.oci.image.manifest.v1+json"


def put(root, payload):
    """Store a JSON blob in the layout and return its digest."""
    data = json.dumps(payload).encode()
    digest = "sha256:" + hashlib.sha256(data).hexdigest()
    path = root / "blobs" / "sha256" / digest.split(":")[1]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return digest, len(data)


def image(root, arch):
    config, size = put(root, {"architecture": arch, "os": "linux"})
    return put(root, {"schemaVersion": 2, "mediaType": MANIFEST,
                      "config": {"digest": config, "size": size}, "layers": []}), config


def build_layout(root):
    """A multi-platform index nested under index.json, with attestations, as buildx writes it."""
    (root / "oci-layout").write_text('{"imageLayoutVersion": "1.0.0"}')
    (amd64, amd64_size), amd64_config = image(root, "amd64")
    (arm64, arm64_size), arm64_config = image(root, "arm64")
    (attestation, attestation_size), _ = image(root, "unknown")
    nested, nested_size = put(root, {"schemaVersion": 2, "mediaType": INDEX, "manifests": [
        {"mediaType": MANIFEST, "digest": amd64, "size": amd64_size,
         "platform": {"os": "linux", "architecture": "amd64"}},
        {"mediaType": MANIFEST, "digest": arm64, "size": arm64_size,
         "platform": {"os": "linux", "architecture": "arm64"}},
        {"mediaType": MANIFEST, "digest": attestation, "size": attestation_size,
         "platform": {"os": "unknown", "architecture": "unknown"},
         "annotations": {"vnd.docker.reference.type": "attestation-manifest",
                         "vnd.docker.reference.digest": amd64}}]})
    (root / "index.json").write_text(json.dumps({"schemaVersion": 2, "manifests": [
        {"mediaType": INDEX, "digest": nested, "size": nested_size}]}))
    return {"linux/amd64": (amd64, amd64_config), "linux/arm64": (arm64, arm64_config)}


class SplitTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.source = self.root / "image"
        self.source.mkdir()
        self.expected = build_layout(self.source)

    def tearDown(self):
        self.directory.cleanup()

    def test_lists_only_real_platforms(self):
        found = dict(layout.platform_manifests(self.source))
        self.assertEqual(sorted(found), ["linux/amd64", "linux/arm64"])

    def test_each_split_names_exactly_its_own_image(self):
        for platform, (manifest, config) in self.expected.items():
            dest = self.root / platform.replace("/", "-")
            self.assertEqual(layout.split(self.source, platform, dest), config)
            index = json.loads((dest / "index.json").read_text())
            self.assertEqual([m["digest"] for m in index["manifests"]], [manifest])
            self.assertTrue((dest / "oci-layout").is_file())
            # The split must hold the same bytes, not a rebuilt copy.
            blob = dest / "blobs" / "sha256" / manifest.split(":")[1]
            self.assertEqual(blob.read_bytes(),
                             (self.source / "blobs" / "sha256" / manifest.split(":")[1]).read_bytes())

    def test_unknown_platform_fails_and_names_what_exists(self):
        with self.assertRaises(SystemExit) as raised:
            layout.split(self.source, "linux/s390x", self.root / "bad")
        self.assertIn("linux/amd64", str(raised.exception))

    def test_missing_layout_fails_clearly(self):
        with self.assertRaises(SystemExit) as raised:
            layout.split(self.root / "absent", "linux/amd64", self.root / "out")
        self.assertIn("not an OCI image layout", str(raised.exception))

    def test_existing_destination_is_replaced(self):
        dest = self.root / "out"
        layout.split(self.source, "linux/amd64", dest)
        manifest, config = self.expected["linux/arm64"]
        self.assertEqual(layout.split(self.source, "linux/arm64", dest), config)
        index = json.loads((dest / "index.json").read_text())
        self.assertEqual([m["digest"] for m in index["manifests"]], [manifest])


if __name__ == "__main__":
    unittest.main()
