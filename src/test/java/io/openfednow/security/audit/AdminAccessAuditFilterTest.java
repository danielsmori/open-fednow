package io.openfednow.security.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link AdminAccessAuditFilter} — issue #50.
 *
 * <p>Verifies the per-request audit contract without loading Spring Security:
 * principal resolution, header parsing, status classification, and the
 * "do nothing for non-admin paths" rule.
 */
class AdminAccessAuditFilterTest {

    private AdminAuditLogService auditService;
    private AdminAccessAuditFilter filter;

    @BeforeEach
    void setUp() {
        auditService = mock(AdminAuditLogService.class);
        filter = new AdminAccessAuditFilter(auditService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── Path scoping ─────────────────────────────────────────────────────────

    @Test
    void nonAdminRequestsAreNotAudited() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/fednow/receive");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        verify(auditService, never()).record(org.mockito.ArgumentMatchers.any());
    }

    // ── Result classification ────────────────────────────────────────────────

    @Test
    void successfulRequestIsClassifiedAsGranted() throws Exception {
        AdminAuditEntry recorded = runFilter("GET", "/admin/sagas", null, 200, null);

        assertThat(recorded.result()).isEqualTo(AuditResult.GRANTED);
        assertThat(recorded.statusCode()).isEqualTo(200);
        assertThat(recorded.httpMethod()).isEqualTo("GET");
        assertThat(recorded.requestPath()).isEqualTo("/admin/sagas");
    }

    @Test
    void unauthorizedRequestIsClassifiedAsDenied() throws Exception {
        AdminAuditEntry recorded = runFilter("GET", "/admin/sagas", null, 401, null);

        assertThat(recorded.result()).isEqualTo(AuditResult.DENIED);
    }

    @Test
    void forbiddenRequestIsClassifiedAsDenied() throws Exception {
        AdminAuditEntry recorded = runFilter("GET", "/admin/sagas", null, 403, null);

        assertThat(recorded.result()).isEqualTo(AuditResult.DENIED);
    }

    @Test
    void otherFourXxIsClassifiedAsRejected() throws Exception {
        AdminAuditEntry recorded = runFilter("GET", "/admin/sagas/UNKNOWN", null, 404, null);

        assertThat(recorded.result()).isEqualTo(AuditResult.REJECTED);
    }

    @Test
    void fiveXxIsClassifiedAsError() throws Exception {
        AdminAuditEntry recorded = runFilter("POST", "/admin/reconcile", null, 500, null);

        assertThat(recorded.result()).isEqualTo(AuditResult.ERROR);
    }

    // ── Principal resolution ─────────────────────────────────────────────────

    @Test
    void anonymousIsRecordedWhenNoAuthHeaderPresent() throws Exception {
        AdminAuditEntry recorded = runFilter("GET", "/admin/sagas", null, 401, null);

        assertThat(recorded.principal()).isEqualTo(AdminAccessAuditFilter.ANONYMOUS_PRINCIPAL);
    }

    @Test
    void attemptedUsernameFromBasicHeaderIsRecordedOnFailure() throws Exception {
        String header = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("alice:wrong-password".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        AdminAuditEntry recorded = runFilter("GET", "/admin/sagas", header, 401, null);

        assertThat(recorded.principal()).isEqualTo("alice");
    }

    @Test
    void authenticatedPrincipalFromContextIsRecordedOnSuccess() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "alice", "creds",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        // No Authorization header — the filter must read from the SecurityContext
        AdminAuditEntry recorded = runFilter("GET", "/admin/sagas", null, 200, null);

        assertThat(recorded.principal()).isEqualTo("alice");
    }

    @Test
    void malformedAuthHeaderFallsBackToAnonymous() throws Exception {
        AdminAuditEntry recorded = runFilter("GET", "/admin/sagas", "Basic not-base64!", 401, null);
        assertThat(recorded.principal()).isEqualTo(AdminAccessAuditFilter.ANONYMOUS_PRINCIPAL);
    }

    @Test
    void nonBasicAuthHeaderIsIgnoredForPrincipalParsing() throws Exception {
        AdminAuditEntry recorded = runFilter("GET", "/admin/sagas",
                "Bearer some-jwt-token", 401, null);
        assertThat(recorded.principal()).isEqualTo(AdminAccessAuditFilter.ANONYMOUS_PRINCIPAL);
    }

    // ── Query string capture ─────────────────────────────────────────────────

    @Test
    void queryStringIsRecorded() throws Exception {
        AdminAuditEntry recorded = runFilter("GET", "/admin/audit-log", null, 200, "limit=10&offset=5");

        assertThat(recorded.queryString()).isEqualTo("limit=10&offset=5");
    }

    @Test
    void absentQueryStringIsNull() throws Exception {
        AdminAuditEntry recorded = runFilter("GET", "/admin/sagas", null, 200, null);
        assertThat(recorded.queryString()).isNull();
    }

    @Test
    void sensitiveQueryParametersAreRedactedBeforePersistence() throws Exception {
        // No admin endpoint accepts these params today, but the audit filter is
        // the last line of defense — a future endpoint that inadvertently
        // exposes a secret in the URL must not leak into the audit table.
        AdminAuditEntry recorded = runFilter("GET", "/admin/sagas", null, 200,
                "limit=10&token=deadbeef&offset=0");

        assertThat(recorded.queryString()).isEqualTo("limit=10&token=REDACTED&offset=0");
    }

    // ── Persistence failure isolation ────────────────────────────────────────

    @Test
    void auditServiceFailureDoesNotPropagateToRequest() throws Exception {
        org.mockito.Mockito.doThrow(new RuntimeException("simulated"))
                .when(auditService).record(org.mockito.ArgumentMatchers.any());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/sagas");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        // Must not throw
        filter.doFilter(request, response, new MockFilterChain());
    }

    // ── Header parsing edge cases ────────────────────────────────────────────

    @Test
    void basicAuthParserHandlesUsernameWithoutColon() {
        String header = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("alice".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(AdminAccessAuditFilter.parseBasicAuthUsername(header)).isNull();
    }

    @Test
    void basicAuthParserReturnsNullForNullHeader() {
        assertThat(AdminAccessAuditFilter.parseBasicAuthUsername(null)).isNull();
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private AdminAuditEntry runFilter(String method, String path, String authHeader,
                                      int responseStatus, String queryString)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (authHeader != null) {
            request.addHeader("Authorization", authHeader);
        }
        if (queryString != null) {
            request.setQueryString(queryString);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> ((HttpServletResponse) res).setStatus(responseStatus);

        filter.doFilter(request, response, chain);

        ArgumentCaptor<AdminAuditEntry> captor = ArgumentCaptor.forClass(AdminAuditEntry.class);
        verify(auditService).record(captor.capture());
        return captor.getValue();
    }

    /** Required so the FilterChain functional interface can throw checked exceptions. */
    @SuppressWarnings("unused")
    private static void noop(HttpServletRequest req, HttpServletResponse res) {}
}
