package io.openfednow.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the conditional bean wiring for {@link RtpClient}.
 *
 * <p>Uses {@link ApplicationContextRunner} (no server, no Testcontainers) to
 * confirm that:
 * <ul>
 *   <li>{@link SandboxRtpClient} activates when {@code openfednow.gateway.rtp-endpoint}
 *       is absent — the default case for local development.</li>
 *   <li>{@link HttpRtpClient} activates when the property is present with a URL value.</li>
 * </ul>
 *
 * <p>These two tests together document and enforce the config fix that removed
 * {@code rtp-endpoint: ${RTP_ENDPOINT:}} from {@code application.yml} — the blank
 * default was silently activating {@link HttpRtpClient} with an empty endpoint.
 */
class RtpClientBeanConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RtpClientConfig.class, SandboxRtpClient.class, RtpXmlSerializer.class)
            .withPropertyValues("openfednow.gateway.response-timeout-seconds=18");

    @Test
    void withoutRtpEndpointProperty_sandboxRtpClientActivates() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(RtpClient.class);
            assertThat(ctx.getBean(RtpClient.class)).isInstanceOf(SandboxRtpClient.class);
        });
    }

    @Test
    void withRtpEndpointProperty_httpRtpClientActivates() {
        runner
                .withPropertyValues("openfednow.gateway.rtp-endpoint=http://tch-simulator.example.com")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RtpClient.class);
                    assertThat(ctx.getBean(RtpClient.class)).isInstanceOf(HttpRtpClient.class);
                });
    }
}
