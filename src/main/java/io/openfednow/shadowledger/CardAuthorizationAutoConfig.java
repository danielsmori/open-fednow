package io.openfednow.shadowledger;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for real-time card authorization event processing.
 *
 * <p>Wires the fallback {@link NoOpCardAuthorizationEventListener} as the default
 * {@link CardAuthorizationEventListener} bean when no vendor-specific adapter or
 * custom implementation has been registered by the institution.
 */
@AutoConfiguration
@ConditionalOnProperty(
        name = "openfednow.shadowledger.card-listener.provider",
        havingValue = "noop",
        matchIfMissing = true
)
public class CardAuthorizationAutoConfig {

    /**
     * Creates the fallback card authorization event listener bean.
     *
     * <p>Only activated when {@code openfednow.shadowledger.card-listener.provider}
     * resolves to {@code noop} (or is omitted entirely) and no other bean of type
     * {@link CardAuthorizationEventListener} exists in the context.
     *
     * <p>This allows the framework to operate out-of-the-box without requiring a
     * live card processor event stream, which is ideal for local development, isolation
     * testing, or deployments where card network synchronization is not enabled.
     */
    @Bean
    @ConditionalOnMissingBean(CardAuthorizationEventListener.class)
    public CardAuthorizationEventListener noOpCardAuthorizationEventListener() {
        return new NoOpCardAuthorizationEventListener();
    }
}