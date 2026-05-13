package io.openfednow.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the outbound FedNow HTTP client.
 *
 * <p>Wires {@link HttpFedNowClient} as the {@link FedNowClient} bean,
 * injecting the FedNow endpoint URL and response timeout from
 * application properties. Keeping the {@code @Value} bindings here
 * means {@link HttpFedNowClient} has a single plain constructor that
 * tests can call directly without a Spring context.
 */
@Configuration
public class FedNowClientConfig {

    /**
     * Creates the HTTP FedNow client as the named bean {@code httpFedNowClient}.
     *
     * <p>Only activated when {@code openfednow.gateway.fednow-endpoint} is set
     * (i.e., the {@code FEDNOW_ENDPOINT} environment variable is provided). When the
     * property is absent, this bean is not created and {@link SandboxFedNowClient}
     * activates instead via {@code @ConditionalOnMissingBean(name = "httpFedNowClient")}.
     */
    @Bean(name = "httpFedNowClient")
    @ConditionalOnProperty(name = "openfednow.gateway.fednow-endpoint", matchIfMissing = false)
    public FedNowClient fedNowClient(
            @Value("${openfednow.gateway.fednow-endpoint}") String endpoint,
            @Value("${openfednow.gateway.response-timeout-seconds}") int timeoutSeconds) {
        return new HttpFedNowClient(endpoint, timeoutSeconds);
    }
}
