package io.openfednow.security.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Layer 1 — Sweeps expired rows from {@code admin_audit_log}.
 *
 * <p>{@link AdminAccessAuditFilter} writes one row per {@code /admin/**} request
 * — both granted and denied. Over time, healthcheck-hitting-admin-endpoint
 * activity and ordinary operator use accumulate. This service is the
 * scheduled retention sweep that keeps the table bounded.
 *
 * <p>Configurable knobs:
 * <ul>
 *   <li>{@code openfednow.admin-audit.retention-days} — how long an audit row
 *       is kept. Default 365 days, sufficient for typical compliance review
 *       windows. Reduce for shorter regulatory requirements; never set to
 *       zero (rows would be deleted before any read could see them).</li>
 *   <li>{@code openfednow.admin-audit.cleanup-interval-hours} — sweep cadence.
 *       Default 24h. The query is supported by {@code idx_admin_audit_requested_at}
 *       so cost scales with the number of rows actually deleted, not the
 *       table size.</li>
 * </ul>
 *
 * <p>The threshold is computed on the JVM side rather than via SQL
 * {@code INTERVAL} so the query stays portable across H2 (used in
 * integration tests) and PostgreSQL (production).
 */
@Component
public class AdminAuditLogCleanupService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditLogCleanupService.class);

    private final JdbcTemplate jdbc;
    private final int retentionDays;

    public AdminAuditLogCleanupService(
            JdbcTemplate jdbc,
            @Value("${openfednow.admin-audit.retention-days:365}") int retentionDays) {
        if (retentionDays <= 0) {
            throw new IllegalArgumentException(
                    "openfednow.admin-audit.retention-days must be positive (got "
                            + retentionDays + ")");
        }
        this.jdbc = jdbc;
        this.retentionDays = retentionDays;
    }

    int getRetentionDays() {
        return retentionDays;
    }

    /**
     * Deletes audit rows whose {@code requested_at} is older than the configured retention window.
     *
     * <p>The first sweep waits one full interval after startup so it does not collide
     * with the saga-recovery work that runs on {@code ApplicationReadyEvent}.
     */
    @Scheduled(
            fixedDelayString = "#{${openfednow.admin-audit.cleanup-interval-hours:24} * 60 * 60 * 1000}",
            initialDelayString = "#{${openfednow.admin-audit.cleanup-interval-hours:24} * 60 * 60 * 1000}"
    )
    public int sweepExpired() {
        Instant threshold = Instant.now().minusSeconds(retentionDays * 86_400L);
        int deleted = jdbc.update(
                "DELETE FROM admin_audit_log WHERE requested_at < ?",
                java.sql.Timestamp.from(threshold));
        if (deleted > 0) {
            log.info("Admin audit log cleanup: deleted {} row(s) older than {} days",
                    deleted, retentionDays);
        } else {
            log.debug("Admin audit log cleanup: no expired rows");
        }
        return deleted;
    }
}
