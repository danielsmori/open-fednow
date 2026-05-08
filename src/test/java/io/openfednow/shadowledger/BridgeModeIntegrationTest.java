package io.openfednow.shadowledger;

import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for bridge mode and maintenance window recovery — issue #13.
 *
 * <p>Tests the full maintenance-window cycle:
 * <ol>
 *   <li>Core goes offline (bridge mode active)</li>
 *   <li>Inbound payments are queued to RabbitMQ via {@link AvailabilityBridge}</li>
 *   <li>Core returns online</li>
 *   <li>{@link ReconciliationService#reconcile()} confirms all pending entries and
 *       creates an audit record in {@code reconciliation_run}</li>
 *   <li>Balance discrepancies are detected and corrected</li>
 * </ol>
 *
 * <p>The Sandbox adapter is active by default ({@code openfednow.adapter=sandbox}).
 * Its {@code getAvailableBalance()} always returns {@code 50000.00} for non-LOWBAL_
 * accounts — this is the "authoritative core balance" for reconciliation purposes.
 */
class BridgeModeIntegrationTest extends AbstractInfrastructureIntegrationTest {

    @Autowired private AvailabilityBridge availabilityBridge;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private ShadowLedger shadowLedger;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private AmqpAdmin amqpAdmin;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    void cleanup() {
        ((RabbitAdmin) amqpAdmin).purgeQueue(RabbitMqConfig.MAINTENANCE_QUEUE, false);
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
        jdbc.update("DELETE FROM reconciliation_run");
        redis.delete(redis.keys("balance:*"));
    }

    // ── Bridge mode detection ─────────────────────────────────────────────────

    @Test
    void sandboxAdapterReportsCoreAvailableByDefault() {
        // Default sandbox configuration has core-available=true
        assertThat(availabilityBridge.isInBridgeMode()).isFalse();
    }

    // ── Transaction queuing ───────────────────────────────────────────────────

    @Test
    void queueForCoreProcessingPublishesMessageToRabbitMQ() {
        availabilityBridge.queueForCoreProcessing(
                "E2E-BRIDGE-001",
                "{\"endToEndId\":\"E2E-BRIDGE-001\",\"amount\":\"250.00\"}");

        Object received = rabbitTemplate.receiveAndConvert(RabbitMqConfig.MAINTENANCE_QUEUE, 3_000);

        assertThat(received).isNotNull();
        assertThat(new String((byte[]) received, StandardCharsets.UTF_8)).contains("E2E-BRIDGE-001");
    }

    @Test
    void multipleQueuedTransactionsAccumulateInQueue() {
        List<String> transactionIds = List.of("E2E-Q-001", "E2E-Q-002", "E2E-Q-003");

        for (String id : transactionIds) {
            availabilityBridge.queueForCoreProcessing(id, "{\"id\":\"" + id + "\"}");
        }

        int consumed = 0;
        while (rabbitTemplate.receiveAndConvert(RabbitMqConfig.MAINTENANCE_QUEUE, 1_000) != null) {
            consumed++;
        }
        assertThat(consumed).isEqualTo(transactionIds.size());
    }

    // ── Reconciliation: clean run ─────────────────────────────────────────────

    @Test
    void reconcileMarksUnconfirmedEntriesAsConfirmed() {
        // Seed Redis balance equal to what the Sandbox core returns (50000.00)
        redis.opsForValue().set("balance:ACC-RECON-001", "5000000"); // 50000.00 in cents

        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES ('TXN-RECON-001', 'E2E-RECON-001', 'ACC-RECON-001', 'CREDIT',
                        100.00, 49900.00, 50000.00, FALSE)
                """);

        ReconciliationService.ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.transactionsReplayed()).isGreaterThanOrEqualTo(1);
        assertThat(report.reconciliationSuccessful()).isTrue();

        Boolean confirmed = jdbc.queryForObject(
                "SELECT core_confirmed FROM shadow_ledger_transaction_log " +
                "WHERE transaction_id = 'TXN-RECON-001'",
                Boolean.class);
        assertThat(confirmed).isTrue();
    }

    @Test
    void reconcileCreatesAuditRecordInReconciliationRunTable() {
        redis.opsForValue().set("balance:ACC-AUDIT-001", "5000000");

        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES ('TXN-AUDIT-001', 'E2E-AUDIT-001', 'ACC-AUDIT-001', 'DEBIT',
                        50.00, 50000.00, 49950.00, FALSE)
                """);

        reconciliationService.reconcile();

        Integer runCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_run", Integer.class);
        assertThat(runCount).isEqualTo(1);

        Boolean successful = jdbc.queryForObject(
                "SELECT successful FROM reconciliation_run ORDER BY id DESC LIMIT 1",
                Boolean.class);
        assertThat(successful).isTrue();
    }

    @Test
    void reconcileWithNoUnconfirmedEntriesSucceedsWithZeroReplayed() {
        ReconciliationService.ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.transactionsReplayed()).isEqualTo(0);
        assertThat(report.discrepanciesDetected()).isEqualTo(0);
        assertThat(report.reconciliationSuccessful()).isTrue();
    }

    // ── Reconciliation: discrepancy detection ─────────────────────────────────

    @Test
    void reconcileDetectsAndCorrectsShadowLedgerDiscrepancy() {
        // Shadow balance is $49000 but Sandbox core returns $50000 → discrepancy of $1000
        redis.opsForValue().set("balance:ACC-DISC-001", "4900000"); // 49000.00 in cents

        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES ('TXN-DISC-001', 'E2E-DISC-001', 'ACC-DISC-001', 'DEBIT',
                        1000.00, 50000.00, 49000.00, FALSE)
                """);

        ReconciliationService.ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.discrepanciesDetected()).isGreaterThan(0);
        assertThat(report.reconciliationSuccessful()).isFalse();

        // Shadow Ledger balance must be corrected to match the core (50000.00 = 5000000 cents)
        String correctedBalance = redis.opsForValue().get("balance:ACC-DISC-001");
        assertThat(correctedBalance).isEqualTo("5000000");
    }

    @Test
    void reconcileDiscrepancyIsRecordedInAuditTable() {
        redis.opsForValue().set("balance:ACC-DISC-AUDIT-001", "4800000"); // 48000.00 cents

        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES ('TXN-DISC-AUDIT-001', 'E2E-DISC-AUDIT-001', 'ACC-DISC-AUDIT-001',
                        'DEBIT', 2000.00, 50000.00, 48000.00, FALSE)
                """);

        reconciliationService.reconcile();

        Integer discrepancies = jdbc.queryForObject(
                "SELECT discrepancies_detected FROM reconciliation_run ORDER BY id DESC LIMIT 1",
                Integer.class);
        assertThat(discrepancies).isGreaterThan(0);
    }

    // ── Targeted replay ───────────────────────────────────────────────────────

    @Test
    void replayTransactionsMarksSpecificEntriesConfirmed() {
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES ('TXN-REPLAY-001', 'E2E-REPLAY-001', 'ACC-REPLAY-001', 'CREDIT',
                        200.00, 49800.00, 50000.00, FALSE)
                """);

        reconciliationService.replayTransactions(List.of("TXN-REPLAY-001"));

        Boolean confirmed = jdbc.queryForObject(
                "SELECT core_confirmed FROM shadow_ledger_transaction_log " +
                "WHERE transaction_id = 'TXN-REPLAY-001'",
                Boolean.class);
        assertThat(confirmed).isTrue();
    }

    @Test
    void replayDoesNotAffectAlreadyConfirmedEntries() {
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES ('TXN-ALREADY-CONFIRMED', 'E2E-ALREADY', 'ACC-REPLAY-002', 'DEBIT',
                        50.00, 500.00, 450.00, TRUE)
                """);

        // Must not throw
        reconciliationService.replayTransactions(List.of("TXN-ALREADY-CONFIRMED"));

        Boolean confirmed = jdbc.queryForObject(
                "SELECT core_confirmed FROM shadow_ledger_transaction_log " +
                "WHERE transaction_id = 'TXN-ALREADY-CONFIRMED'",
                Boolean.class);
        assertThat(confirmed).isTrue();
    }

    @Test
    void replayTransactionsWithEmptyListIsNoOp() {
        // Must not throw
        reconciliationService.replayTransactions(List.of());
    }

    // ── Full maintenance-window cycle ─────────────────────────────────────────

    @Test
    void fullCycle_queue_then_reconcile() {
        // Simulate bridge mode: queue a transaction
        availabilityBridge.queueForCoreProcessing(
                "E2E-CYCLE-001",
                "{\"endToEndId\":\"E2E-CYCLE-001\",\"amount\":\"300.00\"}");

        // Verify message is in queue
        Object queued = rabbitTemplate.receiveAndConvert(RabbitMqConfig.MAINTENANCE_QUEUE, 3_000);
        assertThat(queued).isNotNull();

        // Simulate the core processing and inserting the shadow ledger entry
        redis.opsForValue().set("balance:ACC-CYCLE-001", "5000000");
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES ('TXN-CYCLE-001', 'E2E-CYCLE-001', 'ACC-CYCLE-001', 'CREDIT',
                        300.00, 49700.00, 50000.00, FALSE)
                """);

        // Core returns online — run reconciliation
        ReconciliationService.ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.reconciliationSuccessful()).isTrue();
        assertThat(report.transactionsReplayed()).isGreaterThanOrEqualTo(1);
    }
}
