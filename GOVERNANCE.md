# Governance

OpenFedNow is a maintainer-led open-source project.

## Roles

- **Maintainer.** Daniel Stelzer Mori ([@danielsmori](https://github.com/danielsmori)) sets the technical direction, reviews and merges changes, and publishes releases and their evidence.
- **Contributors.** Anyone may open an issue or a pull request. Each merged contribution is credited to its author in the commit history and the changelog.
- **Evaluators.** Anyone may run the tests and report the result, including failures. Reports are recorded with the reporter's permission.

## How decisions are made

Changes are proposed in issues and pull requests. The maintainer decides after public discussion. Design decisions with lasting effect are recorded as architecture decision records in [`docs/adr/`](docs/adr/).

Work is prioritized by its potential effect on payment outcomes, by how likely it is to be reused, and by the cost of a reproducible fix.

## Rules for changes that affect payment outcomes

Before such a change is merged, the relevant scenarios are rerun and the payment state, the submissions to the simulator and the account effects are checked. Before it is released, the evaluated source version, the commands and the raw results are published under [`docs/reliability/evidence/`](docs/reliability/evidence/).

## What stays out of the repository

Customer data, institution credentials, vendor credentials and restricted specifications are never accepted. Findings from a private evaluation are published only as synthetic tests or interface clarifications, and only with permission.

## Changes to governance

If regular outside contributors become active, the maintainer will propose additional maintainers and, if appropriate, a move to a neutral home. Changes to this document are made by pull request.
