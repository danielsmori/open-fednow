package io.openfednow.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Value("${openfednow.admin.username:admin}")
    private String adminUsername;

    @Value("${openfednow.admin.password:changeme}")
    private String adminPassword;

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
