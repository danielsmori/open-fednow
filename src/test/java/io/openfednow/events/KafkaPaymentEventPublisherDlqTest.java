package io.openfednow.events;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the dead-letter queue path added in issue #48. When a Kafka publish
 * fails, the publisher must re-route the event to the DLQ topic with
 * {@code X-DLQ-Original-Topic} + {@code X-DLQ-Reason} headers and increment
 * the {@code events.publish.failed} counter — without propagating the failure
 * to the caller.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class KafkaPaymentEventPublisherDlqTest {

    private KafkaTemplate<String, String> kafkaTemplate;
    private SimpleMeterRegistry meterRegistry;
    private KafkaPaymentEventPublisher publisher;

    @BeforeEach
    void setUp() {
        kafkaTemplate = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        publisher = new KafkaPaymentEventPublisher(
                kafkaTemplate, meterRegistry, "test.topic", "");
    }

    // ── DLQ dispatch ─────────────────────────────────────────────────────────

    @Test
    void primaryPublishFailureRoutesToDefaultDlqTopic() {
        // First send() (to primary) fails; second send() (to DLQ) succeeds.
        AtomicInteger callIndex = new AtomicInteger();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(inv -> {
            int i = callIndex.getAndIncrement();
            return i == 0
                    ? CompletableFuture.failedFuture(new RuntimeException("broker unreachable"))
                    : CompletableFuture.completedFuture(null);
        });

        publisher.publish(sampleEvent("TXN-DLQ-1"));

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                (ArgumentCaptor<ProducerRecord<String, String>>) (ArgumentCaptor<?>)
                        ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(2)).send(captor.capture());

        List<ProducerRecord<String, String>> sent = captor.getAllValues();
        ProducerRecord<String, String> primary = sent.get(0);
        ProducerRecord<String, String> dlq = sent.get(1);

        assertThat(primary.topic()).isEqualTo("test.topic");
        assertThat(dlq.topic()).isEqualTo("test.topic.dlq");
        assertThat(dlq.key()).isEqualTo(primary.key());
        assertThat(dlq.value()).isEqualTo(primary.value());
    }

    @Test
    void dlqRecordCarriesOriginalTopicAndReasonHeaders() {
        AtomicInteger callIndex = new AtomicInteger();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(inv -> {
            int i = callIndex.getAndIncrement();
            return i == 0
                    ? CompletableFuture.failedFuture(new RuntimeException("out of connections"))
                    : CompletableFuture.completedFuture(null);
        });

        publisher.publish(sampleEvent("TXN-DLQ-HDR"));

        ProducerRecord<String, String> dlq = capturedRecord(1);

        String originalTopic = headerValue(dlq, KafkaPaymentEventPublisher.DLQ_ORIGINAL_TOPIC_HEADER);
        String reason = headerValue(dlq, KafkaPaymentEventPublisher.DLQ_REASON_HEADER);

        assertThat(originalTopic).isEqualTo("test.topic");
        assertThat(reason).contains("RuntimeException");
        assertThat(reason).contains("out of connections");
    }

    @Test
    void dlqRecordPreservesSchemaVersionAndEventTypeHeaders() {
        AtomicInteger callIndex = new AtomicInteger();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(inv -> {
            int i = callIndex.getAndIncrement();
            return i == 0
                    ? CompletableFuture.failedFuture(new RuntimeException("fail"))
                    : CompletableFuture.completedFuture(null);
        });

        publisher.publish(sampleEvent("TXN-DLQ-HEADERS"));

        ProducerRecord<String, String> dlq = capturedRecord(1);
        // Routing headers preserved so DLQ consumers see the same shape
        assertThat(headerValue(dlq, KafkaPaymentEventPublisher.SCHEMA_VERSION_HEADER)).isNotBlank();
        assertThat(headerValue(dlq, KafkaPaymentEventPublisher.EVENT_TYPE_HEADER))
                .isEqualTo("INBOUND_CREDIT_APPLIED");
    }

    @Test
    void failureCounterIncrementsOnPrimaryPublishFailure() {
        AtomicInteger callIndex = new AtomicInteger();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(inv -> {
            int i = callIndex.getAndIncrement();
            return i == 0
                    ? CompletableFuture.failedFuture(new RuntimeException("boom"))
                    : CompletableFuture.completedFuture(null);
        });

        publisher.publish(sampleEvent("TXN-METRIC-1"));

        assertThat(meterRegistry.counter(KafkaPaymentEventPublisher.PUBLISH_FAILED_METRIC).count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter(KafkaPaymentEventPublisher.DLQ_FAILED_METRIC).count())
                .isZero();
    }

    @Test
    void dlqFailedCounterIncrementsWhenDlqPublishAlsoFails() {
        // Both primary and DLQ fail — end-of-the-road path
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(
                CompletableFuture.failedFuture(new RuntimeException("everything broken")));

        publisher.publish(sampleEvent("TXN-METRIC-2"));

        assertThat(meterRegistry.counter(KafkaPaymentEventPublisher.PUBLISH_FAILED_METRIC).count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter(KafkaPaymentEventPublisher.DLQ_FAILED_METRIC).count())
                .isEqualTo(1.0);
    }

    @Test
    void successfulPublishDoesNotTouchDlq() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publish(sampleEvent("TXN-OK"));

        // Only the primary publish — no DLQ call
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
        assertThat(meterRegistry.counter(KafkaPaymentEventPublisher.PUBLISH_FAILED_METRIC).count())
                .isZero();
    }

    // ── DLQ topic naming ─────────────────────────────────────────────────────

    @Test
    void explicitDlqTopicOverrideIsHonored() {
        KafkaPaymentEventPublisher customPublisher = new KafkaPaymentEventPublisher(
                kafkaTemplate, new SimpleMeterRegistry(),
                "payments.events", "payments.events.dead-letter");

        AtomicInteger callIndex = new AtomicInteger();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(inv -> {
            int i = callIndex.getAndIncrement();
            return i == 0
                    ? CompletableFuture.failedFuture(new RuntimeException("fail"))
                    : CompletableFuture.completedFuture(null);
        });

        customPublisher.publish(sampleEvent("TXN-CUSTOM-DLQ"));

        ProducerRecord<String, String> dlq = capturedRecord(1);
        assertThat(dlq.topic()).isEqualTo("payments.events.dead-letter");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ProducerRecord<String, String> capturedRecord(int index) {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                (ArgumentCaptor<ProducerRecord<String, String>>) (ArgumentCaptor<?>)
                        ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(index + 1)).send(captor.capture());
        return captor.getAllValues().get(index);
    }

    private String headerValue(ProducerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private PaymentEvent sampleEvent(String transactionId) {
        return PaymentEvent.create(
                PaymentEvent.EventType.INBOUND_CREDIT_APPLIED,
                transactionId,
                "E2E-" + transactionId,
                new BigDecimal("100.00"),
                "USD",
                null,
                Instant.parse("2026-06-01T10:00:00Z"));
    }
}
