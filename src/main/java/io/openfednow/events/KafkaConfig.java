package io.openfednow.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka topic configuration — active only when {@code openfednow.kafka.enabled=true}.
 *
 * <p>Declares the {@code openfednow.payment.events} topic so that
 * {@link org.springframework.kafka.core.KafkaAdmin} creates it at startup if it
 * does not already exist. When Kafka is disabled this configuration class is not
 * loaded, so no {@code NewTopic} beans are registered and {@code KafkaAdmin}
 * makes no broker connections.
 */
@Configuration
@ConditionalOnProperty(name = "openfednow.kafka.enabled", havingValue = "true")
public class KafkaConfig {

    @Value("${openfednow.kafka.topic:openfednow.payment.events}")
    private String topic;

    /**
     * Declares the payment events topic.
     *
     * <p>Three partitions provide parallelism for downstream consumers (e.g. one
     * consumer per payment-type category). Replication factor 1 is intentionally
     * low for local/sandbox deployments; production clusters should override this
     * via broker-level defaults or explicit configuration.
     */
    @Bean
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name(topic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
