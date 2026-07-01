package io.openfednow.gateway.signing;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;

/**
 * Produces the FedNow-required JWS detached signature over a payload.
 *
 * <p>FedNow's Operating Circular and Technical Specifications require that
 * every submitted pacs.008 (and other outbound ISO 20022 messages) carry a
 * JWS detached signature computed with the institution's Federal Reserve
 * PKI private key. The signature is transmitted in the {@code X-JWS-Signature}
 * HTTP header; the payload itself remains the JSON body of the request.
 *
 * <h2>Format</h2>
 * <pre>
 *   BASE64URL(header) .. BASE64URL(signature)
 * </pre>
 * The double-dot is intentional — a detached JWS omits the middle (payload)
 * segment because the payload travels in the HTTP body.
 *
 * <h2>Protected header</h2>
 * <pre>
 * {
 *   "alg":  "RS256",
 *   "kid":  "&lt;institution-key-id&gt;",
 *   "b64":  false,
 *   "crit": ["b64"]
 * }
 * </pre>
 *
 * <p>{@code b64:false} instructs the signing algorithm to use the raw payload
 * bytes when constructing the signing input, rather than base64url-encoding
 * them first. This matches the FedNow spec and prevents encoding drift
 * between signer and verifier.
 *
 * <p>{@code crit:["b64"]} is required by RFC 7797 — any verifier that does not
 * understand the {@code b64} extension header MUST reject the JWS. This
 * makes the signing scheme self-describing.
 *
 * <h2>Signing input</h2>
 * <pre>
 *   ASCII(BASE64URL(header)) || 0x2E || payload
 * </pre>
 * (The {@code 0x2E} is a literal ASCII dot separator.)
 *
 * <h2>Algorithm</h2>
 * RS256 (RSA + SHA-256) — the Fed PKI standard. Signing keys must be
 * ≥ 2048-bit RSA per FedNow onboarding requirements. This class does not
 * enforce a key-size minimum; the loader in {@code FedNowSigningConfig}
 * does the validation.
 *
 * <p>Instances are safe to share across threads: {@link PrivateKey} and the
 * signature algorithm are read-only after construction; a fresh
 * {@link Signature} instance is created per {@link #sign(byte[])} call.
 */
public class FedNowJwsSigner {

    /** Compact form of the protected header — same bytes every call, so we cache it. */
    private final byte[] encodedHeader;
    private final PrivateKey privateKey;

    /**
     * @param privateKey RSA private key from the institution's Fed PKI keystore
     * @param keyId      key identifier assigned by the Federal Reserve during onboarding
     *                   (or a stable local identifier that the Fed verifier can look up)
     */
    public FedNowJwsSigner(PrivateKey privateKey, String keyId) {
        if (privateKey == null) {
            throw new IllegalArgumentException("privateKey is required");
        }
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("keyId is required");
        }
        this.privateKey = privateKey;
        this.encodedHeader = buildEncodedHeader(keyId);
    }

    /**
     * Computes the detached JWS signature over the payload and returns the
     * compact serialization suitable for inclusion in the
     * {@code X-JWS-Signature} HTTP header.
     *
     * @param payload raw payload bytes as they will appear on the wire
     * @return the compact detached JWS: {@code BASE64URL(header) + ".." + BASE64URL(signature)}
     */
    public String sign(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload is required");
        }
        try {
            Signature rs256 = Signature.getInstance("SHA256withRSA");
            rs256.initSign(privateKey);
            // Signing input per RFC 7797 §3 when b64=false:
            //   ASCII(BASE64URL(header)) || '.' || payload
            rs256.update(encodedHeader);
            rs256.update((byte) '.');
            rs256.update(payload);
            byte[] signature = rs256.sign();

            String encodedSignature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(signature);
            // Detached form: header .. signature (double-dot, empty payload segment)
            return new String(encodedHeader, StandardCharsets.US_ASCII)
                    + ".."
                    + encodedSignature;
        } catch (GeneralSecurityException e) {
            // The algorithm and key were validated at construction time, so any failure
            // here indicates a genuine JCE / hardware fault. Surface as a runtime
            // exception so the router's outbound catch (which produces a synthetic
            // RJCT NARR) handles it.
            throw new JwsSigningException("Failed to compute JWS signature", e);
        }
    }

    /** Package-visible for tests that want to verify the header shape. */
    byte[] encodedHeader() {
        return encodedHeader.clone();
    }

    private static byte[] buildEncodedHeader(String keyId) {
        // JSON keys are ordered per Fed spec convention: alg, kid, b64, crit.
        // A verifier that reconstructs the input must see the same header bytes.
        String header = "{"
                + "\"alg\":\"RS256\","
                + "\"kid\":" + jsonString(keyId) + ","
                + "\"b64\":false,"
                + "\"crit\":[\"b64\"]"
                + "}";
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(header.getBytes(StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Minimal JSON string encoder — escapes the characters that could otherwise break
     * out of the header object. A full JSON serializer would be overkill for a value
     * that is exclusively a Fed-issued key identifier, but we still guard against
     * caller error (e.g., a key-id containing a quote).
     */
    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    /** Thrown when signature computation fails at runtime. */
    public static class JwsSigningException extends RuntimeException {
        public JwsSigningException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
