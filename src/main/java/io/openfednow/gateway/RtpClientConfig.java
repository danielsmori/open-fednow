package io.openfednow.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the outbound RTP HTTP client.
 *
 * <p>Wires {@link HttpRtpClient} as the {@link RtpClient} bean, injecting the
 * TCH endpoint URL and response timeout from application properties. Keeping the
 * {@code @Value} bindings here means {@link HttpRtpClient} has a plain constructor
 * that tests can call directly without a Spring context — the same pattern used by
 * {@link FedNowClientConfig} for the FedNow rail.
 */
@Configuration
public class RtpClientConfig {

    /**
     * Creates the HTTP RTP client as the named bean {@code httpRtpClient}.
     *
     * <p>Only activated when {@code openfednow.gateway.rtp-endpoint} is set
     * (i.e., the {@code RTP_ENDPOINT} environment variable is provided). When the
     * property is absent, this bean is not created and {@link SandboxRtpClient}
     * activates instead via {@code @ConditionalOnMissingBean(name = "httpRtpClient")}.
     *
     * <p>Live TCH connectivity additionally requires TCH institutional participation,
     * PKI certificates, and private-network transport — the same class of credential
     * dependency as Federal Reserve PKI for live FedNow deployment.
     */
    @Bean(name = "httpRtpClient")
    @ConditionalOnProperty(name = "openfednow.gateway.rtp-endpoint", matchIfMissing = false)
    public RtpClient rtpClient(
            @Value("${openfednow.gateway.rtp-endpoint}") String endpoint,
            @Value("${openfednow.gateway.response-timeout-seconds}") int timeoutSeconds,
            RtpXmlSerializer xmlSerializer) {
        return new HttpRtpClient(endpoint, timeoutSeconds, xmlSerializer);
    }
}
