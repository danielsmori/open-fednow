package io.openfednow.security.audit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link AdminAuditLogCleanupService} — audit item #15.
 *
 * <p>Runs against a real H2 database with the production Flyway migrations
 * applied so the DELETE statement is exercised exactly as it will run in
 * PostgreSQL. No Spring context — the service is plain enough to construct
 * directly.
 */
class AdminAuditLogCleanupServiceTest {

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void runMigrations() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:audit_cleanup_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM admin_audit_log");
    }

    // ── Construction ─────────────────────────────────────────────────────────

    @Test
    void zeroRetentionIsRejected() {
        assertThatThrownBy(() -> new AdminAuditLogCleanupService(jdbc, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retention-days");
    }

    @Test
    void negativeRetentionIsRejected() {
        assertThatThrownBy(() -> new AdminAuditLogCleanupService(jdbc, -10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configuredRetentionIsExposed() {
        AdminAuditLogCleanupService service = new AdminAuditLogCleanupService(jdbc, 90);
        assertThat(service.getRetentionDays()).isEqualTo(90);
    }

    // ── Sweep semantics ──────────────────────────────────────────────────────

    @Test
    void sweepWithNoExpiredRowsReturnsZero() {
        insertRow("alice", "GET", "/admin/sagas", Instant.now());

        int deleted = new AdminAuditLogCleanupService(jdbc, 30).sweepExpired();

        assertThat(deleted).isZero();
        assertThat(countRows()).isEqualTo(1);
    }

    @Test
    void sweepDeletesRowsOlderThanRetention() {
        // Two old rows, one recent
        insertRow("alice", "GET", "/admin/sagas",
                Instant.now().minusSeconds(40 * 86400L));   // 40 days old
        insertRow("bob",   "POST", "/admin/reconcile",
                Instant.now().minusSeconds(60 * 86400L));   // 60 days old
        insertRow("carol", "GET", "/admin/audit-log",
                Instant.now().minusSeconds(1 * 86400L));    // 1 day old

        int deleted = new AdminAuditLogCleanupService(jdbc, 30).sweepExpired();

        assertThat(deleted).isEqualTo(2);
        assertThat(countRows()).isEqualTo(1);
        // The surviving row is carol's
        String survivor = jdbc.queryForObject(
                "SELECT principal FROM admin_audit_log", String.class);
        assertThat(survivor).isEqualTo("carol");
    }

    @Test
    void sweepWithEmptyTableIsSafe() {
        int deleted = new AdminAuditLogCleanupService(jdbc, 30).sweepExpired();
        assertThat(deleted).isZero();
    }

    @Test
    void rowExactlyAtRetentionBoundaryIsRetained() {
        // A row whose timestamp is exactly retention_days old must survive — the
        // DELETE is "<", not "<=". Insert one second younger to be sure.
        insertRow("alice", "GET", "/admin/sagas",
                Instant.now().minusSeconds(30 * 86400L - 1));

        int deleted = new AdminAuditLogCleanupService(jdbc, 30).sweepExpired();

        assertThat(deleted).isZero();
        assertThat(countRows()).isEqualTo(1);
    }

    @Test
    void differentRetentionValueProducesDifferentResults() {
        // Same dataset, two different retention configs — exercises the boundary
        // logic in isolation. Same setup, run once with 1-day retention then
        // re-seed and run with 100-day retention.
        insertRow("alice", "GET", "/admin/sagas",
                Instant.now().minusSeconds(50 * 86400L));   // 50 days

        // 1-day retention deletes the row
        int deletedOneDay = new AdminAuditLogCleanupService(jdbc, 1).sweepExpired();
        assertThat(deletedOneDay).isEqualTo(1);

        // Re-insert and try with 100-day retention — same row, this time survives
        insertRow("alice2", "GET", "/admin/sagas",
                Instant.now().minusSeconds(50 * 86400L));
        int deletedHundred = new AdminAuditLogCleanupService(jdbc, 100).sweepExpired();
        assertThat(deletedHundred).isZero();
        assertThat(countRows()).isEqualTo(1);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void insertRow(String principal, String method, String path, Instant requestedAt) {
        jdbc.update("""
                INSERT INTO admin_audit_log
                    (requested_at, principal, http_method, request_path, result, status_code)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                Timestamp.from(requestedAt), principal, method, path, "GRANTED", 200);
    }

    private int countRows() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_log", Integer.class);
        return n == null ? 0 : n;
    }
}
