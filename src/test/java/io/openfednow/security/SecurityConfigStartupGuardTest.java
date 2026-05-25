package io.openfednow.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the startup-time check that refuses to boot the prod profile on
 * default admin credentials.
 *
 * <p>Uses {@link ApplicationContextRunner} so the test loads {@link SecurityConfig}
 * in isolation (no full Spring Boot test, no Testcontainers) — the check is
 * about Spring lifecycle and environment, not about HTTP behavior.
 */
class SecurityConfigStartupGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SecurityConfig.class);

    @Test
    void prodProfileWithDefaultPasswordRefusesToStart() {
        runner
                .withPropertyValues("spring.profiles.active=prod")
                // Default username + default password — exactly the values that ship in application.yml
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("admin credentials");
                });
    }

    @Test
    void prodProfileWithDefaultUsernameRefusesToStart() {
        // Even a custom password isn't enough — using the literal "admin" username
        // means an attacker doesn't need to enumerate the username.
        runner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "openfednow.admin.password=A-Strong-Custom-Password-2026!")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void prodProfileWithBothOverridesStarts() {
        runner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "openfednow.admin.username=operator",
                        "openfednow.admin.password=A-Strong-Custom-Password-2026!")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void devProfileWithDefaultCredentialsStartsNormally() {
        // The check is profile-gated — dev / sandbox keep the convenience defaults
        runner
                .withPropertyValues("spring.profiles.active=dev")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void noActiveProfileStartsNormally() {
        // Local mvn spring-boot:run with no profile must still work
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void prodAndOtherProfileCombinedStillEnforces() {
        // SPRING_PROFILES_ACTIVE=prod,observability scenarios — prod present anywhere triggers the check
        runner
                .withPropertyValues("spring.profiles.active=observability,prod")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class);
                });
    }
}
