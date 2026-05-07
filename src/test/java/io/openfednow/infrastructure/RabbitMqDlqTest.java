package io.openfednow.infrastructure;

import com.rabbitmq.client.GetResponse;
import io.openfednow.shadowledger.RabbitMqConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Dead Letter Queue topology.
 *
 * <p>Verifies that messages rejected from the maintenance-window queue are
 * routed to the DLQ rather than being dropped or requeued indefinitely.
 * This topology protects the reconciliation queue from poison messages —
 * a single unprocessable message cannot block replay of subsequent
 * transactions queued during a core banking maintenance window.
 *
 * @see RabbitMqConfig
 */
class RabbitMqDlqTest extends AbstractInfrastructureIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @BeforeEach
    void purgeQueues() {
        // Purge (not delete) to preserve the DLQ arguments set by RabbitMqConfig.
        ((RabbitAdmin) amqpAdmin).purgeQueue(RabbitMqConfig.MAINTENANCE_QUEUE, false);
        ((RabbitAdmin) amqpAdmin).purgeQueue(RabbitMqConfig.MAINTENANCE_DLQ, false);
    }

    @Test
    void rejectedMessageIsRoutedToDlq() {
        rabbitTemplate.convertAndSend(RabbitMqConfig.MAINTENANCE_QUEUE, "poison-message");

        // Simulate a consumer nacking without requeue — the dead-letter exchange
        // should route the message to the DLQ.
        rabbitTemplate.execute(channel -> {
            GetResponse response = channel.basicGet(RabbitMqConfig.MAINTENANCE_QUEUE, false);
            assertThat(response).isNotNull();
            channel.basicNack(response.getEnvelope().getDeliveryTag(), false, /* requeue */ false);
            return null;
        });

        Object dlqMessage = rabbitTemplate.receiveAndConvert(RabbitMqConfig.MAINTENANCE_DLQ, 3_000);
        assertThat(dlqMessage).isEqualTo("poison-message");
    }

    @Test
    void rejectedMessageLeavesMainQueueEmpty() {
        rabbitTemplate.convertAndSend(RabbitMqConfig.MAINTENANCE_QUEUE, "poison-message");

        rabbitTemplate.execute(channel -> {
            GetResponse response = channel.basicGet(RabbitMqConfig.MAINTENANCE_QUEUE, false);
            channel.basicNack(response.getEnvelope().getDeliveryTag(), false, false);
            return null;
        });

        // Main queue must be empty — the poison message must not be requeued.
        // Wait briefly for DLQ routing to complete before asserting.
        Object requeued = rabbitTemplate.receiveAndConvert(RabbitMqConfig.MAINTENANCE_QUEUE, 500);
        assertThat(requeued).isNull();
    }

    @Test
    void validMessagesAreUnaffectedByPoisonMessageInDlq() {
        // Interleave a poison message between two valid ones.
        // After the poison is dead-lettered, both valid messages must
        // still be consumable from the main queue in order.
        rabbitTemplate.convertAndSend(RabbitMqConfig.MAINTENANCE_QUEUE, "TXN-VALID-1");
        rabbitTemplate.convertAndSend(RabbitMqConfig.MAINTENANCE_QUEUE, "TXN-POISON");
        rabbitTemplate.convertAndSend(RabbitMqConfig.MAINTENANCE_QUEUE, "TXN-VALID-2");

        // Ack the first valid message.
        rabbitTemplate.execute(channel -> {
            GetResponse first = channel.basicGet(RabbitMqConfig.MAINTENANCE_QUEUE, false);
            assertThat(new String(first.getBody())).isEqualTo("TXN-VALID-1");
            channel.basicAck(first.getEnvelope().getDeliveryTag(), false);
            return null;
        });

        // Nack the poison message without requeue.
        rabbitTemplate.execute(channel -> {
            GetResponse poison = channel.basicGet(RabbitMqConfig.MAINTENANCE_QUEUE, false);
            assertThat(new String(poison.getBody())).isEqualTo("TXN-POISON");
            channel.basicNack(poison.getEnvelope().getDeliveryTag(), false, false);
            return null;
        });

        // The second valid message should still be available.
        Object remaining = rabbitTemplate.receiveAndConvert(RabbitMqConfig.MAINTENANCE_QUEUE, 3_000);
        assertThat(remaining).isEqualTo("TXN-VALID-2");

        // The poison message should be in the DLQ.
        Object dlqMessage = rabbitTemplate.receiveAndConvert(RabbitMqConfig.MAINTENANCE_DLQ, 3_000);
        assertThat(dlqMessage).isEqualTo("TXN-POISON");
    }

    @Test
    void dlqAccumulatesMultipleRejectedMessages() {
        for (int i = 1; i <= 3; i++) {
            rabbitTemplate.convertAndSend(RabbitMqConfig.MAINTENANCE_QUEUE, "POISON-" + i);
        }

        // Nack all three.
        for (int i = 0; i < 3; i++) {
            rabbitTemplate.execute(channel -> {
                GetResponse response = channel.basicGet(RabbitMqConfig.MAINTENANCE_QUEUE, false);
                channel.basicNack(response.getEnvelope().getDeliveryTag(), false, false);
                return null;
            });
        }

        // All three should land in the DLQ.
        int dlqCount = 0;
        for (int i = 0; i < 3; i++) {
            Object dlqMsg = rabbitTemplate.receiveAndConvert(RabbitMqConfig.MAINTENANCE_DLQ, 3_000);
            if (dlqMsg != null) dlqCount++;
        }
        assertThat(dlqCount).isEqualTo(3);
    }
}
