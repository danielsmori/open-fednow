package io.openfednow.gateway.signing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip and structure tests for {@link FedNowJwsSigner} + {@link FedNowJwsVerifier}.
 *
 * <p>A self-signed test keypair drives both sides so we can verify the wire format
 * without requiring a real Fed PKI certificate. The verifier is checked against
 * every documented failure mode: tampered payload, unknown kid, unsupported
 * algorithm, missing b64=false, malformed structure.
 */
class FedNowJwsSigningTest {

    private static KeyPair keyPair;
    private static PublicKey publicKey;
    private static final String KID = "test-key-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        publicKey = keyPair.getPublic();
    }

    // ── Structure of the produced JWS ────────────────────────────────────────

    @Test
    void producedJwsHasDetachedShape() {
        FedNowJwsSigner signer = new FedNowJwsSigner(keyPair.getPrivate(), KID);
        String jws = signer.sign("hello".getBytes(StandardCharsets.UTF_8));

        // Detached form: <header>..<signature>  — exactly two dots, empty middle
        assertThat(jws).matches("[^.]+\\.\\.[^.]+");
    }

    @Test
    void protectedHeaderIsRs256Base64WithB64FalseAndCrit() throws Exception {
        FedNowJwsSigner signer = new FedNowJwsSigner(keyPair.getPrivate(), KID);
        String jws = signer.sign("payload".getBytes(StandardCharsets.UTF_8));

        String encodedHeader = jws.substring(0, jws.indexOf('.'));
        byte[] headerBytes = Base64.getUrlDecoder().decode(encodedHeader);
        JsonNode header = MAPPER.readTree(headerBytes);

        assertThat(header.get("alg").asText()).isEqualTo("RS256");
        assertThat(header.get("kid").asText()).isEqualTo(KID);
        assertThat(header.get("b64").asBoolean()).isFalse();
        assertThat(header.get("crit").isArray()).isTrue();
        assertThat(header.get("crit").get(0).asText()).isEqualTo("b64");
    }

    // ── Round-trip ───────────────────────────────────────────────────────────

    @Test
    void signAndVerifyRoundTripSucceeds() {
        FedNowJwsSigner signer = new FedNowJwsSigner(keyPair.getPrivate(), KID);
        FedNowJwsVerifier verifier = new FedNowJwsVerifier(kid -> KID.equals(kid) ? publicKey : null);

        byte[] payload = "{\"messageId\":\"MSG-1\",\"amount\":100.00}"
                .getBytes(StandardCharsets.UTF_8);
        String jws = signer.sign(payload);

        // Round-trip must not throw
        verifier.verify(jws, payload);
    }

    @Test
    void differentPayloadsProduceDifferentSignatures() {
        FedNowJwsSigner signer = new FedNowJwsSigner(keyPair.getPrivate(), KID);
        String a = signer.sign("one".getBytes(StandardCharsets.UTF_8));
        String b = signer.sign("two".getBytes(StandardCharsets.UTF_8));
        assertThat(a).isNotEqualTo(b);
    }

    // ── Verifier rejects invalid signatures ──────────────────────────────────

    @Test
    void tamperedPayloadFailsVerification() {
        FedNowJwsSigner signer = new FedNowJwsSigner(keyPair.getPrivate(), KID);
        FedNowJwsVerifier verifier = new FedNowJwsVerifier(kid -> publicKey);

        byte[] original = "authentic".getBytes(StandardCharsets.UTF_8);
        byte[] tampered = "tampered!".getBytes(StandardCharsets.UTF_8);
        String jws = signer.sign(original);

        assertThatThrownBy(() -> verifier.verify(jws, tampered))
                .isInstanceOf(FedNowJwsVerifier.JwsVerificationException.class)
                .hasMessageContaining("verification failed");
    }

    @Test
    void unknownKidFailsVerification() {
        FedNowJwsSigner signer = new FedNowJwsSigner(keyPair.getPrivate(), KID);
        // Resolver returns null for every kid — simulates an untrusted signer
        FedNowJwsVerifier verifier = new FedNowJwsVerifier(kid -> null);

        String jws = signer.sign("x".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> verifier.verify(jws, "x".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(FedNowJwsVerifier.JwsVerificationException.class)
                .hasMessageContaining("No public key");
    }

    @Test
    void differentKeyFailsVerification() throws Exception {
        FedNowJwsSigner signer = new FedNowJwsSigner(keyPair.getPrivate(), KID);
        // Verifier uses a DIFFERENT keypair's public key
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        PublicKey otherKey = gen.generateKeyPair().getPublic();
        FedNowJwsVerifier verifier = new FedNowJwsVerifier(kid -> otherKey);

        String jws = signer.sign("payload".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> verifier.verify(jws, "payload".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(FedNowJwsVerifier.JwsVerificationException.class);
    }

    // ── Verifier rejects malformed input ─────────────────────────────────────

    @Test
    void missingSignatureHeaderIsRejected() {
        FedNowJwsVerifier verifier = new FedNowJwsVerifier(kid -> publicKey);
        assertThatThrownBy(() -> verifier.verify(null, "body".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("Missing JWS signature");
        assertThatThrownBy(() -> verifier.verify("", "body".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("Missing JWS signature");
    }

    @Test
    void nonDetachedPayloadSegmentIsRejected() {
        // A compact JWS with a non-empty middle segment is not detached
        FedNowJwsVerifier verifier = new FedNowJwsVerifier(kid -> publicKey);
        String notDetached = "aGVhZGVy.cGF5bG9hZA.c2ln";  // header.payload.sig
        assertThatThrownBy(() -> verifier.verify(notDetached, new byte[0]))
                .hasMessageContaining("must be empty for detached");
    }

    @Test
    void unsupportedAlgorithmIsRejected() throws Exception {
        // Hand-craft a JWS whose header uses HS256 — the verifier must refuse
        String header = "{\"alg\":\"HS256\",\"kid\":\"" + KID + "\",\"b64\":false,\"crit\":[\"b64\"]}";
        String encodedHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(header.getBytes(StandardCharsets.UTF_8));
        String jws = encodedHeader + ".." + "signature";

        FedNowJwsVerifier verifier = new FedNowJwsVerifier(kid -> publicKey);
        assertThatThrownBy(() -> verifier.verify(jws, "x".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("Unsupported alg");
    }

    @Test
    void b64TrueIsRejected() throws Exception {
        // A header without b64=false would use base64-encoded payload — not what we do
        String header = "{\"alg\":\"RS256\",\"kid\":\"" + KID + "\",\"b64\":true,\"crit\":[\"b64\"]}";
        String encodedHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(header.getBytes(StandardCharsets.UTF_8));
        String jws = encodedHeader + ".." + "signature";

        FedNowJwsVerifier verifier = new FedNowJwsVerifier(kid -> publicKey);
        assertThatThrownBy(() -> verifier.verify(jws, "x".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("must set b64:false");
    }

    @Test
    void unrecognizedCritExtensionIsRejected() throws Exception {
        // RFC 7515: any critical extension the verifier doesn't understand → reject
        String header = "{\"alg\":\"RS256\",\"kid\":\"" + KID + "\",\"b64\":false,\"crit\":[\"b64\",\"exp\"]}";
        String encodedHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(header.getBytes(StandardCharsets.UTF_8));
        String jws = encodedHeader + ".." + "signature";

        FedNowJwsVerifier verifier = new FedNowJwsVerifier(kid -> publicKey);
        assertThatThrownBy(() -> verifier.verify(jws, "x".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("unrecognized extension");
    }

    // ── Construction validation ──────────────────────────────────────────────

    @Test
    void nullPrivateKeyIsRejected() {
        assertThatThrownBy(() -> new FedNowJwsSigner(null, KID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankKidIsRejected() {
        assertThatThrownBy(() -> new FedNowJwsSigner(keyPair.getPrivate(), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keyIdWithQuoteIsEscapedInHeader() throws Exception {
        // Defensive: a key-id that happens to contain a quote must not break the header JSON
        FedNowJwsSigner signer = new FedNowJwsSigner(keyPair.getPrivate(), "kid\"with\"quotes");
        String jws = signer.sign("x".getBytes(StandardCharsets.UTF_8));

        String encodedHeader = jws.substring(0, jws.indexOf('.'));
        byte[] headerBytes = Base64.getUrlDecoder().decode(encodedHeader);
        // Must be valid JSON with the quotes preserved as a single string value
        JsonNode header = MAPPER.readTree(headerBytes);
        assertThat(header.get("kid").asText()).isEqualTo("kid\"with\"quotes");
    }

    // ── Package internals for other tests ────────────────────────────────────

    /** Exposes the generated keypair to sibling tests (e.g., the filter integration test). */
    static Map<String, Object> sharedKeypair() {
        return Map.of("kid", KID, "privateKey", keyPair.getPrivate(), "publicKey", publicKey);
    }
}
