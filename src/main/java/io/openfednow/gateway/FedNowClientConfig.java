package io.openfednow.gateway;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the outbound FedNow HTTP client.
 *
 * <p>Wires {@link HttpFedNowClient} as the {@link FedNowClient} bean,
 * injecting the FedNow endpoint URL, response timeout, and the
 * {@code fednow} Retry instance from application properties. Keeping the
 * {@code @Value} bindings here means {@link HttpFedNowClient} has plain
 * constructors that tests can call directly without a Spring context.
 */
@Configuration
public class FedNowClientConfig {

    /** Name of the Resilience4j Retry instance applied to outbound FedNow calls. */
    static final String FEDNOW_RETRY_NAME = "fednow";

    /**
     * Creates the HTTP FedNow client as the named bean {@code httpFedNowClient}.
     *
     * <p>Only activated when {@code openfednow.gateway.fednow-endpoint} is set
     * (i.e., the {@code FEDNOW_ENDPOINT} environment variable is provided). When the
     * property is absent, this bean is not created and {@link SandboxFedNowClient}
     * activates instead via {@code @ConditionalOnMissingBean(name = "httpFedNowClient")}.
     *
     * <p>The Retry policy is looked up from the {@link RetryRegistry} by the well-known
     * name {@code fednow}; if the registry does not contain a configuration for that
     * name, Resilience4j returns a sensible default (3 attempts, fixed wait). The
     * production configuration in {@code application.yml} overrides this with a
     * predicate that retries network failures and 5xx but not 4xx.
     */
    @Bean(name = "httpFedNowClient")
    @ConditionalOnProperty(name = "openfednow.gateway.fednow-endpoint", matchIfMissing = false)
    public FedNowClient fedNowClient(
            @Value("${openfednow.gateway.fednow-endpoint}") String endpoint,
            @Value("${openfednow.gateway.response-timeout-seconds}") int timeoutSeconds,
            RetryRegistry retryRegistry) {
        Retry retry = retryRegistry.retry(FEDNOW_RETRY_NAME);
        return new HttpFedNowClient(endpoint, timeoutSeconds, retry);
    }
}
