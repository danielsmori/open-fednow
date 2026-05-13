package io.openfednow.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 *
 * <p>Uses {@code @ConditionalOnProperty} rather than {@code @ConditionalOnMissingBean} to
 * avoid bean-ordering ambiguity during component scan: the condition is evaluated purely
 * from the property value and is deterministic regardless of scan order.
 */
@Component
@ConditionalOnProperty(name = "openfednow.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpPaymentEventPublisher implements PaymentEventPublisher {

    @Override
    public void publish(PaymentEvent event) {
        // intentionally empty — Kafka is disabled
    }
}
