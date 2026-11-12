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

6. The [release workflow](../.github/workflows/release.yml) runs the unit tests and the harness tests, builds the service jar and a zip of the test kit, and creates a GitHub Release with SHA-256 checksums.
7. Set the next `-SNAPSHOT` version in `pom.xml`.

## What a release contains

- `openfednow-<version>.jar`: the Java reference service.
- `openfednow-test-kit-<version>.zip`: the `harness/` directory (which includes the driver contract), the profile guide, `LICENSE` and `NOTICE`. From the extracted folder, `python3 harness/smoke_profile.py --all` runs the four portable tests with Python 3.9 or later and no other packages. The zip does not contain `scripts/evaluate.py` or the Java service; the full evaluation needs a repository checkout.
- `smoke-report-<version>.json`: the report from the four portable tests, as run by the release workflow.
- `SHA256SUMS.txt`: checksums for the files above.

## What a release does not mean

A release is not a certification and does not make the software ready for production. See [reliability scope](reliability/scope.md).
