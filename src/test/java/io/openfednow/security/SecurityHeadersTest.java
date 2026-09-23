package io.openfednow.security;

import io.openfednow.infrastructure.AbstractInfrastructureIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the HTTP security response headers configured in {@link SecurityConfig}.
 *
 * <p>Spring Security 6 enables the default header set automatically; this test
 * exists so a future refactor (e.g., switching to a custom header chain) can't
 * silently drop them. HSTS is configured explicitly and is verified here too.
 *
 * <p>Drives a real HTTP port via {@link TestRestTemplate} so the full filter
 * chain is exercised end-to-end.
 */
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityHeadersTest extends AbstractInfrastructureIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mvc;

    @Test
    void responseHasXContentTypeOptionsHeader() {
        HttpHeaders headers = anyEndpointHeaders();
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void responseHasXFrameOptionsDeny() {
        HttpHeaders headers = anyEndpointHeaders();
        // Spring Security default: DENY
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
    }

    @Test
    void secureRequestsHaveStrictTransportSecurity() throws Exception {
        for (String path : new String[]{"/actuator/health", "/fednow/health"}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).secure(true))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                            .string("Strict-Transport-Security", "max-age=63072000 ; includeSubDomains"));
        }
    }

    @Test
    void plaintextRequestsDoNotAdvertiseStrictTransportSecurity() {
        assertThat(anyEndpointHeaders().getFirst("Strict-Transport-Security")).isNull();
    }

    @Test
    void responseHasCacheControl() {
        HttpHeaders headers = anyEndpointHeaders();
        // Spring Security defaults set no-cache directives so admin / auth responses
        // are not cached by intermediaries.
        String cacheControl = headers.getFirst("Cache-Control");
        assertThat(cacheControl).isNotNull();
        assertThat(cacheControl).containsAnyOf("no-cache", "no-store");
    }

    @Test
    void healthEndpointAlsoCarriesSecurityHeaders() {
        // Even unauthenticated paths get the security headers — the chain is global.
        ResponseEntity<String> response = restTemplate.getForEntity("/fednow/health", String.class);
        HttpHeaders headers = response.getHeaders();
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        // Spring Security only emits HSTS for secure requests.
        assertThat(headers.getFirst("Strict-Transport-Security")).isNull();
    }

    private HttpHeaders anyEndpointHeaders() {
        // /actuator/health is permitAll-ed and stable across environments
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        return response.getHeaders();
    }
}
