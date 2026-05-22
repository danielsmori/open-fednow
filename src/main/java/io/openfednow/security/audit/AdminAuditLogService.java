package io.openfednow.security.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Persists and queries {@link AdminAuditEntry} rows in {@code admin_audit_log}.
 *
 * <p>Writes are best-effort: a failure to persist must never block the actual
 * request from completing. The filter captures the audit entry after the
 * request has already finished, so an exception here surfaces only in logs.
 */
@Component
public class AdminAuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditLogService.class);

    private final JdbcTemplate jdbc;

    public AdminAuditLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a single audit entry. Catches and logs any exception so the
     * caller (a servlet filter on the response path) cannot fail the request.
     */
    public void record(AdminAuditEntry entry) {
        try {
            jdbc.update(
                    """
                    INSERT INTO admin_audit_log
                        (principal, http_method, request_path, query_string,
                         result, status_code, request_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    entry.principal(),
                    entry.httpMethod(),
                    entry.requestPath(),
                    entry.queryString(),
                    entry.result().name(),
                    entry.statusCode(),
                    entry.requestId());
        } catch (Exception e) {
            log.warn("Failed to persist admin audit entry principal={} path={} result={}",
                    entry.principal(), entry.requestPath(), entry.result(), e);
        }
    }

    /**
     * Returns the most recent audit entries in newest-first order.
     */
    public List<AdminAuditEntry> listRecent(int limit, int offset) {
        return jdbc.query(
                """
                SELECT id, requested_at, principal, http_method, request_path,
                       query_string, result, status_code, request_id
                FROM admin_audit_log
                ORDER BY requested_at DESC, id DESC
                LIMIT ? OFFSET ?
                """,
                this::mapEntry,
                limit, offset);
    }

    /**
     * Looks up a single audit entry by primary key.
     */
    public Optional<AdminAuditEntry> findById(long id) {
        return jdbc.query(
                """
                SELECT id, requested_at, principal, http_method, request_path,
                       query_string, result, status_code, request_id
                FROM admin_audit_log
                WHERE id = ?
                """,
                this::mapEntry,
                id).stream().findFirst();
    }

    private AdminAuditEntry mapEntry(ResultSet rs, int rowNum) throws SQLException {
        Timestamp requestedAt = rs.getTimestamp("requested_at");
        return new AdminAuditEntry(
                rs.getLong("id"),
                requestedAt != null ? requestedAt.toInstant() : null,
                rs.getString("principal"),
                rs.getString("http_method"),
                rs.getString("request_path"),
                rs.getString("query_string"),
                AuditResult.valueOf(rs.getString("result")),
                rs.getInt("status_code"),
                rs.getString("request_id"));
    }
}
