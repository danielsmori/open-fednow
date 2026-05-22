package io.openfednow.shadowledger;

import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the {@link ReconciliationService} query methods that
 * back the admin audit endpoints — issue #41.
 *
 * <p>Exercises the SQL against real PostgreSQL via Testcontainers. The
 * MockMvc-level coverage in {@link io.openfednow.gateway.AdminControllerTest}
 * covers the controller-to-service contract; this class verifies the
 * service-to-database contract.
 */
class ReconciliationQueryIntegrationTest extends AbstractInfrastructureIntegrationTest {

    @Autowired private ReconciliationService reconciliationService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM reconciliation_run");
    }

    // ── listRecentRuns ────────────────────────────────────────────────────────

    @Test
    void listRecentRunsReturnsRowsNewestFirst() {
        long olderId = insertRun("2026-05-18T10:00:00Z", true, 5, 0, "Clean", "SCHEDULED");
        long newerId = insertRun("2026-05-19T10:00:00Z", true, 7, 0, "Clean", "MANUAL");

        List<ReconciliationRunSummary> runs = reconciliationService.listRecentRuns(10, 0);

        assertThat(runs).extracting(ReconciliationRunSummary::id)
                .containsExactly(newerId, olderId);
    }

    @Test
    void listRecentRunsHonorsLimit() {
        for (int i = 1; i <= 5; i++) {
            insertRun("2026-05-1" + i + "T10:00:00Z", true, i, 0, "Run " + i, "SCHEDULED");
        }

        List<ReconciliationRunSummary> runs = reconciliationService.listRecentRuns(2, 0);

        assertThat(runs).hasSize(2);
    }

    @Test
    void listRecentRunsHonorsOffset() {
        long r1 = insertRun("2026-05-11T10:00:00Z", true, 1, 0, "Run 1", "SCHEDULED");
        long r2 = insertRun("2026-05-12T10:00:00Z", true, 2, 0, "Run 2", "SCHEDULED");
        long r3 = insertRun("2026-05-13T10:00:00Z", true, 3, 0, "Run 3", "SCHEDULED");

        // Skip the newest row (r3) and take the next one (r2)
        List<ReconciliationRunSummary> page = reconciliationService.listRecentRuns(1, 1);

        assertThat(page).hasSize(1);
        assertThat(page.get(0).id()).isEqualTo(r2);
        assertThat(r1).isNotEqualTo(r3); // sanity: the three ids are distinct
    }

    @Test
    void listRecentRunsExposesAllProjectionFields() {
        long id = insertRun("2026-05-19T10:00:00Z", false, 9, 2,
                "Two discrepancies", "MANUAL");

        ReconciliationRunSummary row = reconciliationService.listRecentRuns(1, 0).get(0);

        assertThat(row.id()).isEqualTo(id);
        assertThat(row.transactionsReplayed()).isEqualTo(9);
        assertThat(row.discrepanciesDetected()).isEqualTo(2);
        assertThat(row.successful()).isFalse();
        assertThat(row.summary()).isEqualTo("Two discrepancies");
        assertThat(row.triggeredBy()).isEqualTo("MANUAL");
        assertThat(row.startedAt()).isNotNull();
        assertThat(row.completedAt()).isNotNull();
        assertThat(row.inProgress()).isFalse();
    }

    // ── findRunById ───────────────────────────────────────────────────────────

    @Test
    void findRunByIdReturnsMatchingRow() {
        long id = insertRun("2026-05-19T10:00:00Z", true, 4, 0, "Clean", "SCHEDULED");

        Optional<ReconciliationRunSummary> found = reconciliationService.findRunById(id);

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(id);
    }

    @Test
    void findRunByIdReturnsEmptyForUnknownId() {
        assertThat(reconciliationService.findRunById(999_999L)).isEmpty();
    }

    @Test
    void findRunByIdSurfacesInProgressRunsWithNullCompletedAt() {
        long id = insertInProgressRun("2026-05-19T11:00:00Z", "MANUAL");

        ReconciliationRunSummary row = reconciliationService.findRunById(id).orElseThrow();

        assertThat(row.completedAt()).isNull();
        assertThat(row.successful()).isNull();
        assertThat(row.inProgress()).isTrue();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private long insertRun(String startedAt, boolean successful, int replayed,
                           int discrepancies, String summary, String triggeredBy) {
        jdbc.update("""
                INSERT INTO reconciliation_run
                    (started_at, completed_at, transactions_replayed,
                     discrepancies_detected, successful, summary, triggered_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                java.sql.Timestamp.from(java.time.Instant.parse(startedAt)),
                java.sql.Timestamp.from(java.time.Instant.parse(startedAt).plusSeconds(5)),
                replayed, discrepancies, successful, summary, triggeredBy);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM reconciliation_run", Long.class);
    }

    private long insertInProgressRun(String startedAt, String triggeredBy) {
        jdbc.update("""
                INSERT INTO reconciliation_run
                    (started_at, transactions_replayed, discrepancies_detected, triggered_by)
                VALUES (?, 0, 0, ?)
                """,
                java.sql.Timestamp.from(java.time.Instant.parse(startedAt)),
                triggeredBy);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM reconciliation_run", Long.class);
    }
}
