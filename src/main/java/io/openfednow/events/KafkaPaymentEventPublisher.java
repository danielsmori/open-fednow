package io.openfednow.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Kafka-backed {@link PaymentEventPublisher}.
 *
 * <p>Active when {@code openfednow.kafka.enabled=true}. Publishes each
 * {@link PaymentEvent} as a JSON string to the configured topic, keyed by
 * {@code transactionId} so that events for the same transaction land on the
 * same partition (preserving per-transaction ordering).
 *
 * <h2>Activation</h2>
 * <pre>
 * openfednow:
 *   kafka:
 *     enabled: true
 *     topic: openfednow.payment.events   # default
 *
 * spring:
 *   kafka:
 *     bootstrap-servers: localhost:9092
 * </pre>
 *
 * <h2>Failure handling</h2>
 * <p>Kafka publish failures are logged as warnings but never propagate to the
 * caller. Event delivery is best-effort — a Kafka outage must not cause a
 * payment to fail or be retried.
 *
 * @see PaymentEventPublisher
 * @see NoOpPaymentEventPublisher
 */
@Component
@ConditionalOnProperty(name = "openfednow.kafka.enabled", havingValue = "true")
public class KafkaPaymentEventPublisher implements PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaPaymentEventPublisher.class);

    /** Kafka message header advertising the schema version of the payload. */
    public static final String SCHEMA_VERSION_HEADER = "X-Schema-Version";

    /** Kafka message header carrying the event type for header-only routing without payload deserialization. */
    public static final String EVENT_TYPE_HEADER = "X-Event-Type";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public KafkaPaymentEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${openfednow.kafka.topic:openfednow.payment.events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        // JavaTimeModule handles Instant serialization; register locally to avoid
        // depending on a globally configured ObjectMapper.
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.topic = topic;
    }

    @Override
    public void publish(PaymentEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize PaymentEvent type={} txn={} — event discarded",
                    event.eventType(), event.transactionId(), e);
            return;
        }

        ProducerRecord<String, String> record = new ProducerRecord<>(
                topic, null, event.transactionId(), payload);
        // The payload also carries schemaVersion (since #49) — the header is duplicated so
        // routing layers can filter on version without deserializing the body.
        record.headers().add(new RecordHeader(SCHEMA_VERSION_HEADER,
                event.schemaVersion().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(EVENT_TYPE_HEADER,
                event.eventType().name().getBytes(StandardCharsets.UTF_8)));

        kafkaTemplate.send(record)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("Kafka publish failed type={} txn={} topic={}",
                                event.eventType(), event.transactionId(), topic, ex);
                    } else {
                        log.debug("PaymentEvent published type={} txn={} schemaVersion={} partition={} offset={}",
                                event.eventType(), event.transactionId(), event.schemaVersion(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
