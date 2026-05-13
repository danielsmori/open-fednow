package io.openfednow.events;

import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import io.openfednow.gateway.FedNowClient;
import io.openfednow.gateway.MessageRouter;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration tests for the Kafka event bus.
 *
 * <p>Verifies that {@link MessageRouter} publishes {@link PaymentEvent}s to Kafka
 * when {@code openfednow.kafka.enabled=true}. Extends
 * {@link AbstractInfrastructureIntegrationTest} to inherit the Redis, RabbitMQ, and
 * PostgreSQL containers, and adds its own Kafka container via a {@code @Container}
 * static field.
 *
 * <p>{@code @Testcontainers(disabledWithoutDocker = true)} is inherited from the
 * parent class: all tests in this class are skipped when Docker is unavailable.
 *
 * <p>A raw Kafka consumer is used to verify message delivery — no
 * {@code @KafkaListener} lifecycle complexity involved.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>Successful inbound credit publishes INBOUND_CREDIT_APPLIED event</li>
 *   <li>Rejected inbound credit publishes INBOUND_PAYMENT_REJECTED event</li>
 *   <li>Successful outbound payment publishes OUTBOUND_PAYMENT_COMPLETED event</li>
 *   <li>Outbound payment with insufficient funds publishes OUTBOUND_PAYMENT_REJECTED event</li>
 *   <li>Event is keyed by transactionId for partition affinity</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KafkaEventBusIntegrationTest extends AbstractInfrastructureIntegrationTest {

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("openfednow.kafka.enabled", () -> "true");
    }

    @Autowired private MessageRouter messageRouter;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;

    @Value("${openfednow.kafka.topic:openfednow.payment.events}")
    private String topic;

    @MockBean private FedNowClient fedNowClient;

    private KafkaConsumer<String, String> consumer;

    private static final String DEBTOR_ACCOUNT = "ACC-DEBTOR-KAFKA-001";

    @BeforeEach
    void setup() {
        // Clean infrastructure state
        redis.delete(redis.keys("balance:*"));
        redis.delete(redis.keys("idempotency:*"));
        jdbc.update("DELETE FROM shadow_ledger_transaction_log");
        jdbc.update("DELETE FROM saga_state");
        jdbc.update("DELETE FROM idempotency_keys");

        // Seed debtor with $1000.00 = 100000 cents
        redis.opsForValue().set("balance:" + DEBTOR_ACCOUNT, "100000");

        // Create a raw consumer subscribed to the payment events topic
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-kafka-event-bus-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(topic));
    }

    @AfterEach
    void teardown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    // ── Inbound credit ────────────────────────────────────────────────────────

    @Test
    void inboundCredit_acsc_publishesInboundCreditAppliedEvent() {
        messageRouter.routeInbound(inbound("TXN-KFK-IN-001", "E2E-KFK-IN-001", "250.00"));

        List<ConsumerRecord<String, String>> records = poll();

        assertThat(records).anySatisfy(r -> {
            assertThat(r.value()).contains("INBOUND_CREDIT_APPLIED");
            assertThat(r.value()).contains("TXN-KFK-IN-001");
            assertThat(r.key()).isEqualTo("TXN-KFK-IN-001");
        });
    }

    @Test
    void inboundCredit_rjct_publishesInboundPaymentRejectedEvent() {
        // RJCT_FUNDS_ prefix causes SandboxAdapter to return REJECTED with AM04
        messageRouter.routeInbound(inbound("TXN-KFK-IN-002", "E2E-KFK-IN-002-RJCT",
                "100.00", "RJCT_FUNDS_ACC-001"));

        List<ConsumerRecord<String, String>> records = poll();

        assertThat(records).anySatisfy(r -> {
            assertThat(r.value()).contains("INBOUND_PAYMENT_REJECTED");
            assertThat(r.value()).contains("TXN-KFK-IN-002");
        });
    }

    // ── Outbound payment ──────────────────────────────────────────────────────

    @Test
    void outboundPayment_acsc_publishesOutboundPaymentCompletedEvent() {
        when(fedNowClient.submitCreditTransfer(any()))
                .thenReturn(Pacs002Message.builder()
                        .originalEndToEndId("E2E-KFK-OUT-001")
                        .originalTransactionId("TXN-KFK-OUT-001")
                        .transactionStatus(Pacs002Message.TransactionStatus.ACSC)
                        .creationDateTime(OffsetDateTime.now())
                        .build());

        messageRouter.routeOutbound(outbound("TXN-KFK-OUT-001", "E2E-KFK-OUT-001", "150.00"));

        List<ConsumerRecord<String, String>> records = poll();

        assertThat(records).anySatisfy(r -> {
            assertThat(r.value()).contains("OUTBOUND_PAYMENT_COMPLETED");
            assertThat(r.value()).contains("TXN-KFK-OUT-001");
        });
    }

    @Test
    void outboundPayment_insufficientFunds_publishesOutboundPaymentRejectedEvent() {
        // $9999.00 exceeds the seeded $1000.00 balance
        messageRouter.routeOutbound(outbound("TXN-KFK-OUT-NSF-001", "E2E-KFK-OUT-NSF-001", "9999.00"));

        List<ConsumerRecord<String, String>> records = poll();

        assertThat(records).anySatisfy(r -> {
            assertThat(r.value()).contains("OUTBOUND_PAYMENT_REJECTED");
            assertThat(r.value()).contains("AM04");
        });
    }

    // ── Event structure ───────────────────────────────────────────────────────

    @Test
    void publishedEvent_isKeyedByTransactionId() {
        messageRouter.routeInbound(inbound("TXN-KFK-KEY-001", "E2E-KFK-KEY-001", "50.00"));

        List<ConsumerRecord<String, String>> records = poll();

        assertThat(records)
                .filteredOn(r -> "TXN-KFK-KEY-001".equals(r.key()))
                .isNotEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Pacs008Message inbound(String transactionId, String endToEndId, String amount) {
        return inbound(transactionId, endToEndId, amount, "ACC-CREDITOR-001");
    }

    private Pacs008Message inbound(String transactionId, String endToEndId,
                                   String amount, String creditorAccount) {
        return Pacs008Message.builder()
                .messageId("MSG-" + transactionId)
                .endToEndId(endToEndId)
                .transactionId(transactionId)
                .interbankSettlementAmount(new BigDecimal(amount))
                .interbankSettlementCurrency("USD")
                .debtorAccountNumber("ACC-DEBTOR-EXT-001")
                .creditorAccountNumber(creditorAccount)
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("021000089")
                .debtorName("Alice Smith")
                .creditorName("Bob Jones")
                .numberOfTransactions(1)
                .creationDateTime(OffsetDateTime.now())
                .build();
    }

    private Pacs008Message outbound(String transactionId, String endToEndId, String amount) {
        return Pacs008Message.builder()
                .messageId("MSG-" + transactionId)
                .endToEndId(endToEndId)
                .transactionId(transactionId)
                .interbankSettlementAmount(new BigDecimal(amount))
                .interbankSettlementCurrency("USD")
                .debtorAccountNumber(DEBTOR_ACCOUNT)
                .creditorAccountNumber("ACC-CREDITOR-EXT-001")
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("021000089")
                .debtorName("Alice Smith")
                .creditorName("Bob Jones")
                .numberOfTransactions(1)
                .creationDateTime(OffsetDateTime.now())
                .build();
    }

    /**
     * Polls Kafka until records arrive or 15 seconds elapse.
     */
    private List<ConsumerRecord<String, String>> poll() {
        List<ConsumerRecord<String, String>> result = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 15_000;
        while (result.isEmpty() && System.currentTimeMillis() < deadline) {
            consumer.poll(Duration.ofMillis(500)).forEach(result::add);
        }
        return result;
    }
}
