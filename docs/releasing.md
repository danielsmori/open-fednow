# Releasing

A release is a tagged, tested version of the repository with its evidence attached. Each release lets another team refer to one exact version of the tests and the reference code.

## Version numbers

Versions follow [Semantic Versioning](https://semver.org/). Until 1.0.0, a minor version may change the driver contract or a scenario's expected results; such changes are called out in [CHANGELOG.md](../CHANGELOG.md). The test profile carries its own version in [`harness/profiles/`](../harness/profiles/).

## Steps

1. Confirm `main` is green in CI.
2. Rerun the scenarios that the release covers and publish the commands, source version and raw results under [`docs/reliability/evidence/`](reliability/evidence/).
3. Move the entries under **Unreleased** in `CHANGELOG.md` to a new heading with the version and date.
4. Set the version in `pom.xml` (remove `-SNAPSHOT`), and make `image.tag` in `helm/values.yaml` and `appVersion` in `helm/Chart.yaml` the same version. Commit and merge to `main`. The release workflow refuses a tag that disagrees with any of the three.
5. Tag the merge commit and push the tag:

   ```sh
   git tag -a v0.1.0 -m "OpenFedNow 0.1.0"
   git push origin v0.1.0
   ```

6. The [release workflow](../.github/workflows/release.yml) runs the unit tests and the harness tests, builds the service jar and a zip of the test kit, builds the container image for `linux/amd64` and `linux/arm64`, scans both, publishes the image to GHCR, and creates a GitHub Release with SHA-256 checksums. See [the container image](#the-container-image) below.
7. **First release only:** a new GHCR package is private, even for a public repository. Open the `open-fednow` package under the owner's GitHub Packages, and under *Package settings* set its visibility to **Public** and confirm it is linked to this repository. Until then, anyone following the Helm chart needs `imagePullSecrets` to pull it.
8. Set the next `-SNAPSHOT` version in `pom.xml`.

If the publish job fails after the build succeeded, re-run **only the failed job**. It pushes the same scanned image again, which is a no-op if part of it already landed. Re-running every job rebuilds the image with a new digest, and the publish job will refuse to put different bytes under a version that already exists.

## The container image

`ghcr.io/danielsmori/open-fednow:<version>` is one tag over two images, `linux/amd64` and `linux/arm64`, so the same tag runs natively on x86 servers and on Apple Silicon. The release workflow:

1. Compiles the jar once, on the runner's own platform. JVM bytecode is the same for both architectures, so only the small runtime stage is built per platform.
2. Writes both images to an OCI layout on disk, with an SBOM and build provenance attached as attestations. Nothing is pushed yet.
3. Scans each platform's image separately at HIGH and CRITICAL severity, applying `.trivyignore`, and checks that the image Trivy reports is the one expected for that platform. Trivy reads only the first image of a multi-platform layout unless it is split first, so without this check an arm64 scan could silently re-scan amd64. Any finding fails the release before anything is published.
4. In a separate job that holds the only write token, pushes that same layout to GHCR with its digests preserved, reads the published digest back, and fails unless it equals the digest that was scanned. A version that already exists with different bytes is never overwritten.

To check what you pulled against what was scanned:

```sh
docker buildx imagetools inspect ghcr.io/danielsmori/open-fednow:<version>
cat openfednow-<version>-container-image.txt   # from the release assets
```

The two digests must match. The weekly [vulnerability scan](../.github/workflows/trivy-scan.yml) rebuilds and rescans the image for both platforms, so a CVE published after a release still surfaces.

A pull request that changes the Dockerfile, the release workflow or `.trivyignore` runs the whole release build as a rehearsal — tests, both images, scans and SBOMs — and publishes nothing.

## What a release contains

- `openfednow-<version>.jar`: the Java reference service.
- `openfednow-test-kit-<version>.zip`: the `harness/` directory (which includes the driver contract), the profile guide, `LICENSE` and `NOTICE`. From the extracted folder, `python3 harness/smoke_profile.py --all` runs the four portable tests with Python 3.9 or later and no other packages. The zip does not contain `scripts/evaluate.py` or the Java service; the full evaluation needs a repository checkout.
- `smoke-report-<version>.json`: the report from the four portable tests, as run by the release workflow.
- `openfednow-<version>-container-image.txt`: the published image reference with its digest, `ghcr.io/danielsmori/open-fednow:<version>@sha256:…`.
- `openfednow-<version>-sbom-linux-amd64.cdx.json` and `…-linux-arm64.cdx.json`: CycloneDX SBOMs of each platform's image.
- `openfednow-<version>-image-scan-linux-amd64.json` and `…-linux-arm64.json`: the Trivy results that gated the release.
- `SHA256SUMS.txt`: checksums for the files above.

## What a release does not mean

A release is not a certification and does not make the software ready for production. See [reliability scope](reliability/scope.md).
