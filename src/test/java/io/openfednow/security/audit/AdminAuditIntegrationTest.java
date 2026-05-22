package io.openfednow.security.audit;

import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that exercises {@link AdminAccessAuditFilter} end to end —
 * issue #50.
 *
 * <p>Drives real HTTP through {@link TestRestTemplate} so Spring Security
 * runs and the audit filter writes through to PostgreSQL via Testcontainers.
 * Verifies that both GRANTED and DENIED requests produce audit rows with the
 * expected principal and result classification.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminAuditIntegrationTest extends AbstractInfrastructureIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM admin_audit_log");
    }

    @Test
    void deniedRequestWithoutCredentialsIsAuditedAsAnonymous() {
        ResponseEntity<String> response = restTemplate.postForEntity("/admin/reconcile",
                null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        Map<String, Object> row = latestAuditRow();
        assertThat(row.get("principal")).isEqualTo("anonymous");
        assertThat(row.get("http_method")).isEqualTo("POST");
        assertThat(row.get("request_path")).isEqualTo("/admin/reconcile");
        assertThat(row.get("result")).isEqualTo("DENIED");
        assertThat(((Number) row.get("status_code")).intValue()).isEqualTo(401);
    }

    @Test
    void deniedRequestWithBadCredentialsCapturesAttemptedUsername() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth("alice", "wrong-password")
                .postForEntity("/admin/reconcile", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        Map<String, Object> row = latestAuditRow();
        assertThat(row.get("principal")).isEqualTo("alice");
        assertThat(row.get("result")).isEqualTo("DENIED");
    }

    @Test
    void grantedRequestIsAuditedWithAuthenticatedUsername() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth("admin", "changeme")
                .getForEntity("/admin/sagas", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // GET /admin/sagas may produce its own audit row; find the matching one.
        List<Map<String, Object>> sagasRequests = jdbc.queryForList(
                "SELECT * FROM admin_audit_log WHERE request_path = '/admin/sagas' " +
                "ORDER BY requested_at DESC, id DESC");
        assertThat(sagasRequests).isNotEmpty();
        Map<String, Object> row = sagasRequests.get(0);
        assertThat(row.get("principal")).isEqualTo("admin");
        assertThat(row.get("result")).isEqualTo("GRANTED");
        assertThat(((Number) row.get("status_code")).intValue()).isEqualTo(200);
    }

    @Test
    void nonAdminRequestProducesNoAuditRow() {
        restTemplate.getForEntity("/fednow/health", String.class);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_audit_log", Integer.class);
        assertThat(count).isZero();
    }

    private Map<String, Object> latestAuditRow() {
        return jdbc.queryForMap(
                "SELECT * FROM admin_audit_log ORDER BY requested_at DESC, id DESC LIMIT 1");
    }
}
