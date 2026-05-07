package io.openfednow.shadowledger;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Layer 4 — RabbitMQ topology for the maintenance-window transaction queue.
 *
 * <p>Declares three AMQP entities:
 *
 * <pre>
 *   [Producer]
 *       │  publish to maintenance-window-transactions
 *       ▼
 *   ┌────────────────────────────────────────────┐
 *   │  maintenance-window-transactions (queue)   │  DLX arg → maintenance-window-transactions.dlx
 *   │  durable, x-dead-letter-exchange set       │
 *   └──────────────────────┬─────────────────────┘
 *                          │  nack / reject / TTL
 *                          ▼
 *   ┌────────────────────────────────────────────┐
 *   │  maintenance-window-transactions.dlx       │  (direct exchange)
 *   │                                            │
 *   └──────────────────────┬─────────────────────┘
 *                          │  routing key = MAINTENANCE_DLQ
 *                          ▼
 *   ┌────────────────────────────────────────────┐
 *   │  maintenance-window-transactions.dlq       │  (dead letter queue, durable)
 *   └────────────────────────────────────────────┘
 * </pre>
 *
 * <p><b>Why a DLQ matters here:</b> During a core banking maintenance window,
 * high-value payment messages are queued for ordered replay. A single poison
 * message (malformed payload, permanent deserialization failure) would block
 * all subsequent reconciliation if it were requeued indefinitely. The DLQ
 * removes the poison message from the live queue so that processing of
 * remaining transactions can continue. Ops teams can then inspect and
 * manually replay or discard DLQ messages.
 */
@Configuration
public class RabbitMqConfig {

    /** Main queue: transactions accumulated during core maintenance windows. */
    public static final String MAINTENANCE_QUEUE = "maintenance-window-transactions";

    /** Dead Letter Queue: receives messages that could not be processed. */
    public static final String MAINTENANCE_DLQ = "maintenance-window-transactions.dlq";

    // Internal — the exchange that routes dead-lettered messages to the DLQ.
    static final String MAINTENANCE_DLX = "maintenance-window-transactions.dlx";

    @Bean
    public DirectExchange maintenanceDlx() {
        return ExchangeBuilder.directExchange(MAINTENANCE_DLX).durable(true).build();
    }

    @Bean
    public Queue maintenanceQueue() {
        return QueueBuilder.durable(MAINTENANCE_QUEUE)
                .deadLetterExchange(MAINTENANCE_DLX)
                .deadLetterRoutingKey(MAINTENANCE_DLQ)
                .build();
    }

    @Bean
    public Queue maintenanceDlq() {
        return QueueBuilder.durable(MAINTENANCE_DLQ).build();
    }

    @Bean
    public Binding maintenanceDlqBinding(Queue maintenanceDlq, DirectExchange maintenanceDlx) {
        return BindingBuilder.bind(maintenanceDlq).to(maintenanceDlx).with(MAINTENANCE_DLQ);
    }
}
