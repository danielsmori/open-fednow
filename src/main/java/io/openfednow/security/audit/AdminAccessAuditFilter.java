package io.openfednow.security.audit;

import io.openfednow.gateway.CorrelationFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Records every access attempt to {@code /admin/**} in {@link AdminAuditLogService}.
 *
 * <p>The filter runs inside the Spring Security chain — wired via
 * {@code HttpSecurity.addFilterBefore(...)} so it sits in front of the
 * authentication filter and therefore sees every request regardless of
 * authorization outcome. The audit row is written after the downstream chain
 * has finished, so the recorded status code is the final response status the
 * client will see.
 *
 * <h2>Principal resolution</h2>
 * Spring Security clears the {@link SecurityContextHolder} on its way out, so
 * by the time this filter inspects the response the authenticated principal
 * may already be gone. The filter therefore tries two sources, in order:
 * <ol>
 *   <li>{@link SecurityContextHolder#getContext()} — populated for successful
 *       requests reaching the handler.</li>
 *   <li>The {@code Authorization: Basic} header, base64-decoded — used as the
 *       fallback for failed authentication attempts. Storing the *attempted*
 *       username on 401s is what makes the table useful for detecting
 *       credential probing.</li>
 * </ol>
 *
 * <h2>Failure isolation</h2>
 * A persistence failure must never fail the request. {@link AdminAuditLogService}
 * catches its own exceptions; any throwable from this filter's audit-building
 * code is also caught here, so the response path remains intact.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AdminAccessAuditFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAccessAuditFilter.class);

    static final String ADMIN_PATH_PREFIX = "/admin";
    static final String ANONYMOUS_PRINCIPAL = "anonymous";

    private final AdminAuditLogService auditService;

    public AdminAccessAuditFilter(AdminAuditLogService auditService) {
        this.auditService = auditService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(ADMIN_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String headerPrincipal = parseBasicAuthUsername(request.getHeader("Authorization"));

        try {
            filterChain.doFilter(request, response);
        } finally {
            try {
                int status = response.getStatus();
                AuditResult result = AuditResult.fromStatus(status);
                String principal = resolvePrincipal(headerPrincipal);

                AdminAuditEntry entry = AdminAuditEntry.of(
                        principal,
                        request.getMethod(),
                        request.getRequestURI(),
                        request.getQueryString(),
                        result,
                        status,
                        MDC.get(CorrelationFilter.MDC_REQUEST_ID));

                auditService.record(entry);

                if (log.isInfoEnabled()) {
                    log.info("admin-access principal={} method={} path={} status={} result={}",
                            principal, request.getMethod(), request.getRequestURI(), status, result);
                }
            } catch (Exception e) {
                log.warn("Admin access audit filter failed for {} {}",
                        request.getMethod(), request.getRequestURI(), e);
            }
        }
    }

    /**
     * Determines the principal to record. Prefers the authenticated identity
     * from the security context (most reliable on success); falls back to the
     * username parsed from the Authorization header (so failed-auth attempts
     * still capture the attempted user); finally returns {@code anonymous}.
     */
    private String resolvePrincipal(String headerPrincipal) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            String name = auth.getName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        if (headerPrincipal != null && !headerPrincipal.isBlank()) {
            return headerPrincipal;
        }
        return ANONYMOUS_PRINCIPAL;
    }

    /**
     * Extracts the username from an {@code Authorization: Basic ...} header
     * without validating the password. Returns {@code null} for any other
     * scheme, malformed input, or missing header.
     */
    static String parseBasicAuthUsername(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Basic ")) {
            return null;
        }
        try {
            String encoded = authorizationHeader.substring("Basic ".length()).trim();
            byte[] decoded = Base64.getDecoder().decode(encoded);
            String credentials = new String(decoded, StandardCharsets.UTF_8);
            int colon = credentials.indexOf(':');
            if (colon < 0) {
                return null;
            }
            String user = credentials.substring(0, colon);
            return user.isBlank() ? null : user;
        } catch (IllegalArgumentException e) {
            // Malformed base64 — treat as anonymous
            return null;
        }
    }
}
