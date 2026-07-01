package io.openfednow.gateway.signing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Iterator;
import java.util.function.Function;

/**
 * Verifies an inbound {@code X-JWS-Signature} detached signature against the
 * institution's known Federal Reserve public key(s).
 *
 * <p>The counterpart to {@link FedNowJwsSigner}. FedNow signs the responses
 * it sends us — the framework must verify those signatures before treating
 * the message body as authoritative, so that a compromised transport layer
 * cannot inject a fraudulent pacs.002.
 *
 * <h2>Verification steps</h2>
 * <ol>
 *   <li>Split the compact JWS on the two dots — the middle segment must be empty
 *       (detached form).</li>
 *   <li>Decode the base64url-encoded header; parse it as JSON.</li>
 *   <li>Confirm {@code alg = RS256}, {@code b64 = false}, and that {@code crit}
 *       declares {@code b64} as a critical extension. Reject anything else —
 *       we intentionally do not negotiate weaker algorithms.</li>
 *   <li>Look up the public key for the header's {@code kid} via the supplied
 *       resolver. A key we don't know is a rejection.</li>
 *   <li>Reconstruct the signing input: {@code BASE64URL(header) . payload}
 *       (raw payload bytes, per {@code b64=false}).</li>
 *   <li>Verify the RSA signature with the resolved public key.</li>
 * </ol>
 *
 * <p>Both the payload bytes and the JWS string come from the inbound HTTP
 * request. The filter that invokes this verifier is responsible for capturing
 * the request body without draining it before the controller reads it.
 */
public class FedNowJwsVerifier {

    private static final Logger log = LoggerFactory.getLogger(FedNowJwsVerifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Function<String, PublicKey> publicKeyResolver;

    /**
     * @param publicKeyResolver looks up the {@link PublicKey} for a given {@code kid}.
     *                          Return {@code null} for an unknown key — the verifier
     *                          treats that as an authentication failure.
     */
    public FedNowJwsVerifier(Function<String, PublicKey> publicKeyResolver) {
        if (publicKeyResolver == null) {
            throw new IllegalArgumentException("publicKeyResolver is required");
        }
        this.publicKeyResolver = publicKeyResolver;
    }

    /**
     * Verifies the JWS signature against the supplied payload.
     *
     * @param jws     the detached JWS from the {@code X-JWS-Signature} header
     * @param payload raw payload bytes as they arrived in the HTTP body
     * @throws JwsVerificationException if any check fails — invalid structure,
     *         unknown key, unsupported algorithm, or a bad signature
     */
    public void verify(String jws, byte[] payload) {
        if (jws == null || jws.isBlank()) {
            throw new JwsVerificationException("Missing JWS signature header");
        }
        if (payload == null) {
            throw new JwsVerificationException("Missing payload bytes for verification");
        }

        String[] parts = splitDetached(jws);
        String encodedHeader = parts[0];
        String encodedSignature = parts[1];

        JsonNode header = parseHeader(encodedHeader);
        assertHeaderShape(header);

        String kid = header.path("kid").asText(null);
        if (kid == null || kid.isBlank()) {
            throw new JwsVerificationException("JWS header missing 'kid'");
        }
        PublicKey publicKey = publicKeyResolver.apply(kid);
        if (publicKey == null) {
            throw new JwsVerificationException("No public key configured for kid=" + kid);
        }

        byte[] signature;
        try {
            signature = Base64.getUrlDecoder().decode(encodedSignature);
        } catch (IllegalArgumentException e) {
            throw new JwsVerificationException("JWS signature segment is not valid base64url", e);
        }

        try {
            Signature rs256 = Signature.getInstance("SHA256withRSA");
            rs256.initVerify(publicKey);
            rs256.update(encodedHeader.getBytes(StandardCharsets.US_ASCII));
            rs256.update((byte) '.');
            rs256.update(payload);
            if (!rs256.verify(signature)) {
                throw new JwsVerificationException("JWS signature verification failed for kid=" + kid);
            }
            log.debug("JWS signature verified kid={}", kid);
        } catch (GeneralSecurityException e) {
            throw new JwsVerificationException("JWS signature verification error", e);
        }
    }

    private static String[] splitDetached(String jws) {
        // Detached JWS: <header>..<signature>  (two dots, empty middle)
        int firstDot = jws.indexOf('.');
        if (firstDot < 0) {
            throw new JwsVerificationException("JWS is missing separator dot");
        }
        int secondDot = jws.indexOf('.', firstDot + 1);
        if (secondDot < 0) {
            throw new JwsVerificationException("JWS is missing second separator dot");
        }
        if (secondDot != firstDot + 1) {
            throw new JwsVerificationException(
                    "JWS payload segment must be empty for detached signatures");
        }
        String header = jws.substring(0, firstDot);
        String signature = jws.substring(secondDot + 1);
        if (header.isEmpty() || signature.isEmpty()) {
            throw new JwsVerificationException("JWS header or signature segment is empty");
        }
        return new String[] { header, signature };
    }

    private static JsonNode parseHeader(String encodedHeader) {
        byte[] headerBytes;
        try {
            headerBytes = Base64.getUrlDecoder().decode(encodedHeader);
        } catch (IllegalArgumentException e) {
            throw new JwsVerificationException("JWS header is not valid base64url", e);
        }
        try {
            return MAPPER.readTree(headerBytes);
        } catch (Exception e) {
            throw new JwsVerificationException("JWS header is not valid JSON", e);
        }
    }

    private static void assertHeaderShape(JsonNode header) {
        String alg = header.path("alg").asText(null);
        if (!"RS256".equals(alg)) {
            throw new JwsVerificationException(
                    "Unsupported alg (only RS256 accepted): " + alg);
        }
        JsonNode b64 = header.path("b64");
        if (b64.isMissingNode() || b64.asBoolean(true)) {
            throw new JwsVerificationException("JWS header must set b64:false");
        }
        JsonNode crit = header.path("crit");
        if (!crit.isArray() || !containsValue(crit, "b64")) {
            throw new JwsVerificationException("JWS header must declare b64 in crit[]");
        }
        // Any additional entries in 'crit' would be an unknown critical extension.
        // Per RFC 7515 §4.1.11 we MUST reject if we don't understand them.
        for (Iterator<JsonNode> it = crit.elements(); it.hasNext(); ) {
            String value = it.next().asText(null);
            if (!"b64".equals(value)) {
                throw new JwsVerificationException(
                        "JWS crit contains an unrecognized extension: " + value);
            }
        }
    }

    private static boolean containsValue(JsonNode array, String value) {
        for (Iterator<JsonNode> it = array.elements(); it.hasNext(); ) {
            if (value.equals(it.next().asText(null))) {
                return true;
            }
        }
        return false;
    }

    /** Thrown on any signature verification failure. */
    public static class JwsVerificationException extends RuntimeException {
        public JwsVerificationException(String message) { super(message); }
        public JwsVerificationException(String message, Throwable cause) { super(message, cause); }
    }
}
