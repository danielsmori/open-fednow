package io.openfednow.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
 * <p>Kafka publish failures are handled in three layers:
 * <ol>
 *   <li>They never propagate to the caller. A Kafka outage must not cause a
 *       payment to fail or be retried by the payment-side machinery.</li>
 *   <li>Every failure increments the {@code events.publish.failed} counter
 *       and logs a warning with the underlying cause.</li>
 *   <li>The event is re-published to a dead-letter topic — same key + payload
 *       + schema headers, plus {@code X-DLQ-Reason} / {@code X-DLQ-Original-Topic}
 *       headers naming the original failure. DLQ topic defaults to
 *       {@code {topic}.dlq}; override via {@code openfednow.kafka.dlq-topic}.
 *       If the DLQ publish itself fails, that's logged as an error and the
 *       {@code events.publish.dlq_failed} counter increments — beyond that
 *       there's nothing more the publisher can do.</li>
 * </ol>
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

    /** Header on DLQ records naming the original topic that rejected the publish. */
    public static final String DLQ_ORIGINAL_TOPIC_HEADER = "X-DLQ-Original-Topic";

    /** Header on DLQ records carrying the exception class + message that caused the failure. */
    public static final String DLQ_REASON_HEADER = "X-DLQ-Reason";

    static final String PUBLISH_FAILED_METRIC = "events.publish.failed";
    static final String DLQ_FAILED_METRIC = "events.publish.dlq_failed";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final String dlqTopic;
    private final Counter publishFailedCounter;
    private final Counter dlqFailedCounter;

    public KafkaPaymentEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            MeterRegistry meterRegistry,
            @Value("${openfednow.kafka.topic:openfednow.payment.events}") String topic,
            @Value("${openfednow.kafka.dlq-topic:}") String dlqTopicOverride) {
        this.kafkaTemplate = kafkaTemplate;
        // JavaTimeModule handles Instant serialization; register locally to avoid
        // depending on a globally configured ObjectMapper.
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.topic = topic;
        this.dlqTopic = (dlqTopicOverride == null || dlqTopicOverride.isBlank())
                ? topic + ".dlq"
                : dlqTopicOverride;
        this.publishFailedCounter = Counter.builder(PUBLISH_FAILED_METRIC)
                .description("PaymentEvent Kafka publishes that failed and were routed to the DLQ")
                .register(meterRegistry);
        this.dlqFailedCounter = Counter.builder(DLQ_FAILED_METRIC)
                .description("PaymentEvent DLQ re-publishes that themselves failed — end of the road")
                .register(meterRegistry);
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
                        log.warn("Kafka publish failed type={} txn={} topic={} — routing to DLQ",
                                event.eventType(), event.transactionId(), topic, ex);
                        publishFailedCounter.increment();
                        routeToDlq(record, payload, event, ex);
                    } else {
                        log.debug("PaymentEvent published type={} txn={} schemaVersion={} partition={} offset={}",
                                event.eventType(), event.transactionId(), event.schemaVersion(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }

    /**
     * Re-publishes a failed event to the DLQ topic with headers naming the
     * original topic and failure reason. Preserves the schema-version + event-type
     * headers so a DLQ consumer sees the same routing metadata as the primary topic.
     *
     * <p>Best-effort: if the DLQ publish also fails, the event is dropped and the
     * dlq_failed counter increments. There's no further recovery layer — a
     * Kafka cluster that can't accept even the DLQ topic is beyond the publisher's
     * ability to compensate for.
     */
    private void routeToDlq(ProducerRecord<String, String> original, String payload,
                            PaymentEvent event, Throwable failureCause) {
        ProducerRecord<String, String> dlqRecord = new ProducerRecord<>(
                dlqTopic, null, original.key(), payload);
        // Preserve the original routing headers
        dlqRecord.headers().add(new RecordHeader(SCHEMA_VERSION_HEADER,
                event.schemaVersion().getBytes(StandardCharsets.UTF_8)));
        dlqRecord.headers().add(new RecordHeader(EVENT_TYPE_HEADER,
                event.eventType().name().getBytes(StandardCharsets.UTF_8)));
        // Add DLQ metadata
        dlqRecord.headers().add(new RecordHeader(DLQ_ORIGINAL_TOPIC_HEADER,
                topic.getBytes(StandardCharsets.UTF_8)));
        dlqRecord.headers().add(new RecordHeader(DLQ_REASON_HEADER,
                dlqReasonText(failureCause).getBytes(StandardCharsets.UTF_8)));

        try {
            kafkaTemplate.send(dlqRecord)
                    .whenComplete((dlqResult, dlqEx) -> {
                        if (dlqEx != null) {
                            log.error("DLQ publish also failed type={} txn={} dlqTopic={} — event dropped",
                                    event.eventType(), event.transactionId(), dlqTopic, dlqEx);
                            dlqFailedCounter.increment();
                        } else {
                            log.info("PaymentEvent routed to DLQ type={} txn={} dlqTopic={}",
                                    event.eventType(), event.transactionId(), dlqTopic);
                        }
                    });
        } catch (Exception e) {
            // Synchronous throw from KafkaTemplate.send (e.g., serializer failure).
            // The whenComplete branch above catches async failures.
            log.error("DLQ publish threw synchronously type={} txn={} dlqTopic={} — event dropped",
                    event.eventType(), event.transactionId(), dlqTopic, e);
            dlqFailedCounter.increment();
        }
    }

    private static String dlqReasonText(Throwable cause) {
        if (cause == null) {
            return "unknown";
        }
        String msg = cause.getMessage();
        String label = cause.getClass().getSimpleName();
        return msg == null ? label : label + ": " + msg;
    }
}
