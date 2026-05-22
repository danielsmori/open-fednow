package io.openfednow.security.audit;

import java.time.Instant;

/**
 * Read-only projection of a row in {@code admin_audit_log}.
 *
 * @param id           surrogate primary key
 * @param requestedAt  when the access attempt was received
 * @param principal    authenticated username on success, or attempted username
 *                     (or {@code anonymous}) on failure
 * @param httpMethod   HTTP verb of the access attempt
 * @param requestPath  request URI under {@code /admin/**}
 * @param queryString  optional URL query string; null when absent
 * @param result       {@link AuditResult} — GRANTED / DENIED / REJECTED / ERROR
 * @param statusCode   final HTTP status returned to the client
 * @param requestId    correlation ID from {@code CorrelationFilter}; null only when the
 *                     filter chain failed before correlation was populated
 */
public record AdminAuditEntry(
        Long id,
        Instant requestedAt,
        String principal,
        String httpMethod,
        String requestPath,
        String queryString,
        AuditResult result,
        int statusCode,
        String requestId
) {

    /**
     * Builds an entry for insertion. {@code id} is assigned by the database
     * and {@code requestedAt} defaults to {@code NOW()} in PostgreSQL — this
     * constructor leaves both unset.
     */
    public static AdminAuditEntry of(
            String principal,
            String httpMethod,
            String requestPath,
            String queryString,
            AuditResult result,
            int statusCode,
            String requestId
    ) {
        return new AdminAuditEntry(
                null, null, principal, httpMethod, requestPath,
                queryString, result, statusCode, requestId);
    }
}
