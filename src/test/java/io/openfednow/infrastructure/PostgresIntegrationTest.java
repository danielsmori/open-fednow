package io.openfednow.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for PostgreSQL schema correctness.
 *
 * <p>When the Spring context starts, Flyway automatically applies all
 * migrations to the Testcontainers PostgreSQL instance. These tests then
 * verify schema correctness against a real PostgreSQL engine — catching
 * PostgreSQL-specific behaviours that H2 (used in {@code FlywayMigrationTest})
 * does not fully replicate:
 *
 * <ul>
 *   <li>Partial indexes ({@code WHERE} clause) actually exist and are usable</li>
 *   <li>{@code BIGSERIAL} generates sequential IDs correctly</li>
 *   <li>{@code TIMESTAMPTZ} stores and retrieves with timezone information</li>
 *   <li>PostgreSQL enforces {@code CHECK} constraints identically to production</li>
 *   <li>Index existence is verifiable via {@code pg_indexes}</li>
 * </ul>
 */
class PostgresIntegrationTest extends AbstractInfrastructureIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    // --- Flyway ran successfully ---

    @Test
    void flywayAppliedAllFourMigrations() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE",
                Integer.class);
        assertThat(applied).isEqualTo(4);
    }

    // --- All tables exist ---

    @Test
    void allFourTablesExist() {
        List<String> tables = jdbc.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_type   = 'BASE TABLE'
                  AND table_name NOT LIKE 'flyway_%'
                ORDER BY table_name
                """,
                String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "shadow_ledger_transaction_log",
                "saga_state",
                "idempotency_keys",
                "reconciliation_run"
        );
    }

    // --- Partial indexes exist (PostgreSQL-specific; not reliably verified in H2) ---

    @Test
    void partialIndexOnUnconfirmedShadowLedgerRowsExists() {
        // idx_sltl_pending_confirm — WHERE core_confirmed = FALSE
        // This is the reconciliation replay queue index; must exist on real PostgreSQL
        boolean exists = indexExists("idx_sltl_pending_confirm");
        assertThat(exists)
                .as("Partial index idx_sltl_pending_confirm should exist")
                .isTrue();
    }

    @Test
    void partialIndexOnFailedReconciliationRunsExists() {
        // idx_recon_failed — WHERE successful = FALSE
        boolean exists = indexExists("idx_recon_failed");
        assertThat(exists)
                .as("Partial index idx_recon_failed should exist")
                .isTrue();
    }

    // --- BIGSERIAL auto-increment ---

    @Test
    void bigserialGeneratesSequentialIds() {
        // Use RETURNING to capture the assigned IDs directly, so this test is
        // independent of rows inserted by other test methods in the same transaction.
        Long id1 = jdbc.queryForObject(
                "INSERT INTO reconciliation_run (triggered_by) VALUES ('MANUAL') RETURNING id",
                Long.class);
        Long id2 = jdbc.queryForObject(
                "INSERT INTO reconciliation_run (triggered_by) VALUES ('MANUAL') RETURNING id",
                Long.class);

        assertThat(id1).isNotNull();
        assertThat(id2).isNotNull();
        assertThat(id2).isGreaterThan(id1);
    }

    // --- TIMESTAMPTZ timezone handling ---

    @Test
    void timestamptzPreservesTimezoneOffset() {
        OffsetDateTime utcPlusFive = OffsetDateTime.of(2024, 6, 15, 10, 30, 0, 0, ZoneOffset.ofHours(5));

        jdbc.update("""
                INSERT INTO reconciliation_run (started_at, triggered_by)
                VALUES (?, 'SCHEDULED')
                """, utcPlusFive);

        OffsetDateTime retrieved = jdbc.queryForObject(
                "SELECT started_at FROM reconciliation_run ORDER BY id DESC LIMIT 1",
                OffsetDateTime.class);

        assertThat(retrieved).isNotNull();
        // PostgreSQL normalizes TIMESTAMPTZ to UTC internally; the instant must match
        assertThat(retrieved.toInstant()).isEqualTo(utcPlusFive.toInstant());
    }

    // --- CHECK constraints on real PostgreSQL ---

    @Test
    void shadowLedgerTransactionLogRejectsInvalidType() {
        try {
            jdbc.execute("""
                    INSERT INTO shadow_ledger_transaction_log
                        (transaction_id, end_to_end_id, account_id, transaction_type,
                         amount, balance_before, balance_after, core_confirmed)
                    VALUES ('TXN-PG-BAD', 'E2E-PG-BAD', 'ACC-001', 'INVALID',
                            100.00, 50000.00, 49900.00, FALSE)
                    """);
            assertThat(false).as("Expected PostgreSQL CHECK constraint violation").isTrue();
        } catch (Exception e) {
            assertThat(e.getMessage().toLowerCase()).contains("check");
        }
    }

    @Test
    void idempotencyKeyRejectsRejectionWithoutReasonCode() {
        try {
            jdbc.execute("""
                    INSERT INTO idempotency_keys
                        (end_to_end_id, message_id, response_status, expires_at)
                    VALUES ('E2E-PG-RJCT', 'MSG-001', 'RJCT',
                            NOW() + INTERVAL '48 hours')
                    """);
            assertThat(false).as("Expected PostgreSQL CHECK constraint violation").isTrue();
        } catch (Exception e) {
            assertThat(e.getMessage().toLowerCase()).contains("check");
        }
    }

    @Test
    void reconciliationRunRejectsNegativeDiscrepancies() {
        try {
            jdbc.execute("""
                    INSERT INTO reconciliation_run
                        (transactions_replayed, discrepancies_detected, triggered_by)
                    VALUES (10, -1, 'SCHEDULED')
                    """);
            assertThat(false).as("Expected PostgreSQL CHECK constraint violation").isTrue();
        } catch (Exception e) {
            assertThat(e.getMessage().toLowerCase()).contains("check");
        }
    }

    // --- UNIQUE constraints ---

    @Test
    void sagaStateUniqueConstraintOnTransactionId() {
        jdbc.execute("""
                INSERT INTO saga_state (saga_id, transaction_id, end_to_end_id, state)
                VALUES ('SAGA-PG-001', 'TXN-PG-UNIQ', 'E2E-PG-001', 'INITIATED')
                """);

        try {
            jdbc.execute("""
                    INSERT INTO saga_state (saga_id, transaction_id, end_to_end_id, state)
                    VALUES ('SAGA-PG-002', 'TXN-PG-UNIQ', 'E2E-PG-002', 'INITIATED')
                    """);
            assertThat(false).as("Expected PostgreSQL UNIQUE constraint violation").isTrue();
        } catch (Exception e) {
            assertThat(e.getMessage().toLowerCase()).contains("unique");
        }
    }

    // --- Full insert round-trip (verifies column types work end-to-end) ---

    @Test
    void shadowLedgerTransactionLogFullInsertAndQuery() {
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, saga_id, core_confirmed, applied_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "TXN-PG-FULL", "E2E-PG-FULL", "ACC-CHECKING-001", "DEBIT",
                new BigDecimal("250.00"),
                new BigDecimal("50000.00"),
                new BigDecimal("49750.00"),
                "SAGA-PG-FULL",
                false,
                OffsetDateTime.now(ZoneOffset.UTC));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM shadow_ledger_transaction_log WHERE transaction_id = 'TXN-PG-FULL'");

        assertThat(row.get("end_to_end_id")).isEqualTo("E2E-PG-FULL");
        assertThat(row.get("account_id")).isEqualTo("ACC-CHECKING-001");
        assertThat(row.get("transaction_type")).isEqualTo("DEBIT");
        assertThat(((BigDecimal) row.get("amount"))).isEqualByComparingTo("250.00");
        assertThat(row.get("core_confirmed")).isEqualTo(false);
        assertThat(row.get("id")).isNotNull(); // BIGSERIAL assigned
    }

    // --- Helper ---

    private boolean indexExists(String indexName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname = ?",
                Integer.class,
                indexName);
        return count != null && count > 0;
    }
}
