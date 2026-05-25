package io.openfednow.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Reference security configuration for OpenFedNow administrative endpoints.
 *
 * <p>Access model:
 * <ul>
 *   <li>{@code /admin/**} — HTTP Basic authentication, {@code ADMIN} role required.
 *       Credentials are injected from {@code openfednow.admin.username} /
 *       {@code openfednow.admin.password} (configurable via environment variables
 *       {@code ADMIN_USERNAME} / {@code ADMIN_PASSWORD}).</li>
 *   <li>{@code /fednow/**}, {@code /rtp/**} — open (mutual TLS is enforced at the
 *       infrastructure/Ingress level in production; this layer does not duplicate it).</li>
 *   <li>{@code /actuator/**} — open for internal monitoring; restrict at the
 *       network/Ingress level in production.</li>
 *   <li>All other paths — open.</li>
 * </ul>
 *
 * <p><strong>Production note:</strong> This is a reference implementation using
 * in-memory credentials suitable for sandbox/reference deployments. Production
 * deployments should integrate with the institution's IAM system (LDAP, OAuth 2.0,
 * mTLS client certificates, or a secrets-manager-backed credential store).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Default admin username carried in {@code application.yml} for sandbox / dev. */
    static final String DEFAULT_ADMIN_USERNAME = "admin";

    /** Default admin password carried in {@code application.yml} for sandbox / dev. */
    static final String DEFAULT_ADMIN_PASSWORD = "changeme";

    @Value("${openfednow.admin.username:" + DEFAULT_ADMIN_USERNAME + "}")
    private String adminUsername;

    @Value("${openfednow.admin.password:" + DEFAULT_ADMIN_PASSWORD + "}")
    private String adminPassword;

    private final Environment environment;

    public SecurityConfig(Environment environment) {
        this.environment = environment;
    }

    /**
     * Refuses to start the application when the prod profile is active and the
     * admin credentials are still at their {@code application.yml} defaults.
     *
     * <p>A regulated payment system that boots with {@code admin / changeme} is a
     * compliance finding waiting to happen. Failing loud at startup forces the
     * deployer to set {@code ADMIN_USERNAME} and {@code ADMIN_PASSWORD} before
     * the service can accept traffic. The check is profile-gated so the sandbox
     * and dev profiles continue to run with the convenience defaults.
     */
    @PostConstruct
    void verifyProdCredentials() {
        boolean prodActive = false;
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) {
                prodActive = true;
                break;
            }
        }
        if (!prodActive) {
            return;
        }
        if (DEFAULT_ADMIN_USERNAME.equals(adminUsername)
                || DEFAULT_ADMIN_PASSWORD.equals(adminPassword)) {
            throw new IllegalStateException(
                    "Refusing to start: the 'prod' profile is active but the admin credentials "
                            + "are still at their sandbox defaults. Set the ADMIN_USERNAME and "
                            + "ADMIN_PASSWORD environment variables (or override "
                            + "openfednow.admin.username / openfednow.admin.password) before "
                            + "launching production.");
        }
    }

    /**
     * Security filter chain:
     * <ul>
     *   <li>CSRF disabled — stateless REST API</li>
     *   <li>{@code /admin/**} requires ADMIN role via HTTP Basic</li>
     *   <li>All other paths permitted (FedNow mTLS handled at infrastructure level)</li>
     * </ul>
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().permitAll()
            )
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
            User.builder()
                .username(adminUsername)
                .password(encoder.encode(adminPassword))
                .roles("ADMIN")
                .build()
        );
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
