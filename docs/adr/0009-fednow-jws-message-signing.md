# ADR-0009: Optional detached JWS reference component

## Status

Accepted for local reference testing. This is **not** a verified FedNow message-security profile or the last step to live connectivity.

## Context and decision

The repository includes `FedNowJwsSigner`, `FedNowJwsVerifier`, a signing configuration and an inbound filter. They implement a locally tested RS256 detached JWS shape with `b64=false` under RFC 7515 and RFC 7797. The component is opt-in via `openfednow.fednow.signing.enabled=true`; the sandbox default leaves it off. Unit tests establish behavior for the authored keys, headers and bodies.

The applicable FedNow message, header, key-management, transport and authentication requirements must be obtained from current participant-accessible specifications and verified in an authorized environment. Neither the public operating procedures nor a locally passing RFC test proves that `X-JWS-Signature`, key ID derivation, canonicalization or any other field matches the live service. Configuring a keystore or HTTP endpoint is insufficient. The gateway and SQL reliability paths remain synthetic.

## Consequences

The signer/verifier is reusable code for controlled evaluation. It cannot be described as live FedNow interoperability, complete PKI integration, certification or production readiness. See [reliability scope](../reliability/scope.md) and [known limitations](../known-limitations.md).
