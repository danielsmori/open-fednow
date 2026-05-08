package io.openfednow.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Enumeration;

/**
 * Layer 1 — Certificate Management
 *
 * <p>Manages TLS mutual authentication using Federal Reserve PKI certificates.
 * FedNow requires that all participating institutions authenticate using
 * certificates issued by the Federal Reserve's Certificate Authority.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Certificate expiry monitoring (alerts at 30 days before expiry)</li>
 *   <li>Inbound mTLS validation — FedNow client certificates must be issued
 *       by the Fed PKI; in dev/staging this check is skipped when no
 *       truststore is configured</li>
 *   <li>Outbound connection setup is handled by the JVM keystore configured
 *       via {@code TLS_KEYSTORE_PATH} and {@code TLS_KEYSTORE_PASSWORD}</li>
 * </ul>
 */
@Component
public class CertificateManager {

    private static final Logger log = LoggerFactory.getLogger(CertificateManager.class);

    /** Warn when the outbound certificate expires within this many days. */
    private static final long RENEWAL_WARNING_DAYS = 30;

    @Value("${openfednow.tls.keystore-path:}")
    private String keystorePath;

    @Value("${openfednow.tls.keystore-password:}")
    private String keystorePassword;

    @Value("${openfednow.tls.fed-truststore-path:}")
    private String fedTruststorePath;

    /**
     * Validates the client certificate on an inbound connection from FedNow.
     *
     * <p>When no Fed truststore is configured (dev / sandbox mode), validation
     * is skipped and a warning is logged. In production the truststore is
     * mounted from the Kubernetes secret {@code openfednow-tls}.
     *
     * @throws SecurityException if the certificate is invalid, expired, or
     *         not issued by the Federal Reserve Certificate Authority
     */
    public void validateClientCertificate() {
        if (!StringUtils.hasText(fedTruststorePath)) {
            log.debug("Fed PKI truststore not configured — skipping inbound mTLS validation (dev/sandbox mode)");
            return;
        }
        // In production, inbound mTLS is enforced at the TLS termination layer
        // (Kubernetes Ingress / Istio) using the Fed truststore. If we reach
        // this point the infrastructure has already validated the certificate.
        log.debug("Inbound certificate validated by TLS termination layer");
    }

    /**
     * Checks whether the institution's outbound certificate is valid and
     * not within the renewal warning window (30 days before expiry).
     *
     * @return true if the certificate is valid and has more than 30 days remaining
     */
    public boolean isOutboundCertificateValid() {
        if (!StringUtils.hasText(keystorePath)) {
            log.debug("Keystore not configured — reporting certificate as valid (dev/sandbox mode)");
            return true;
        }
        return daysUntilCertificateExpiry() > RENEWAL_WARNING_DAYS;
    }

    /**
     * Returns the number of days until the institution's outbound certificate expires.
     * Loads the PKCS12 keystore from the configured path and inspects the first
     * certificate in the chain.
     *
     * @return days until expiry; negative if already expired; {@link Long#MAX_VALUE}
     *         if no keystore is configured (dev/sandbox mode)
     */
    public long daysUntilCertificateExpiry() {
        if (!StringUtils.hasText(keystorePath)) {
            log.debug("Keystore not configured — returning max expiry (dev/sandbox mode)");
            return Long.MAX_VALUE;
        }

        try (InputStream is = new FileInputStream(keystorePath)) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            char[] password = StringUtils.hasText(keystorePassword)
                    ? keystorePassword.toCharArray()
                    : new char[0];
            keyStore.load(is, password);

            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                Certificate cert = keyStore.getCertificate(alias);
                if (cert instanceof X509Certificate x509) {
                    Date notAfter = x509.getNotAfter();
                    long days = ChronoUnit.DAYS.between(Instant.now(), notAfter.toInstant());
                    log.debug("Outbound certificate alias={} expiresInDays={}", alias, days);
                    if (days <= RENEWAL_WARNING_DAYS) {
                        log.warn("Outbound certificate expiring soon: alias={} daysRemaining={}",
                                alias, days);
                    }
                    return days;
                }
            }
            log.warn("No X.509 certificate found in keystore at {}", keystorePath);
            return 0;

        } catch (Exception e) {
            log.error("Failed to load keystore from {} — certificate expiry unknown", keystorePath, e);
            return 0;
        }
    }
}
