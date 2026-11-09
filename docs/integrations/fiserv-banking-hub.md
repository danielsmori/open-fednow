# Fiserv Banking Hub integration discovery

Reviewed 2026-11-05 against public Fiserv Developer Studio pages and a read-only Trial call from Java. This is not a provider-backed financial integration.

## Identified public environment

- Product: Banking Hub, Premier API family, with `Premier-Immediate` on **Add Transfer** and `Premier-DDA` on **Get Account Details**. Other core options and product compatibility were not evaluated.
- The [getting-started guide](https://developer.fiserv.com/product/BankingHub/docs/get-started.md) describes a trial workspace for a **shared Premier Sandbox Environment**. It separately describes a paid Banking Hub Connection plan for a dedicated test environment and additional services. Daniel created a Trial workspace on 2026-11-05. Its supplied host URL is `https://bankinghub-cert.fiservapis.com/banking/efx/v1`. A published synthetic sample account returned a read-only response; no paid plan or dedicated test core has been obtained for this project.
- The guide describes token acquisition at `POST https://bankinghub-cert.fiservapis.com/fts-apim/oauth2/v2`, using the workspace API key and secret as **Basic** credentials and a form body of `grant_type=client_credentials`. It says a workspace provides an organization ID and host URL. The opt-in [Trial preflight](../../scripts/fiserv_trial_preflight.py) executed that token request successfully on 2026-11-05. It did not verify required transaction headers, scopes, signing, tenant setup or any money-changing core operation.
- The public [Add Transfer reference](https://developer.fiserv.com/product/BankingHub/api/post/xferservice/payments/transfers?branch=main&version=Premier&core=Premier-Immediate) documents `POST /xferservice/payments/transfers`, a required `EFXHeader`, `XferInfo`, and a transfer amount. It describes immediate memo posting, nightly batch entries, and a batch update if memo posting fails. This is **not** evidence of a durable available-funds reservation, posting that consumes a hold, or rail-settlement coordination. A correlation field is not an idempotency guarantee.
- The public Premier [Add Account Hold](https://developer.fiserv.com/product/BankingHub/api/post/acctholdservice/stophold/accountHolds?branch=active&version=Premier&core=Premier) reference describes a hold restriction, amount, reason and expiry. [Get Account Hold](https://developer.fiserv.com/product/BankingHub/api/post/acctholdservice/stophold/accountHolds/secured?branch=active&version=Premier&core=Premier) can retrieve a list or a specific hold by provider hold identifier. Neither public description establishes atomic sufficient-funds checking, client-key lookup after a lost creation response, or safe hold-consuming posting.
- The [Premier-DDA Get Account Details](https://developer.fiserv.com/product/BankingHub/api/post/acctservice/acctmgmt/accounts/secured?branch=main&version=Premier&core=Premier-DDA) reference documents an account selector and a response containing `AcctRec.DepositAcctInfo.AcctBal` entries. The appropriate available-balance type, freshness, and trial statefulness are not yet verified.
- The [Premier Trial sample collection](https://developer.fiserv.com/product/BankingHub/docs/resources/resources.md) (R2026.3.3) includes a synthetic DDA account under **Account Service → Get Account - DDA**. A read-only Account Details request using that sample succeeded on 2026-11-05: the response account ID matched and contained 30 balance entries. The account value is stored only in the private local configuration. This one response does not identify which balance entry is authoritative or prove freshness, statefulness, or a live test core.
- The legacy `FiservAdapter`/`fiserv/` code uses `/banking/v1/*` paths, form-body client credentials, and fixed-point cent strings. Those are local fixture shapes. They have not been mapped to this Banking Hub contract and must not be aimed at a Fiserv host.

## Java read-only adapter and reproduction

`PremierTrialAdapter` is separate from the legacy fixture adapter. It pins the documented CERT token and API destinations, refuses redirects, uses Basic key/secret authentication with `grant_type=client_credentials`, and sends the published `AcctSel.AcctKeys` DDA selector with an `EFXHeader`. It checks that the returned account ID matches and that `AcctBal` is a nonempty list. It discards the token and full response. Its `ReliableOutboundCorePort` balance method returns empty because no balance entry has been verified as authoritative available funds; all mutation and recovery methods return `UNSUPPORTED`.

With JDK 17+, Maven, and the existing private `~/.config/openfednow/fiserv-trial.env` containing only the published synthetic DDA, run this opt-in read-only call from the repository root:

```bash
mvn -q spring-boot:run -Dspring-boot.run.main-class=io.openfednow.acl.adapters.fiserv.PremierTrialProbe -Dspring-boot.run.arguments=--read-sample-dda
```

The probe prints only an identity-match flag, number of balance entries, and `available_funds=unverified`. Local tests use synthetic responses and make no network call. The Trial run and local tests are recorded in the [dated Java evidence bundle](evidence/2026-11-05-premier-java-read/manifest.md).

## Access and safety boundary

The user has created a Banking Hub Trial workspace and configured credentials and a sample DDA account in a private local file. Token acquisition and one read-only account request were tested. Do not buy a plan or invoke money-changing APIs as part of local checks. The existing `/fednow/send` walkthrough uses a synthetic SQL core and separate rail simulator. It does not use Banking Hub. The new outbound core contract defaults to unsupported and cannot authorize a send.

Future mutation tests need official versioned schemas for the chosen operations, provider-approved mutation fixtures, permitted test behavior, and credentials stored outside Git. A local private configuration template is at `~/.config/openfednow/fiserv-trial.env` on Daniel's machine; it is not part of this repository. Confirm any commercial terms directly before selecting a plan. Capture redacted provider observations and a separate lookup before claiming a financial effect.

The tracked [configuration example](fiserv-trial.env.example) contains placeholders only. The local file should be readable only by its owner (`chmod 600 ~/.config/openfednow/fiserv-trial.env`). Fill its API key, secret and Premier organization ID from the Trial workspace without sharing them in chat, screenshots or issue comments. Use only a sample account published for the Trial. Run `python3 scripts/fiserv_trial_preflight.py` for a local readiness check, `--authenticate` for one token request, or `--read-sample-dda` for the published read-only DDA request. The preflight refuses destinations other than the documented CERT host and never prints the token, credentials, account ID, or full account response. It makes no money-changing request.

## Next gates toward a verified hold

1. Confirm a named stateful Premier test core, approved synthetic mutation accounts, entitlements, and permissible fault tests with Fiserv. The shared Trial sandbox and its mock-server examples do not establish those properties.
2. Get the versioned hold, balance, lookup, release, and relevant posting specifications. In particular, establish which `AcctBal` entry is available funds, whether hold creation checks funds atomically, and which other channels honor it.
3. Establish a recoverable client identity. Add Account Hold returns `AcctHoldStatusRec.AcctHoldKeys.AcctHoldIdent`; Get Account Hold can list account holds or select by that provider ID. The public pages do not establish an unambiguous search by a client key after the response containing `AcctHoldIdent` is lost.
4. Only then implement a persisted mutation intent and opt-in provider test: observe before state, create one hold, independently inspect balance and hold, drop a response, recover the original hold, and confirm no duplicate. Report every failed or unsupported step. Posting against a simulated rail is a later gate.
