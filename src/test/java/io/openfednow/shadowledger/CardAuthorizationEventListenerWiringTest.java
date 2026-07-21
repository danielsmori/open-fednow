package io.openfednow.shadowledger;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the conditional wiring of the card authorization layer.
 *
 * <p>Tests the autoconfiguration lifecycle and lifecycle back-off mechanics:
 * <ol>
 *   <li>Verifies that {@link CardAuthorizationConfig} activates a default fallback when no bean is present.</li>
 *   <li>Verifies that a custom core or vendor-provided implementation forces the autoconfiguration to back off.</li>
 * </ol>
 *
 * <p>The conditional matching is evaluated via the Spring Boot {@link ApplicationContextRunner} utility
 * which simulates clean application startup states during testing.
 */
class CardAuthorizationEventListenerWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CardAuthorizationConfig.class);

    // ── Conditional bean auto-wiring ──────────────────────────────────────────

    @Test
    void whenNoCustomBeanPresent_thenFallbackToNoOpImplementation() {
        this.contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(CardAuthorizationEventListener.class);
                    assertThat(context.getBean(CardAuthorizationEventListener.class))
                            .isInstanceOf(NoOpCardAuthorizationEventListener.class);
                });
    }

    @Test
    void whenCustomBeanExplicitlyRegistered_thenNoOpImplementationBacksOff() {
        this.contextRunner
                .withBean("customCardListener", CardAuthorizationEventListener.class,
                        () -> new CardAuthorizationEventListener() {
                            @Override public void onAuthorization(String acct, BigDecimal amt, String code) {}
                            @Override public void onReversal(String acct, BigDecimal amt, String code) {}
                        })
                .run(context -> {
                    assertThat(context).hasSingleBean(CardAuthorizationEventListener.class);
                    assertThat(context.getBean(CardAuthorizationEventListener.class))
                            .isNotInstanceOf(NoOpCardAuthorizationEventListener.class);
                });
    }

}