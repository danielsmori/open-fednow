package io.openfednow.infrastructure;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for infrastructure integration tests.
 *
 * <p>Declares Redis, RabbitMQ, and PostgreSQL containers as {@code @Container} static
 * fields so that the Testcontainers JUnit 5 extension manages their lifecycle:
 * containers are started once before the first test class that references them and
 * shared across all subclasses (because the fields are {@code static}).
 *
 * <p>{@code @Testcontainers(disabledWithoutDocker = true)} causes the entire test
 * class to be <em>skipped</em> (rather than failing) when the Docker daemon is not
 * available. This prevents cascading {@code NoClassDefFoundError} errors in local
 * environments where Docker is not running.
 *
 * <p>Each subclass should be annotated with {@code @SpringBootTest} and focus its
 * tests on a single infrastructure concern. All three containers must be available
 * for the Spring context to start cleanly, regardless of which concern is under test.
 *
 * <h2>Container images</h2>
 * Pinned to the same major versions used in {@code docker-compose.yml} and the
 * GitHub Actions CI service definitions:
 * <ul>
 *   <li>Redis 7 (Alpine)</li>
 *   <li>RabbitMQ 3 with management plugin</li>
 *   <li>PostgreSQL 16 (Alpine)</li>
 * </ul>
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class AbstractInfrastructureIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @Container
    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management"));

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("openfednow")
                    .withUsername("openfednow")
                    .withPassword("openfednow");

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
