package io.openfednow.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that all Flyway migrations apply cleanly to an H2 in-memory database
 * running in PostgreSQL-compatibility mode.
 *
 * <p>No Spring context is loaded. Flyway is configured programmatically, mirroring
 * the {@code spring.flyway} settings in {@code application.yml}. This keeps the
 * test fast, self-contained, and independent of Redis and RabbitMQ.
 *
 * <p>All test methods share a single H2 database created in {@code @BeforeAll}.
 * "Table exists" checks use {@code information_schema} rather than asserting a
 * zero row count, so they are not sensitive to the order in which JUnit runs the
 * other test methods (which insert rows).
 */
class FlywayMigrationTest {

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void runMigrations() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:flyway_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();

        flyway.migrate();

        jdbc = new JdbcTemplate(dataSource);
    }

    // --- All migrations applied successfully ---

    @Test
    void allMigrationsSucceeded() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();

        MigrationInfo[] migrations = flyway.info().all();
        assertThat(migrations).isNotEmpty();

        for (MigrationInfo info : migrations) {
            assertThat(info.getState())
                    .as("Migration %s should have succeeded", info.getVersion())
                    .isEqualTo(MigrationState.SUCCESS);
        }
    }

    @Test
    void exactlySevenMigrationsArePresent() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();

        assertThat(flyway.info().all()).hasSize(7);
    }

    // --- V1: shadow_ledger_transaction_log ---

    @Test
    void shadowLedgerTransactionLogTableExists() {
        assertThat(tableExists("shadow_ledger_transaction_log")).isTrue();
    }

    @Test
    void shadowLedgerTransactionLogHasRequiredColumns() {
        int before = rowCount("shadow_ledger_transaction_log");

        jdbc.execute("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES
                    ('TXN-001', 'E2E-001', 'ACC-001', 'DEBIT',
                     100.00, 50000.00, 49900.00, FALSE)
                """);

        assertThat(rowCount("shadow_ledger_transaction_log")).isEqualTo(before + 1);
    }

    @Test
    void shadowLedgerTransactionLogRejectsInvalidTransactionType() {
        try {
            jdbc.execute("""
                    INSERT INTO shadow_ledger_transaction_log
                        (transaction_id, end_to_end_id, account_id, transaction_type,
                         amount, balance_before, balance_after, core_confirmed)
                    VALUES
                        ('TXN-BAD', 'E2E-BAD', 'ACC-001', 'INVALID_TYPE',
                         100.00, 50000.00, 49900.00, FALSE)
                    """);
            assertThat(false).as("Expected constraint violation for invalid transaction_type").isTrue();
        } catch (Exception e) {
            assertThat(e.getMessage()).containsIgnoringCase("constraint");
        }
    }

    // --- V2: saga_state ---

    @Test
    void sagaStateTableExists() {
        assertThat(tableExists("saga_state")).isTrue();
    }

    @Test
    void sagaStateTableAcceptsAllValidStates() {
        String[] states = {
            "INITIATED", "FUNDS_RESERVED", "CORE_SUBMITTED",
            "FEDNOW_CONFIRMED", "COMPLETED", "COMPENSATING", "FAILED"
        };

        int before = rowCount("saga_state");

        for (int i = 0; i < states.length; i++) {
            jdbc.update("""
                    INSERT INTO saga_state (saga_id, transaction_id, end_to_end_id, state)
                    VALUES (?, ?, ?, ?)
                    """,
                    "SAGA-" + i, "TXN-SAGA-" + i, "E2E-SAGA-" + i, states[i]);
        }

        assertThat(rowCount("saga_state")).isEqualTo(before + states.length);
    }

    // --- V5: saga_state source_rail column ---

    @Test
    void sagaStateAcceptsBothFedNowAndRtpSourceRails() {
        int before = rowCount("saga_state");

        jdbc.update("""
                INSERT INTO saga_state (saga_id, transaction_id, end_to_end_id, state, source_rail)
                VALUES (?, ?, ?, ?, ?)
                """,
                "SAGA-RAIL-FED", "TXN-RAIL-FED", "E2E-RAIL-FED", "INITIATED", "FEDNOW");
        jdbc.update("""
                INSERT INTO saga_state (saga_id, transaction_id, end_to_end_id, state, source_rail)
                VALUES (?, ?, ?, ?, ?)
                """,
                "SAGA-RAIL-RTP", "TXN-RAIL-RTP", "E2E-RAIL-RTP", "INITIATED", "RTP");

        assertThat(rowCount("saga_state")).isEqualTo(before + 2);
    }

    @Test
    void sagaStateRejectsInvalidSourceRail() {
        try {
            jdbc.update("""
                    INSERT INTO saga_state (saga_id, transaction_id, end_to_end_id, state, source_rail)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    "SAGA-RAIL-BAD", "TXN-RAIL-BAD", "E2E-RAIL-BAD", "INITIATED", "ACH");
            assertThat(false).as("Expected constraint violation for invalid source_rail").isTrue();
        } catch (Exception e) {
            assertThat(e.getMessage()).containsIgnoringCase("constraint");
        }
    }

    @Test
    void sagaStateDefaultsSourceRailToFedNowWhenOmitted() {
        jdbc.update("""
                INSERT INTO saga_state (saga_id, transaction_id, end_to_end_id, state)
                VALUES (?, ?, ?, ?)
                """,
                "SAGA-RAIL-DEFAULT", "TXN-RAIL-DEFAULT", "E2E-RAIL-DEFAULT", "INITIATED");

        String rail = jdbc.queryForObject(
                "SELECT source_rail FROM saga_state WHERE saga_id = ?",
                String.class, "SAGA-RAIL-DEFAULT");
        assertThat(rail).isEqualTo("FEDNOW");
    }

    @Test
    void sagaStateEnforcesUniqueTransactionId() {
        jdbc.execute("""
                INSERT INTO saga_state (saga_id, transaction_id, end_to_end_id, state)
                VALUES ('SAGA-UNIQ-1', 'TXN-UNIQ', 'E2E-UNIQ-1', 'INITIATED')
                """);

        try {
            jdbc.execute("""
                    INSERT INTO saga_state (saga_id, transaction_id, end_to_end_id, state)
                    VALUES ('SAGA-UNIQ-2', 'TXN-UNIQ', 'E2E-UNIQ-2', 'INITIATED')
                    """);
            assertThat(false).as("Expected unique constraint violation on transaction_id").isTrue();
        } catch (Exception e) {
            assertThat(e.getMessage()).containsIgnoringCase("unique");
        }
    }

    // --- V3: idempotency_keys ---

    @Test
    void idempotencyKeysTableExists() {
        assertThat(tableExists("idempotency_keys")).isTrue();
    }

    @Test
    void idempotencyKeysTableAcceptsAllResponseStatuses() {
        int before = rowCount("idempotency_keys");

        jdbc.execute("""
                INSERT INTO idempotency_keys
                    (end_to_end_id, message_id, response_status, expires_at)
                VALUES ('E2E-IDEM-ACSC', 'MSG-001', 'ACSC', NOW() + INTERVAL '48' HOUR)
                """);
        jdbc.execute("""
                INSERT INTO idempotency_keys
                    (end_to_end_id, message_id, response_status, expires_at)
                VALUES ('E2E-IDEM-ACSP', 'MSG-002', 'ACSP', NOW() + INTERVAL '48' HOUR)
                """);
        jdbc.execute("""
                INSERT INTO idempotency_keys
                    (end_to_end_id, message_id, response_status, response_reason_code, expires_at)
                VALUES ('E2E-IDEM-RJCT', 'MSG-003', 'RJCT', 'AM04', NOW() + INTERVAL '48' HOUR)
                """);

        assertThat(rowCount("idempotency_keys")).isEqualTo(before + 3);
    }

    @Test
    void idempotencyKeysEnforcesReasonCodeOnRejection() {
        try {
            jdbc.execute("""
                    INSERT INTO idempotency_keys
                        (end_to_end_id, message_id, response_status, expires_at)
                    VALUES ('E2E-RJCT-NO-CODE', 'MSG-BAD', 'RJCT', NOW() + INTERVAL '48' HOUR)
                    """);
            assertThat(false).as("Expected constraint violation: RJCT without reason code").isTrue();
        } catch (Exception e) {
            assertThat(e.getMessage()).containsIgnoringCase("constraint");
        }
    }

    // --- V4: reconciliation_run ---

    @Test
    void reconciliationRunTableExists() {
        assertThat(tableExists("reconciliation_run")).isTrue();
    }

    @Test
    void reconciliationRunTableAcceptsScheduledAndManualTriggers() {
        int before = rowCount("reconciliation_run");

        jdbc.execute("""
                INSERT INTO reconciliation_run (transactions_replayed, triggered_by)
                VALUES (42, 'SCHEDULED')
                """);
        jdbc.execute("""
                INSERT INTO reconciliation_run (transactions_replayed, triggered_by)
                VALUES (5, 'MANUAL')
                """);

        assertThat(rowCount("reconciliation_run")).isEqualTo(before + 2);
    }

    @Test
    void reconciliationRunRejectsNegativeDiscrepancyCount() {
        try {
            jdbc.execute("""
                    INSERT INTO reconciliation_run
                        (transactions_replayed, discrepancies_detected, triggered_by)
                    VALUES (10, -1, 'SCHEDULED')
                    """);
            assertThat(false).as("Expected constraint violation for negative discrepancies_detected").isTrue();
        } catch (Exception e) {
            assertThat(e.getMessage()).containsIgnoringCase("constraint");
        }
    }

    // --- V6: admin_audit_log ---

    @Test
    void adminAuditLogTableExists() {
        assertThat(tableExists("admin_audit_log")).isTrue();
    }

    @Test
    void adminAuditLogAcceptsAllResultValues() {
        int before = rowCount("admin_audit_log");

        String[] results = {"GRANTED", "DENIED", "REJECTED", "ERROR"};
        for (int i = 0; i < results.length; i++) {
            jdbc.update("""
                    INSERT INTO admin_audit_log
                        (principal, http_method, request_path, result, status_code)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    "user-" + i, "GET", "/admin/test", results[i], 200);
        }

        assertThat(rowCount("admin_audit_log")).isEqualTo(before + results.length);
    }

    @Test
    void adminAuditLogRejectsInvalidResultValue() {
        try {
            jdbc.update("""
                    INSERT INTO admin_audit_log
                        (principal, http_method, request_path, result, status_code)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    "user-bad", "GET", "/admin/test", "INVALID", 200);
            assertThat(false).as("Expected constraint violation for invalid result").isTrue();
        } catch (Exception e) {
            assertThat(e.getMessage()).containsIgnoringCase("constraint");
        }
    }

    // --- V7: request_id correlation columns on saga_state + reconciliation_run ---

    @Test
    void sagaStateAcceptsRequestId() {
        jdbc.update("""
                INSERT INTO saga_state
                    (saga_id, transaction_id, end_to_end_id, state, source_rail, request_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                "SAGA-REQ-1", "TXN-REQ-1", "E2E-REQ-1", "INITIATED", "FEDNOW",
                "req-abc-123");

        String storedRequestId = jdbc.queryForObject(
                "SELECT request_id FROM saga_state WHERE saga_id = ?",
                String.class, "SAGA-REQ-1");
        assertThat(storedRequestId).isEqualTo("req-abc-123");
    }

    @Test
    void sagaStateAllowsNullRequestId() {
        // Scheduled sagas (SagaTimeoutMonitor, AvailabilityBridge) initiate outside
        // any HTTP request, so request_id must remain nullable.
        jdbc.update("""
                INSERT INTO saga_state
                    (saga_id, transaction_id, end_to_end_id, state, source_rail)
                VALUES (?, ?, ?, ?, ?)
                """,
                "SAGA-REQ-NULL", "TXN-REQ-NULL", "E2E-REQ-NULL", "INITIATED", "FEDNOW");

        String storedRequestId = jdbc.queryForObject(
                "SELECT request_id FROM saga_state WHERE saga_id = ?",
                String.class, "SAGA-REQ-NULL");
        assertThat(storedRequestId).isNull();
    }

    @Test
    void reconciliationRunAcceptsRequestId() {
        jdbc.update("""
                INSERT INTO reconciliation_run (triggered_by, request_id)
                VALUES ('MANUAL', ?)
                """,
                "req-recon-99");

        String storedRequestId = jdbc.queryForObject(
                "SELECT request_id FROM reconciliation_run WHERE request_id = ?",
                String.class, "req-recon-99");
        assertThat(storedRequestId).isEqualTo("req-recon-99");
    }

    // --- Helpers ---

    private boolean tableExists(String tableName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                Integer.class, tableName);
        return count != null && count > 0;
    }

    private int rowCount(String tableName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + tableName, Integer.class);
        return count != null ? count : 0;
    }
}
