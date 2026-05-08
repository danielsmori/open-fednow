package io.openfednow.shadowledger;

import com.rabbitmq.client.GetResponse;
import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests for transaction replay ordering and poison message handling — issue #14.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>{@link AvailabilityBridge#queueForCoreProcessing} publishes messages with
 *       the transactionId as the AMQP message-id property, in publish order</li>
 *   <li>{@link ReconciliationService#replayTransactions} confirms targeted entries
 *       without affecting others</li>
 *   <li>A poison message in the queue is dead-lettered without blocking valid
 *       messages that follow it</li>
 * </ul>
 *
 * <p>The DLQ topology is declared by {@link RabbitMqConfig} and tested at a lower
 * level in {@code RabbitMqDlqTest}. This class tests the same behaviour at the
 * service layer.
 */
class ReplayOrderingIntegrationTest extends AbstractInfrastructureIntegrationTest {

    @Autowired private AvailabilityBridge availabilityBridge;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private AmqpAdmin amqpAdmin;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    void cleanup() {
        ((RabbitAdmin) amqpAdmin).purgeQueue(RabbitMqConfig.MAINTENANCE_QUEUE, false);
        ((RabbitAdmin) amqpAdmin).purgeQueue(RabbitMqConfig.MAINTENANCE_DLQ, false);
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
        jdbc.update("DELETE FROM reconciliation_run");
        redis.delete(redis.keys("balance:*"));
    }

    // ── Message ID propagation ────────────────────────────────────────────────

    @Test
    void queueForCoreProcessingSetsTransactionIdAsMessageId() {
        availabilityBridge.queueForCoreProcessing("E2E-ID-PROP-001", "{\"test\":true}");

        Message received = rabbitTemplate.receive(RabbitMqConfig.MAINTENANCE_QUEUE, 3_000);

        assertThat(received).isNotNull();
        assertThat(received.getMessageProperties().getMessageId()).isEqualTo("E2E-ID-PROP-001");
    }

    @Test
    void queueForCoreProcessingSetsJsonContentType() {
        availabilityBridge.queueForCoreProcessing("E2E-CT-001", "{\"amount\":\"100.00\"}");

        Message received = rabbitTemplate.receive(RabbitMqConfig.MAINTENANCE_QUEUE, 3_000);

        assertThat(received).isNotNull();
        assertThat(received.getMessageProperties().getContentType()).isEqualTo("application/json");
    }

    // ── FIFO ordering ─────────────────────────────────────────────────────────

    @Test
    void transactionsAreReceivedInPublishOrder() {
        List<String> transactionIds = List.of(
                "E2E-ORDER-001", "E2E-ORDER-002", "E2E-ORDER-003",
                "E2E-ORDER-004", "E2E-ORDER-005");

        for (String id : transactionIds) {
            availabilityBridge.queueForCoreProcessing(id, "{\"id\":\"" + id + "\"}");
        }

        List<String> receivedIds = new ArrayList<>();
        for (int i = 0; i < transactionIds.size(); i++) {
            Message msg = rabbitTemplate.receive(RabbitMqConfig.MAINTENANCE_QUEUE, 3_000);
            if (msg != null) {
                receivedIds.add(msg.getMessageProperties().getMessageId());
            }
        }

        assertThat(receivedIds).containsExactlyElementsOf(transactionIds);
    }

    // ── Targeted replay confirmation ──────────────────────────────────────────

    @Test
    void replayTransactionsConfirmsAllListedEntries() {
        List<String> txnIds = List.of("TXN-REPLAY-A", "TXN-REPLAY-B", "TXN-REPLAY-C");
        for (String txnId : txnIds) {
            jdbc.update("""
                    INSERT INTO shadow_ledger_transaction_log
                        (transaction_id, end_to_end_id, account_id, transaction_type,
                         amount, balance_before, balance_after, core_confirmed)
                    VALUES (?, ?, 'ACC-ORDER-001', 'CREDIT', 100.00, 49900.00, 50000.00, FALSE)
                    """, txnId, "E2E-" + txnId);
        }

        reconciliationService.replayTransactions(txnIds);

        Integer confirmed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shadow_ledger_transaction_log " +
                "WHERE transaction_id IN ('TXN-REPLAY-A','TXN-REPLAY-B','TXN-REPLAY-C') " +
                "AND core_confirmed = TRUE",
                Integer.class);
        assertThat(confirmed).isEqualTo(3);
    }

    @Test
    void replayTransactionsDoesNotConfirmUnlistedEntries() {
        // Insert two entries but only replay one
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES ('TXN-LISTED', 'E2E-LISTED', 'ACC-PARTIAL-001', 'CREDIT',
                        50.00, 49950.00, 50000.00, FALSE),
                       ('TXN-UNLISTED', 'E2E-UNLISTED', 'ACC-PARTIAL-001', 'CREDIT',
                        50.00, 49900.00, 49950.00, FALSE)
                """);

        reconciliationService.replayTransactions(List.of("TXN-LISTED"));

        Boolean listedConfirmed = jdbc.queryForObject(
                "SELECT core_confirmed FROM shadow_ledger_transaction_log " +
                "WHERE transaction_id = 'TXN-LISTED'",
                Boolean.class);
        Boolean unlistedConfirmed = jdbc.queryForObject(
                "SELECT core_confirmed FROM shadow_ledger_transaction_log " +
                "WHERE transaction_id = 'TXN-UNLISTED'",
                Boolean.class);

        assertThat(listedConfirmed).isTrue();
        assertThat(unlistedConfirmed).isFalse();
    }

    @Test
    void replayWithEmptyListIsNoOp() {
        assertThatCode(() -> reconciliationService.replayTransactions(List.of()))
                .doesNotThrowAnyException();
    }

    // ── Poison message isolation ──────────────────────────────────────────────

    @Test
    void poisonMessageIsDeadLetteredWithoutBlockingFollowingMessages() {
        // Publish: valid → poison → valid
        rabbitTemplate.convertAndSend(RabbitMqConfig.MAINTENANCE_QUEUE, "TXN-BEFORE-POISON");
        rabbitTemplate.convertAndSend(RabbitMqConfig.MAINTENANCE_QUEUE, "POISON-UNPARSEABLE");
        rabbitTemplate.convertAndSend(RabbitMqConfig.MAINTENANCE_QUEUE, "TXN-AFTER-POISON");

        // Ack the first valid message
        rabbitTemplate.execute(channel -> {
            GetResponse first = channel.basicGet(RabbitMqConfig.MAINTENANCE_QUEUE, false);
            assertThat(first).isNotNull();
            assertThat(new String(first.getBody())).isEqualTo("TXN-BEFORE-POISON");
            channel.basicAck(first.getEnvelope().getDeliveryTag(), false);
            return null;
        });

        // Simulate consumer nacking the poison message without requeue
        rabbitTemplate.execute(channel -> {
            GetResponse poison = channel.basicGet(RabbitMqConfig.MAINTENANCE_QUEUE, false);
            assertThat(poison).isNotNull();
            channel.basicNack(poison.getEnvelope().getDeliveryTag(), false, /* requeue= */ false);
            return null;
        });

        // The valid message after the poison must still be consumable
        Object remaining = rabbitTemplate.receiveAndConvert(RabbitMqConfig.MAINTENANCE_QUEUE, 3_000);
        assertThat(remaining).isEqualTo("TXN-AFTER-POISON");

        // Poison message must be in the DLQ, not lost
        Object dlqMessage = rabbitTemplate.receiveAndConvert(RabbitMqConfig.MAINTENANCE_DLQ, 3_000);
        assertThat(dlqMessage).isEqualTo("POISON-UNPARSEABLE");
    }

    @Test
    void multiplePoisonMessagesAccumulateInDlqWithoutBlockingMainQueue() {
        // Interleave valid and poison messages
        rabbitTemplate.convertAndSend(RabbitMqConfig.MAINTENANCE_QUEUE, "TXN-VALID-1");
        rabbitTemplate.convertAndSend(RabbitMqConfig.MAINTENANCE_QUEUE, "POISON-1");
        rabbitTemplate.convertAndSend(RabbitMqConfig.MAINTENANCE_QUEUE, "TXN-VALID-2");
        rabbitTemplate.convertAndSend(RabbitMqConfig.MAINTENANCE_QUEUE, "POISON-2");
        rabbitTemplate.convertAndSend(RabbitMqConfig.MAINTENANCE_QUEUE, "TXN-VALID-3");

        // Process all 5 messages: ack valid, nack poison
        rabbitTemplate.execute(channel -> {
            for (int i = 0; i < 5; i++) {
                GetResponse msg = channel.basicGet(RabbitMqConfig.MAINTENANCE_QUEUE, false);
                if (msg == null) break;
                String body = new String(msg.getBody());
                if (body.startsWith("POISON")) {
                    channel.basicNack(msg.getEnvelope().getDeliveryTag(), false, false);
                } else {
                    channel.basicAck(msg.getEnvelope().getDeliveryTag(), false);
                }
            }
            return null;
        });

        // Main queue should be empty
        assertThat(rabbitTemplate.receiveAndConvert(RabbitMqConfig.MAINTENANCE_QUEUE, 500))
                .isNull();

        // DLQ should have exactly 2 poison messages
        int dlqCount = 0;
        while (rabbitTemplate.receiveAndConvert(RabbitMqConfig.MAINTENANCE_DLQ, 1_000) != null) {
            dlqCount++;
        }
        assertThat(dlqCount).isEqualTo(2);
    }

    @Test
    void replayIsIdempotentWhenEntryAlreadyConfirmed() {
        // An entry already confirmed should not be re-processed (confirmed stays true)
        jdbc.update("""
                INSERT INTO shadow_ledger_transaction_log
                    (transaction_id, end_to_end_id, account_id, transaction_type,
                     amount, balance_before, balance_after, core_confirmed)
                VALUES ('TXN-IDEM-001', 'E2E-IDEM-001', 'ACC-IDEM-001', 'CREDIT',
                        100.00, 49900.00, 50000.00, TRUE)
                """);

        assertThatCode(() -> reconciliationService.replayTransactions(List.of("TXN-IDEM-001")))
                .doesNotThrowAnyException();

        Boolean confirmed = jdbc.queryForObject(
                "SELECT core_confirmed FROM shadow_ledger_transaction_log " +
                "WHERE transaction_id = 'TXN-IDEM-001'",
                Boolean.class);
        assertThat(confirmed).isTrue();
    }
}
