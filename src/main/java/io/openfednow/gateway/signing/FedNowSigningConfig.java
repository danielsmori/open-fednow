package io.openfednow.gateway.signing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAKey;
import java.util.HashMap;
import java.util.Map;

/**
 * Spring wiring for FedNow JWS message signing and verification.
 *
 * <p>Both beans are conditional on {@code openfednow.fednow.signing.enabled=true}.
 * A sandbox / dev deployment leaves the flag unset and no signing/verification
 * beans are created — the FedNow client sends unsigned requests (fine against
 * the simulator) and inbound requests are not verified (mTLS termination at
 * the Ingress is still enforced separately).
 *
 * <p>In production the flag is on, the keystore is mounted from a Kubernetes
 * secret, and {@link FedNowJwsSigner} loads the institution's private key at
 * startup. The verifier bean loads the trusted Fed public keys from the
 * signing truststore.
 */
@Configuration
@ConditionalOnProperty(name = "openfednow.fednow.signing.enabled", havingValue = "true")
public class FedNowSigningConfig {

    private static final Logger log = LoggerFactory.getLogger(FedNowSigningConfig.class);

    /**
     * Loads the institution's RSA private key from the configured keystore and
     * publishes a {@link FedNowJwsSigner} for outbound message signing.
     *
     * <p>Key selection: if {@code openfednow.fednow.signing.key-alias} is set, that
     * alias is used; otherwise the first key entry in the keystore is loaded.
     * Fed onboarding provides a single signing key per institution, so the default
     * matches the typical setup while the property covers rotation scenarios where
     * multiple aliases coexist temporarily.
     *
     * <p>The {@code kid} value is the identifier the Fed expects in the JWS header
     * to look up the corresponding public key. If {@code openfednow.fednow.signing.key-id}
     * is set, that value is used. Otherwise we compute a SHA-256 thumbprint of the
     * certificate, base64url-encoded — a stable identifier that both sides can
     * derive independently.
     */
    @Bean
    public FedNowJwsSigner fedNowJwsSigner(
            @Value("${openfednow.tls.keystore-path:}") String keystorePath,
            @Value("${openfednow.tls.keystore-password:}") String keystorePassword,
            @Value("${openfednow.fednow.signing.key-alias:}") String keyAlias,
            @Value("${openfednow.fednow.signing.key-id:}") String configuredKeyId) throws Exception {

        if (!StringUtils.hasText(keystorePath)) {
            throw new IllegalStateException(
                    "openfednow.fednow.signing.enabled=true but no keystore is configured. "
                            + "Set TLS_KEYSTORE_PATH / TLS_KEYSTORE_PASSWORD or disable signing.");
        }

        KeyStore keystore = loadKeystore(keystorePath, keystorePassword);
        char[] password = passwordChars(keystorePassword);
        String alias = resolveSigningAlias(keystore, keyAlias);

        PrivateKey privateKey = (PrivateKey) keystore.getKey(alias, password);
        if (privateKey == null) {
            throw new IllegalStateException("No private key found in keystore for alias=" + alias);
        }
        if (privateKey instanceof RSAKey rsa && rsa.getModulus().bitLength() < 2048) {
            throw new IllegalStateException(
                    "RSA signing key must be at least 2048-bit (found "
                            + rsa.getModulus().bitLength() + "-bit)");
        }

        X509Certificate cert = (X509Certificate) keystore.getCertificate(alias);
        String kid = StringUtils.hasText(configuredKeyId)
                ? configuredKeyId
                : sha256Thumbprint(cert);

        log.info("FedNow JWS signing enabled alias={} kid={} algorithm=RS256", alias, kid);
        return new FedNowJwsSigner(privateKey, kid);
    }

    /**
     * Loads the Fed's public signing keys from the signing truststore and publishes
     * a {@link FedNowJwsVerifier}. Each certificate in the truststore contributes
     * one {@code kid → PublicKey} entry (SHA-256 thumbprint of the certificate is
     * the kid, matching how the signer derives it).
     *
     * <p>When {@code openfednow.fednow.signing.truststore-path} is not set, this
     * bean is not created; the {@code JwsInboundVerificationFilter} then treats
     * verification as a no-op (sandbox / dev). Production must set the property
     * or inbound signatures are effectively unchecked.
     */
    @Bean
    public FedNowJwsVerifier fedNowJwsVerifier(
            @Value("${openfednow.fednow.signing.truststore-path:}") String truststorePath,
            @Value("${openfednow.fednow.signing.truststore-password:}") String truststorePassword) throws Exception {

        Map<String, PublicKey> keysByKid = new HashMap<>();
        if (StringUtils.hasText(truststorePath)) {
            KeyStore truststore = loadKeystore(truststorePath, truststorePassword);
            for (String alias : java.util.Collections.list(truststore.aliases())) {
                Certificate cert = truststore.getCertificate(alias);
                if (cert instanceof X509Certificate x509) {
                    String kid = sha256Thumbprint(x509);
                    keysByKid.put(kid, x509.getPublicKey());
                    log.info("Loaded Fed public key alias={} kid={}", alias, kid);
                }
            }
        } else {
            log.warn("openfednow.fednow.signing.enabled=true but no signing truststore configured — "
                    + "inbound signatures cannot be verified. Set FEDNOW_SIGNING_TRUSTSTORE_PATH before "
                    + "processing production traffic.");
        }
        Map<String, PublicKey> immutable = Map.copyOf(keysByKid);
        return new FedNowJwsVerifier(immutable::get);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static KeyStore loadKeystore(String path, String password) throws Exception {
        try (InputStream is = new FileInputStream(path)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(is, passwordChars(password));
            return ks;
        }
    }

    private static char[] passwordChars(String password) {
        return StringUtils.hasText(password) ? password.toCharArray() : new char[0];
    }

    private static String resolveSigningAlias(KeyStore keystore, String configuredAlias) throws Exception {
        if (StringUtils.hasText(configuredAlias)) {
            if (!keystore.containsAlias(configuredAlias)) {
                throw new IllegalStateException(
                        "Configured signing alias not found in keystore: " + configuredAlias);
            }
            return configuredAlias;
        }
        for (String alias : java.util.Collections.list(keystore.aliases())) {
            if (keystore.isKeyEntry(alias)) {
                return alias;
            }
        }
        throw new IllegalStateException("Keystore contains no private-key entries");
    }

    /**
     * SHA-256 thumbprint of a certificate, base64url-encoded without padding.
     * Used as the JWS {@code kid} so the same identifier can be derived on both
     * ends without out-of-band coordination.
     */
    static String sha256Thumbprint(X509Certificate cert) throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] digest = sha256.digest(cert.getEncoded());
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
}
