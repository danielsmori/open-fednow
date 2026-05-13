package io.openfednow.events;

/**
 * Publishes {@link PaymentEvent}s to an event bus.
 *
 * <p>Two implementations are provided:
 * <ul>
 *   <li>{@link KafkaPaymentEventPublisher} — active when
 *       {@code openfednow.kafka.enabled=true}; publishes JSON events to the
 *       configured Kafka topic.</li>
 *   <li>{@link NoOpPaymentEventPublisher} — default; swallows all events so
 *       that existing deployments need no Kafka broker.</li>
 * </ul>
 *
 * <p>Publishing is fire-and-forget from the payment processing perspective: a
 * Kafka failure must never cause a payment to fail or be retried. Implementations
 * must log failures but must not propagate exceptions to callers.
 *
 * @see PaymentEvent
 */
public interface PaymentEventPublisher {

    /**
     * Publishes the event asynchronously.
     *
     * @param event the payment lifecycle event; never {@code null}
     */
    void publish(PaymentEvent event);
}
