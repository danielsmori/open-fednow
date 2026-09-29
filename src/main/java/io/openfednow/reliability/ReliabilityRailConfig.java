package io.openfednow.reliability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
public class ReliabilityRailConfig {
    private static final Logger log = LoggerFactory.getLogger(ReliabilityRailConfig.class);
    @Bean
    ReliabilityRailPort reliabilityRailPort(
            @Value("${openfednow.reliability.synthetic-rail-url:}") String endpoint,
            @Value("${openfednow.reliability.transport-timeout-millis:1500}") int timeoutMillis) {
        log.info("Synthetic reliability rail configured={}", !endpoint.isBlank());
        return endpoint.isBlank() ? new UnavailableRailPort()
                : new HttpSyntheticRailPort(endpoint, timeoutMillis);
    }
}
