package io.openfednow.shadowledger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises Shadow Ledger behavior when Redis is unreachable.
 *
 * <p>Unlike {@link RedisIntegrationTest} — which shares the base container with
 * every other infrastructure test — this class owns its own Redis instance and
 * deliberately stops it mid-test. Stopping the shared container would break
 * every other {@code @Tag("integration")} class, so isolation is intentional.
 *
 * <p>The test documents the currently expected failure mode: Shadow Ledger
 * surfaces a {@link RedisConnectionFailureException} when Redis is down, rather
 * than silently returning stale data, a default balance, or a generic
 * RuntimeException. This is the correct behavior — a payment system that
 * guesses a balance from thin air is worse than one that outright refuses.
 * The outer caller (MessageRouter) can catch this specific exception type and
 * translate it into an ISO 20022 TS01 (System Unavailable) response instead of
 * a downstream funds/reservation error that would mislead operators.
 *
 * <p>A future change that swallows or masks the connection error would be
 * caught here — that's the point of pinning the behavior down as a test.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisOutageIntegrationTest {

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("openfednow")
                    .withUsername("openfednow")
                    .withPassword("openfednow");

    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management"));

    @BeforeAll
    static void bootContainers() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping Redis-outage integration test");
        REDIS.start();
        POSTGRES.start();
        RABBITMQ.start();
    }

    @AfterAll
    static void tearDownContainers() {
        if (REDIS.isRunning()) {
            REDIS.stop();
        }
        POSTGRES.stop();
        RABBITMQ.stop();
    }

    @DynamicPropertySource
    static void bindProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ShadowLedger shadowLedger;

    @Autowired
    private StringRedisTemplate redis;

    private static final String ACCOUNT = "ACC-OUTAGE-TEST";

    @Test
    @Order(1)
    void seedAndReadBalanceWhileRedisIsUp() {
        redis.opsForValue().set("balance:" + ACCOUNT, "500000");

        BigDecimal balance = shadowLedger.getAvailableBalance(ACCOUNT);
        assertThat(balance).isEqualByComparingTo(new BigDecimal("5000.00"));
    }

    @Test
    @Order(2)
    void redisOutageSurfacesAsRedisConnectionFailureException() {
        REDIS.stop();

        assertThatThrownBy(() -> shadowLedger.getAvailableBalance(ACCOUNT))
                .isInstanceOf(RedisConnectionFailureException.class);
    }
}
