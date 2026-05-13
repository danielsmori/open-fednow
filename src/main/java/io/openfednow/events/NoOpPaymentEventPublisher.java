package io.openfednow.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default {@link PaymentEventPublisher} — active when the Kafka event bus is disabled.
 *
 * <p>Silently discards all events. This is the correct default for institutions that have
 * not yet provisioned a Kafka cluster; it means the payment processing path is unaffected
 * and no Kafka broker is required to run OpenFedNow.
 *
 * <p>Replaced by {@link KafkaPaymentEventPublisher} when
 * {@code openfednow.kafka.enabled=true}.
 */
@Component
@ConditionalOnMissingBean(PaymentEventPublisher.class)
public class NoOpPaymentEventPublisher implements PaymentEventPublisher {

    @Override
    public void publish(PaymentEvent event) {
        // intentionally empty — Kafka is disabled
    }
}
