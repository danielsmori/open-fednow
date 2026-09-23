package io.openfednow.shadowledger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.annotation.DirtiesContext;
import io.lettuce.core.RedisException;
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

/**
 * Exercises Shadow Ledger behavior when Redis is unreachable.
 *
 * <p>Unlike {@link RedisIntegrationTest} — which shares the base container with
 * every other infrastructure test — this class owns its own Redis instance and
 * deliberately stops it mid-test. Stopping the shared container would break
 * every other integration test, so isolation is intentional.
 *
 * <p>An outage must surface as a Redis data-access error, not a fabricated
 * balance. Depending on whether Lettuce reconnects or an established connection
 * closes mid-command or a queued command times out, Spring may use different
 * DataAccessException subtypes; the underlying Redis error must remain visible.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.data.redis.timeout=1s", "spring.data.redis.connect-timeout=1s"
})
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
        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("Docker is required for the integration suite").isTrue();
        REDIS.start();
        POSTGRES.start();
        RABBITMQ.start();
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
    void redisOutageSurfacesAsRedisDataAccessError() {
        REDIS.stop();

        assertThatThrownBy(() -> shadowLedger.getAvailableBalance(ACCOUNT))
                .isInstanceOf(DataAccessException.class)
                .hasCauseInstanceOf(RedisException.class);
    }
}
