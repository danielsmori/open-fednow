# Releasing

A release is a tagged, tested version of the repository with its evidence attached. Each release lets another team refer to one exact version of the tests and the reference code.

## Version numbers

Versions follow [Semantic Versioning](https://semver.org/). Until 1.0.0, a minor version may change the driver contract or a scenario's expected results; such changes are called out in [CHANGELOG.md](../CHANGELOG.md). The test profile carries its own version in [`harness/profiles/`](../harness/profiles/).

## Steps

1. Confirm `main` is green in CI.
2. Rerun the scenarios that the release covers and publish the commands, source version and raw results under [`docs/reliability/evidence/`](reliability/evidence/).
3. Move the entries under **Unreleased** in `CHANGELOG.md` to a new heading with the version and date.
4. Set the version in `pom.xml` (remove `-SNAPSHOT`), commit, and merge to `main`.
5. Tag the merge commit and push the tag:

   ```sh
   git tag -a v0.1.0 -m "OpenFedNow 0.1.0"
   git push origin v0.1.0
   ```

6. Before tagging, run the [release workflow](../.github/workflows/release.yml) manually from the Actions tab. Without a tag it is a dry run: it builds the container image for `linux/amd64` and `linux/arm64`, scans both, writes the SBOMs and uploads everything as an artefact, but pushes nothing and creates no release. A tag is awkward to take back, so check the dry run first.
7. The release workflow then runs on the tag. It runs the unit and harness tests, builds the service jar and the test-kit zip, builds the image for both platforms, scans each one, pushes the image to `ghcr.io/danielsmori/open-fednow:<version>` and creates a GitHub Release with SHA-256 checksums. The image tag matches `image.tag` in [helm/values.yaml](../helm/values.yaml) and the chart's `appVersion`.
8. **After the first publish only:** a new GHCR package is private even when the repository is public. Open the package settings and set its visibility to public, otherwise anyone deploying the chart needs `imagePullSecrets`. This is a one-time step for the package, not per release.
9. Set the next `-SNAPSHOT` version in `pom.xml`.

### If the image scan fails

The scan fails the release before anything is pushed, which is the intent: a vulnerable image never reaches the registry. Fix the dependency, or add a scoped exception to [`.trivyignore`](../.trivyignore) with the CVE, the reason it does not apply to this application, and the advisory link. Do not widen the severity filter to get a release out.

## What a release contains

- `openfednow-<version>.jar`: the Java reference service.
- `openfednow-test-kit-<version>.zip`: the `harness/` directory (which includes the driver contract), the profile guide, `LICENSE` and `NOTICE`. From the extracted folder, `python3 harness/smoke_profile.py --all` runs the four portable tests with Python 3.9 or later and no other packages. The zip does not contain `scripts/evaluate.py` or the Java service; the full evaluation needs a repository checkout.
- `smoke-report-<version>.json`: the report from the four portable tests, as run by the release workflow.
- `openfednow-<version>-sbom-amd64.cdx.json` and `openfednow-<version>-sbom-arm64.cdx.json`: CycloneDX software bills of materials for each image platform.
- `openfednow-<version>-image-digest.txt`: the published image reference and its digest. Pin the digest rather than the tag when a deployment needs to be reproducible.
- `SHA256SUMS.txt`: checksums for the files above.

The container image is published separately to `ghcr.io/danielsmori/open-fednow:<version>` for `linux/amd64` and `linux/arm64`, with an SBOM and a provenance attestation attached in the registry. Pull it with `docker pull ghcr.io/danielsmori/open-fednow:<version>`, and verify the attestations with `docker buildx imagetools inspect`.

## What a release does not mean

A release is not a certification and does not make the software ready for production. See [reliability scope](reliability/scope.md).
