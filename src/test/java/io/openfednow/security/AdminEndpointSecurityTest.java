package io.openfednow.security;

import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code /admin/**} endpoints require ADMIN credentials and that
 * FedNow payment endpoints remain publicly accessible.
 *
 * <p>Uses {@link TestRestTemplate} over a real HTTP port so that Spring Security's
 * filter chain is exercised exactly as it would be in production.
 *
 * <p>The default credentials used in tests are the values set in
 * {@code application.yml}: {@code admin} / {@code changeme}. Production deployments
 * must override these via {@code ADMIN_USERNAME} and {@code ADMIN_PASSWORD} environment
 * variables and integrate with the institution's IAM/secrets management system.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminEndpointSecurityTest extends AbstractInfrastructureIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    // ── /admin/reconcile — authentication required ────────────────────────────

    @Test
    void adminReconcile_returns401_withoutCredentials() {
        ResponseEntity<String> response = restTemplate.postForEntity("/admin/reconcile",
                null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminReconcile_returns200_withValidAdminCredentials() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth("admin", "changeme")
                .postForEntity("/admin/reconcile", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void adminReconcile_returns401_withWrongPassword() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth("admin", "wrongpassword")
                .postForEntity("/admin/reconcile", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminReconcile_returns401_withUnknownUser() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth("unknown", "changeme")
                .postForEntity("/admin/reconcile", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── /fednow/** — no authentication required ───────────────────────────────

    @Test
    void fednowHealth_isAccessibleWithoutCredentials() {
        ResponseEntity<String> response = restTemplate.getForEntity("/fednow/health", String.class);
        // Must NOT be 401 — FedNow endpoints are open (mTLS enforced at infrastructure level)
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rtpHealth_isAccessibleWithoutCredentials() {
        ResponseEntity<String> response = restTemplate.getForEntity("/rtp/health", String.class);
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── Actuator — open for internal monitoring ───────────────────────────────

    @Test
    void actuatorHealth_isAccessibleWithoutCredentials() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health",
                String.class);
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
