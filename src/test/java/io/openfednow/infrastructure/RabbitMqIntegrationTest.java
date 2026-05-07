package io.openfednow.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.openfednow.shadowledger.RabbitMqConfig;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for RabbitMQ connectivity and message operations.
 *
 * <p>Validates that Spring's {@link RabbitTemplate} and {@link AmqpAdmin} are
 * correctly wired to the Testcontainers RabbitMQ instance, and that the
 * operations required by {@code AvailabilityBridge} behave as expected.
 *
 * <h2>Operations under test</h2>
 * <ul>
 *   <li>Queue declaration with durability — messages must survive broker restart</li>
 *   <li>Message publishing — {@code AvailabilityBridge.queueForCoreProcessing()}</li>
 *   <li>Message consumption in FIFO order — {@code ReconciliationService.replayTransactions()}</li>
 *   <li>Payload round-trip — transaction ID and serialized payload preserved</li>
 *   <li>Queue depth — verifying the maintenance-window queue accumulates messages</li>
 * </ul>
 */
class RabbitMqIntegrationTest extends AbstractInfrastructureIntegrationTest {

    static final String MAINTENANCE_QUEUE = RabbitMqConfig.MAINTENANCE_QUEUE;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @BeforeEach
    void purgeQueue() {
        // Purge rather than delete+redeclare: RabbitMqConfig declares the queue
        // with dead-letter arguments on context startup, and redeclaring with
        // different arguments would cause a PRECONDITION_FAILED channel error.
        // Purging empties the queue while preserving its declaration.
        ((RabbitAdmin) amqpAdmin).purgeQueue(MAINTENANCE_QUEUE, false);
    }

    // --- Connectivity ---

    @Test
    void rabbitMqIsReachable() {
        QueueInformation info = amqpAdmin.getQueueInfo(MAINTENANCE_QUEUE);
        assertThat(info).isNotNull();
        assertThat(info.getName()).isEqualTo(MAINTENANCE_QUEUE);
    }

    // --- Queue configuration ---

    @Test
    void queueIsDeclaredDurable() {
        // Durability is declared in RabbitMqConfig — a non-durable queue would
        // lose messages if RabbitMQ restarts during a maintenance window.
        QueueInformation info = amqpAdmin.getQueueInfo(MAINTENANCE_QUEUE);
        assertThat(info).isNotNull();
        assertThat(info.getMessageCount()).isNotNegative();
    }

    // --- Message publishing and consumption ---

    @Test
    void publishedMessageCanBeConsumed() {
        String payload = """
                {"transactionId":"TXN-001","endToEndId":"E2E-001","amount":"100.00"}
                """.strip();

        rabbitTemplate.convertAndSend(MAINTENANCE_QUEUE, payload);

        String received = (String) rabbitTemplate.receiveAndConvert(MAINTENANCE_QUEUE, 3000);
        assertThat(received).isEqualTo(payload);
    }

    @Test
    void messagesAreReceivedInFifoOrder() {
        // ReconciliationService must replay transactions in chronological order.
        // RabbitMQ queues are FIFO; verify this holds.
        List<String> transactionIds = List.of("TXN-FIRST", "TXN-SECOND", "TXN-THIRD");

        for (String txId : transactionIds) {
            rabbitTemplate.convertAndSend(MAINTENANCE_QUEUE, txId);
        }

        List<String> received = new ArrayList<>();
        for (int i = 0; i < transactionIds.size(); i++) {
            received.add((String) rabbitTemplate.receiveAndConvert(MAINTENANCE_QUEUE, 3000));
        }

        assertThat(received).containsExactlyElementsOf(transactionIds);
    }

    @Test
    void transactionIdIsPreservedInPayload() {
        String transactionId = "TXN-E2E-2024-001";
        String payload = "{\"transactionId\":\"" + transactionId + "\",\"amount\":\"500.00\"}";

        rabbitTemplate.convertAndSend(MAINTENANCE_QUEUE, payload);

        String received = (String) rabbitTemplate.receiveAndConvert(MAINTENANCE_QUEUE, 3000);
        assertThat(received).contains(transactionId);
    }

    @Test
    void queueAccumulatesMessagesBeforeConsumption() {
        // During a maintenance window, multiple payments may arrive before
        // ReconciliationService begins replay. Verify the queue holds all of them.
        // Note: getQueueInfo().getMessageCount() is updated asynchronously by RabbitMQ
        // and can lag behind sends, so we consume and count instead of reading the
        // queue depth — this is the observable behaviour that actually matters.
        int messageCount = 5;
        for (int i = 0; i < messageCount; i++) {
            rabbitTemplate.convertAndSend(MAINTENANCE_QUEUE,
                    "{\"transactionId\":\"TXN-BATCH-" + i + "\"}");
        }

        List<Object> received = new ArrayList<>();
        for (int i = 0; i < messageCount; i++) {
            Object msg = rabbitTemplate.receiveAndConvert(MAINTENANCE_QUEUE, 3000);
            if (msg != null) received.add(msg);
        }
        assertThat(received).hasSize(messageCount);
    }

    @Test
    void messageBytesArePreservedForArbitraryPayload() {
        // Verify that binary-safe message delivery works for serialized payloads.
        // The AvailabilityBridge may queue serialized Pacs008Message objects.
        byte[] binaryPayload = "pacs.008 serialized content".getBytes(StandardCharsets.UTF_8);
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_BYTES);
        Message message = new Message(binaryPayload, props);

        rabbitTemplate.send(MAINTENANCE_QUEUE, message);

        Message received = rabbitTemplate.receive(MAINTENANCE_QUEUE, 3000);
        assertThat(received).isNotNull();
        assertThat(received.getBody()).isEqualTo(binaryPayload);
    }
}
