package io.openfednow.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KafkaPaymentEventPublisher} — issue #49.
 *
 * <p>Verifies the versioning headers and payload encoding without spinning up
 * a real Kafka broker. {@link KafkaTemplate} is mocked; the captured
 * {@link ProducerRecord} is inspected directly.
 */
@SuppressWarnings("unchecked")
class KafkaPaymentEventPublisherTest {

    private KafkaTemplate<String, String> kafkaTemplate;
    private KafkaPaymentEventPublisher publisher;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        kafkaTemplate = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn((CompletableFuture<SendResult<String, String>>) (CompletableFuture<?>)
                        CompletableFuture.completedFuture(null));
        publisher = new KafkaPaymentEventPublisher(kafkaTemplate, "test.topic");
    }

    // ── Header presence ──────────────────────────────────────────────────────

    @Test
    void publishedRecordCarriesSchemaVersionHeader() {
        publisher.publish(sampleEvent());

        ProducerRecord<String, String> sent = captureSentRecord();
        Header header = sent.headers().lastHeader(KafkaPaymentEventPublisher.SCHEMA_VERSION_HEADER);
        assertThat(header).isNotNull();
        assertThat(new String(header.value(), StandardCharsets.UTF_8))
                .isEqualTo(PaymentEvent.CURRENT_SCHEMA_VERSION);
    }

    @Test
    void publishedRecordCarriesEventTypeHeader() {
        publisher.publish(sampleEvent());

        ProducerRecord<String, String> sent = captureSentRecord();
        Header header = sent.headers().lastHeader(KafkaPaymentEventPublisher.EVENT_TYPE_HEADER);
        assertThat(header).isNotNull();
        assertThat(new String(header.value(), StandardCharsets.UTF_8))
                .isEqualTo("INBOUND_CREDIT_APPLIED");
    }

    @Test
    void schemaVersionHeaderReflectsValueOnTheEvent() {
        // Construct an event with an explicit older version — the publisher must NOT
        // override it with CURRENT_SCHEMA_VERSION. This guards against accidental
        // re-stamping during replay or rebroadcast scenarios.
        PaymentEvent oldVersion = new PaymentEvent(
                "0.9", PaymentEvent.EventType.INBOUND_CREDIT_APPLIED,
                "TXN-OLD", "E2E-OLD", new BigDecimal("100.00"), "USD",
                null, Instant.parse("2026-05-01T10:00:00Z"));

        publisher.publish(oldVersion);

        ProducerRecord<String, String> sent = captureSentRecord();
        assertThat(headerValue(sent, KafkaPaymentEventPublisher.SCHEMA_VERSION_HEADER))
                .isEqualTo("0.9");
    }

    // ── Payload encoding ─────────────────────────────────────────────────────

    @Test
    void payloadIncludesSchemaVersionField() throws Exception {
        publisher.publish(sampleEvent());

        ProducerRecord<String, String> sent = captureSentRecord();
        PaymentEvent roundTripped = mapper.readValue(sent.value(), PaymentEvent.class);
        assertThat(roundTripped.schemaVersion()).isEqualTo(PaymentEvent.CURRENT_SCHEMA_VERSION);
    }

    @Test
    void recordIsKeyedByTransactionId() {
        PaymentEvent event = sampleEvent();
        publisher.publish(event);

        ProducerRecord<String, String> sent = captureSentRecord();
        assertThat(sent.key()).isEqualTo(event.transactionId());
    }

    @Test
    void recordIsSentToConfiguredTopic() {
        publisher.publish(sampleEvent());

        ProducerRecord<String, String> sent = captureSentRecord();
        assertThat(sent.topic()).isEqualTo("test.topic");
    }

    // ── Header-payload consistency ───────────────────────────────────────────

    @Test
    void schemaVersionHeaderAndPayloadFieldAreEqual() throws Exception {
        publisher.publish(sampleEvent());

        ProducerRecord<String, String> sent = captureSentRecord();
        String headerValue = headerValue(sent, KafkaPaymentEventPublisher.SCHEMA_VERSION_HEADER);
        PaymentEvent payload = mapper.readValue(sent.value(), PaymentEvent.class);
        assertThat(headerValue).isEqualTo(payload.schemaVersion());
    }

    @Test
    void eventTypeHeaderAndPayloadFieldAreEqual() throws Exception {
        PaymentEvent event = PaymentEvent.create(
                PaymentEvent.EventType.OUTBOUND_PAYMENT_REJECTED,
                "TXN-X", "E2E-X", new BigDecimal("250.00"), "USD",
                "AM04", Instant.now());
        publisher.publish(event);

        ProducerRecord<String, String> sent = captureSentRecord();
        PaymentEvent payload = mapper.readValue(sent.value(), PaymentEvent.class);
        assertThat(headerValue(sent, KafkaPaymentEventPublisher.EVENT_TYPE_HEADER))
                .isEqualTo(payload.eventType().name());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ProducerRecord<String, String> captureSentRecord() {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                (ArgumentCaptor<ProducerRecord<String, String>>) (ArgumentCaptor<?>)
                        ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    private String headerValue(ProducerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private PaymentEvent sampleEvent() {
        return PaymentEvent.create(
                PaymentEvent.EventType.INBOUND_CREDIT_APPLIED,
                "TXN-001",
                "E2E-001",
                new BigDecimal("100.00"),
                "USD",
                null,
                Instant.parse("2026-05-22T10:00:00Z"));
    }
}
