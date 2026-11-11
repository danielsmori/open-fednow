# Security policy

OpenFedNow is a sandbox and reference implementation. It uses synthetic accounts and a simulated payment service, and it is not intended to run in production. Security reports are still welcome, because other teams may read or adapt this code.

## Reporting a vulnerability

Please do not open a public issue for a security problem.

1. Use GitHub's private reporting: open the repository's **Security** tab and choose **Report a vulnerability**.
2. If private reporting is unavailable, open an issue titled "Security contact request" with no technical details. The maintainer will arrange a private channel.

Include the affected file or endpoint, the version or commit, the steps to reproduce, and the effect you observed.

## What to expect

The maintainer will acknowledge a report within seven days and will tell you whether it is accepted, needs more information, or is out of scope. Accepted reports are fixed on `main` with a regression test where practical, and the fix is recorded in [CHANGELOG.md](CHANGELOG.md). You will be credited unless you ask not to be.

## Scope

In scope: the Java service, the Python harness, the demo console, the Helm chart and the CI configuration in this repository.

Out of scope: the documented sandbox defaults, such as the `admin` / `changeme` fixture credentials, which the `prod` profile refuses to start with. Findings about third-party services or vendor environments belong with their owners.

## Never include real data

Do not send customer data, institution credentials, vendor credentials or restricted specifications in a report, an issue or a pull request. Use synthetic examples.
