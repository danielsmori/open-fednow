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
 * <p>H2's PostgreSQL mode ({@code MODE=PostgreSQL}) supports the SQL constructs
 * used in the migrations: {@code BIGSERIAL}, {@code TIMESTAMPTZ}, {@code NUMERIC},
 * and partial indexes with {@code WHERE} clauses.
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
    void exactlyFourMigrationsArePresent() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();

        assertThat(flyway.info().all()).hasSize(4);
    }

    // --- V1: shadow_ledger_transaction_log ---

    @Test
    void shadowLedgerTransactionLogTableExists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void shadowLedgerTransactionLogHasRequiredColumns() {
        // Insert a row to verify all NOT NULL columns and constraints are correct
        jdbc.execute("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES
                    ('TXN-001', 'E2E-001', 'ACC-001', 'DEBIT',
                     100.00, 50000.00, 49900.00, FALSE)
                """);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log", Integer.class);
        assertThat(count).isEqualTo(1);
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
            // Should not reach here
            assertThat(false).as("Expected constraint violation for invalid transaction_type").isTrue();
        } catch (Exception e) {
            assertThat(e.getMessage()).containsIgnoringCase("constraint");
        }
    }

    // --- V2: saga_state ---

    @Test
    void sagaStateTableExists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM saga_state", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void sagaStateTableAcceptsAllValidStates() {
        String[] states = {
            "INITIATED", "FUNDS_RESERVED", "CORE_SUBMITTED",
            "FEDNOW_CONFIRMED", "COMPLETED", "COMPENSATING", "FAILED"
        };

        for (int i = 0; i < states.length; i++) {
            jdbc.update("""
                    INSERT INTO saga_state (saga_id, transaction_id, end_to_end_id, state)
                    VALUES (?, ?, ?, ?)
                    """,
                    "SAGA-" + i, "TXN-SAGA-" + i, "E2E-SAGA-" + i, states[i]);
        }

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM saga_state", Integer.class);
        assertThat(count).isEqualTo(states.length);
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
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void idempotencyKeysTableAcceptsAllResponseStatuses() {
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

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys", Integer.class);
        assertThat(count).isEqualTo(3);
    }

    @Test
    void idempotencyKeysEnforcesReasonCodeOnRejection() {
        // A RJCT response without a reason code must be rejected by the constraint
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
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_run", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void reconciliationRunTableAcceptsScheduledAndManualTriggers() {
        jdbc.execute("""
                INSERT INTO reconciliation_run (transactions_replayed, triggered_by)
                VALUES (42, 'SCHEDULED')
                """);
        jdbc.execute("""
                INSERT INTO reconciliation_run (transactions_replayed, triggered_by)
                VALUES (5, 'MANUAL')
                """);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_run", Integer.class);
        assertThat(count).isEqualTo(2);
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
}
