package io.openfednow.shadowledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the conditional wiring of the card authorization layer.
 *
 * <p>Tests the auto-configuration lifecycle and lifecycle back-off mechanics:
 * <ol>
 *   <li>Verifies that {@link CardAuthorizationAutoConfig} activates a default fallback when no bean is present.</li>
 *   <li>Verifies that a custom core or vendor-provided implementation forces the auto-configuration to back off.</li>
 * </ol>
 *
 * <p>The conditional matching is evaluated via the Spring Boot {@link ApplicationContextRunner} utility
 * which simulates clean application startup states during testing.
 */
class CardAuthorizationEventListenerWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CardAuthorizationAutoConfig.class));

    // ── Conditional bean auto-wiring ──────────────────────────────────────────

    @Test
    void whenNoCustomBeanPresent_thenFallbackToNoOpImplementation() {
        this.contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(CardAuthorizationEventListener.class);
                    assertThat(context).hasBean("noOpCardAuthorizationEventListener");
                });
    }

    @Test
    void whenCustomBeanExplicitlyRegistered_thenNoOpImplementationIsBacksOff() {
        this.contextRunner
                .withUserConfiguration(CustomListenerConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(CardAuthorizationEventListener.class);
                    assertThat(context).hasBean("customCardListener");
                    assertThat(context).doesNotHaveBean(NoOpCardAuthorizationEventListener.class);
                });
    }

    // ── Stub configurations for lifecycle verification ────────────────────────

    /**
     * Minimal user configuration simulating an custom downstream institution or core vendor implementation.
     */
    @Configuration
    static class CustomListenerConfig {

        /**
         * Registers a custom mock listener bean to trigger conditional back-off rules.
         *
         * @return a stubbed implementation of {@link CardAuthorizationEventListener}
         */
        @Bean
        public CardAuthorizationEventListener customCardListener() {
            return new CardAuthorizationEventListener() {
                @Override public void onAuthorization(String acct, long amt, String code) {}
                @Override public void onReversal(String acct, long amt, String code) {}
            };
        }
    }
}