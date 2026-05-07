package io.openfednow.infrastructure;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for infrastructure integration tests.
 *
 * <p>Starts Redis, RabbitMQ, and PostgreSQL containers once for the entire
 * test suite (containers are {@code static} and shared across subclasses).
 * Each subclass gets a fully configured Spring context pointed at the
 * running containers via {@link DynamicPropertySource}.
 *
 * <p>Subclasses should be annotated with {@code @SpringBootTest} and focus
 * their tests on a single infrastructure concern (Redis, RabbitMQ, or
 * PostgreSQL). All three containers must be available for the Spring context
 * to start cleanly regardless of which concern is under test.
 *
 * <h2>Container images</h2>
 * Images are pinned to the same major versions used in {@code docker-compose.yml}
 * and the GitHub Actions CI service definitions:
 * <ul>
 *   <li>Redis 7 (Alpine)</li>
 *   <li>RabbitMQ 3 with management plugin</li>
 *   <li>PostgreSQL 16 (Alpine)</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class AbstractInfrastructureIntegrationTest {

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management"));

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("openfednow")
                    .withUsername("openfednow")
                    .withPassword("openfednow");

    static {
        // Start all containers before any Spring context is created.
        // Testcontainers reuses running containers within the same JVM,
        // so the cost is paid once per test-suite execution.
        REDIS.start();
        RABBITMQ.start();
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // Redis
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);

        // RabbitMQ
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);

        // PostgreSQL (replaces the H2 default; Flyway runs automatically on context load)
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
