# Fiserv Banking Hub integration discovery

Reviewed 2026-11-05 against public Fiserv Developer Studio pages. This is a discovery and local contract deliverable, not a provider-tested integration.

## Identified public environment

- Product: Banking Hub, Premier API family, `Premier-Immediate` core option on the public **Add Transfer** reference page. Other core options and product compatibility were not evaluated.
- The [getting-started guide](https://developer.fiserv.com/product/BankingHub/docs/get-started.md) describes a trial workspace for a **shared Premier Sandbox Environment**. It separately describes a paid Banking Hub Connection plan for a dedicated test environment and additional services. No workspace, entitlement, test account, or paid plan is present for this project.
- The guide describes token acquisition at `POST https://bankinghub-cert.fiservapis.com/fts-apim/oauth2/v2`, using the workspace API key and secret as **Basic** credentials and a form body of `grant_type=client_credentials`. It says a workspace provides an organization ID and host URL. We have not authenticated or verified required transaction headers, scopes, signing, or tenant setup.
- The public [Add Transfer reference](https://developer.fiserv.com/product/BankingHub/api/post/xferservice/payments/transfers?branch=main&version=Premier&core=Premier-Immediate) documents `POST /xferservice/payments/transfers`, a required `EFXHeader`, `XferInfo`, and a transfer amount. It describes immediate memo posting, nightly batch entries, and a batch update if memo posting fails. This is **not** evidence of a durable available-funds reservation, posting that consumes a hold, or rail-settlement coordination. A correlation field is not an idempotency guarantee.
- The legacy `FiservAdapter`/`fiserv/` code uses `/banking/v1/*` paths, form-body client credentials, and fixed-point cent strings. Those are local fixture shapes. They have not been mapped to this Banking Hub contract and must not be aimed at a Fiserv host.

## Access and safety boundary

The user has confirmed no Banking Hub developer access yet. Do not enroll, accept terms, buy a plan, configure provider credentials, or invoke money-changing APIs as part of the local checks. The existing `/fednow/send` walkthrough uses a synthetic SQL core and separate rail simulator. It does not use Banking Hub. The new outbound core contract defaults to unsupported and cannot authorize a send.

A future provider test needs a named workspace and environment, official versioned schemas for the chosen operations, approved synthetic account IDs, permitted test behavior, and credentials stored outside Git. Confirm any commercial terms directly before selecting a plan. Capture redacted provider observations and a separate lookup before claiming a financial effect.
