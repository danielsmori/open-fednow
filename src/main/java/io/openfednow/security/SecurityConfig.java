package io.openfednow.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
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
            // CORS uses our explicit deny-by-default configuration source —
            // see corsConfigurationSource() below.
            .cors(Customizer.withDefaults())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().permitAll()
            )
            // Spring Security 6 enables a default header set (X-Content-Type-Options,
            // X-Frame-Options DENY, Cache-Control, X-XSS-Protection: 0). HSTS is
            // opt-in — enable it explicitly so any TLS-terminated deployment instructs
            // compliant browsers to remember the HTTPS-only policy. The Ingress /
            // load balancer is the actual enforcer; this is defense in depth.
            .headers(headers -> headers
                .httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        // 2 years — the OWASP recommended baseline; long enough that
                        // browsers won't fall back to plaintext between visits.
                        .maxAgeInSeconds(63_072_000)))
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    /**
     * CORS posture: <strong>deny by default</strong>.
     *
     * <p>OpenFedNow is a server-to-server API — FedNow and TCH submit requests
     * over mTLS, the institution's core banking adapter calls outbound. No
     * browser-origin client should ever hit these endpoints. The empty
     * configuration source registered here makes that intent explicit: there
     * is no allow-list, so every preflight is denied.
     *
     * <p>An institution that builds a browser-based admin console can override
     * this bean with their own {@link CorsConfigurationSource} carrying the
     * appropriate allow-list — no other framework code needs to change.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        // Returning a configuration with no allowed origins / methods / headers
        // means Spring Security responds to preflights with the request rejected.
        // A future admin UI override would replace this bean with one carrying
        // an institution-specific allow-list.
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", new CorsConfiguration());
        return source;
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
