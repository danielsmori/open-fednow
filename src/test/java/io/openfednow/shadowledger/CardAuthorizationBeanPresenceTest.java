package io.openfednow.shadowledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying full application context component scanning and bean presence.
 *
 * <p>Validates that {@link CardAuthorizationConfig} correctly registers a
 * {@link CardAuthorizationEventListener} bean within a full Spring Boot runtime environment,
 * ensuring standard component scanning behavior is maintained without autoconfiguration filtering issues.
 */
@SpringBootTest
class CardAuthorizationBeanPresenceTest {

    @Autowired(required = false)
    private CardAuthorizationEventListener listener;

    /**
     * Verifies that a {@link CardAuthorizationEventListener} bean is wired and available
     * in the application context at runtime.
     */
    @Test
    void beanIsPresentAtRuntime() {
        assertThat(listener).isNotNull();
    }
}