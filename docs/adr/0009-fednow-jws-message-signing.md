# ADR-0009: FedNow JWS detached message signing

## Status

Accepted

## Context

FedNow's Operating Circular and Technical Specifications require that every submitted `pacs.008` credit transfer — and the responses FedNow sends back — carry a JWS detached signature computed with the sender's Federal Reserve PKI private key. The signature is transmitted in the `X-JWS-Signature` HTTP header; the payload itself remains the JSON body of the request.

The signature is what makes FedNow's message authentication work at the application layer, independently of the mTLS termination happening at the network layer. mTLS proves that a TLS peer is authorized to reach the FedNow endpoint; the JWS proves that a specific message was authored by the institution that holds the corresponding private key. Both are required — one is not a substitute for the other.

Before this ADR the framework was mTLS-aware (via `CertificateManager`) but had no JWS support. Live FedNow connectivity was blocked on that gap. Every other production-readiness item on the audit could be addressed without external access; JWS is the last one.

## Decision

Implement RS256 detached JWS with `b64=false` per RFC 7515 + RFC 7797, matching the FedNow specification. Both sides — outbound signing on `HttpFedNowClient` and inbound verification on `/fednow/**` — are opt-in via a single feature flag; the sandbox / simulator flow continues to work unchanged.

### Wire format

Detached JWS compact serialization:

```
BASE64URL(header) .. BASE64URL(signature)
```

Two dots on purpose — the middle (payload) segment is empty because the payload travels in the HTTP body. RFC 7797 §3 defines the signing input in this case as:

```
ASCII(BASE64URL(header)) || 0x2E || payload_bytes
```

That is: the base64url-encoded header, an ASCII `.`, then the *raw* payload bytes without any encoding. `b64=false` in the header is what selects this path.

### Protected header

```
{
  "alg":  "RS256",
  "kid":  "<institution-key-id>",
  "b64":  false,
  "crit": ["b64"]
}
```

- **RS256** — RSA + SHA-256, the Fed PKI standard for message signing. Key size ≥ 2048 bits is enforced at load time.
- **kid** — the identifier Fed uses to look up the public key. The framework accepts an explicit `openfednow.fednow.signing.key-id`; when unset it derives a SHA-256 thumbprint of the certificate as the kid, so both sides can arrive at the same value without out-of-band coordination.
- **b64=false** — signals `RFC 7797` unencoded-payload signing. Prevents any encoding drift between signer and verifier over the raw JSON bytes.
- **crit=["b64"]** — required by RFC 7515 §4.1.11. A verifier that doesn't understand the `b64` extension MUST reject the JWS. This makes the scheme self-describing and future-proof.

### Failure modes and their HTTP surface

| Failure | Outcome |
|---|---|
| Missing `X-JWS-Signature` header | 401 `{ "error": "jws_verification_failed", "reason": "Missing JWS signature header" }` |
| Malformed compact serialization | 401 with a reason describing the specific structural violation |
| Unsupported `alg` (anything except RS256) | 401 |
| `b64=true` or `crit` doesn't declare `b64` as critical | 401 |
| Unknown `kid` | 401 |
| Signature does not verify against the payload | 401 |
| Any exception from the JCE | 401 |

In every failure case the downstream controller is never invoked — no idempotency record, no saga, no side effect.

### Sandbox / dev behavior

The signer and verifier beans are `@ConditionalOnProperty(openfednow.fednow.signing.enabled=true)`. When the flag is off (the default), `HttpFedNowClient` sends unsigned requests — which is correct against the WireMock simulator — and `JwsInboundVerificationFilter` short-circuits on `shouldNotFilter` so every request passes untouched. This matches the pattern the framework already uses for mTLS (`CertificateManager`): the sandbox is unauthenticated so the demo `./demo/run-demo.sh` works without institutional credentials.

## Alternatives Considered

**Nimbus JOSE+JWT as the underlying library.**
Nimbus is the standard Java JWS/JWT library and handles detached signatures + `b64=false` correctly. Rejected in favor of a direct implementation on top of `java.security.Signature` — the scope is narrow (one algorithm, one header shape, no key discovery / JWK Set fetching), so an in-repo ~150-line implementation is easier to audit than depending on a 600 KB library. The full JWS grammar we support is exactly what `FedNowJwsSigner` writes; nothing else is on the wire.

**HMAC (HS256) instead of RSA.**
HMAC is simpler and faster but requires a shared secret between the institution and the Fed. The Fed does not distribute shared secrets; institutions are authenticated by public-key identity. Not an option in practice.

**Envelope JWS (payload in the middle segment) instead of detached.**
Envelope form would duplicate the payload — once base64-encoded in the JWS, once as the HTTP body — which the FedNow spec explicitly does not permit and which would waste bandwidth. Rejected.

**Signing / verifying the parsed model instead of the raw bytes.**
Any Java-side (de)serialization is a source of drift: field ordering, whitespace, number formatting can differ across serializers. Signing the wire bytes eliminates that surface entirely. RFC 7797 was created for exactly this use case; using it correctly means we sign the actual JSON that leaves the HTTP stack.

**Skip inbound verification when the Ingress terminates mTLS.**
mTLS authenticates the transport peer, not the message. A compromised middlebox or a mis-routed TLS session would let a forged pacs.002 through mTLS but not through JWS verification. Rejected as insufficient defense in depth; the operational cost of always verifying is negligible.

## Consequences

**Positive:**
- Live FedNow connectivity is no longer blocked by a missing code-side capability. Institutions with Fed PKI credentials can enable signing with a single environment flag; nothing else changes.
- Both directions are covered: outbound signing on every submission, inbound verification on every `/fednow/**` POST.
- The implementation is small enough to audit line-by-line: the signer is ~50 lines of substance, the verifier ~100, and the servlet filter buffers the body exactly once.
- Sandbox / demo workflows keep working — the feature flag is off by default.
- The `kid` derivation via SHA-256 thumbprint eliminates out-of-band key-identifier coordination for the common single-signing-key case.

**Negative:**
- We have not exercised against a live Fed endpoint. The wire format matches published spec references, but end-to-end validation against the actual FedNow production environment requires institutional onboarding. This is the same constraint that applies to every other framework component that touches the Fed side.
- Only RS256 is supported. When the Fed publishes support for PS256 or EdDSA (some payment networks are moving that direction), this will need to be extended.
- Key rotation is not automated — the current bean loads the key at startup. An institution rotating keys must recycle the pod (or use the compensation retry sweep to reissue in-flight work). Automated in-place rotation is a follow-up.

## Related

- `FedNowJwsSigner.java` — outbound signing
- `FedNowJwsVerifier.java` — inbound verification
- `FedNowSigningConfig.java` — keystore-driven bean wiring
- `HttpFedNowClient.java` — RestTemplate interceptor that attaches the header
- `JwsInboundVerificationFilter.java` — servlet filter for the `/fednow/**` path
- `CertificateManager.java` — mTLS termination and keystore lifecycle (pre-existing)
- RFC 7515 — JSON Web Signature
- RFC 7797 — JWS Unencoded Payload Option
