package io.openfednow.gateway;

import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP implementation of {@link RtpClient}.
 *
 * <p>Serializes pacs.008 messages to canonical ISO 20022 XML via
 * {@link RtpXmlSerializer#serializePacs008}, POSTs them to
 * {@code {rtpEndpoint}/transfers}, and parses the pacs.002 XML response via
 * {@link RtpXmlSerializer#parsePacs002}. Network errors and HTTP error responses
 * are caught and converted to a synthetic RJCT pacs.002 — callers always receive
 * a well-formed {@link Pacs002Message}, never a raw exception.
 *
 * <p>This class is not a {@code @Component}; it is created by {@link RtpClientConfig}
 * as a Spring bean so the endpoint URL and timeout can be injected from application
 * properties. Tests instantiate it directly via the public constructor, pointing it
 * at a WireMock base URL — the same pattern used by {@link HttpFedNowClient}.
 *
 * <h2>Protocol differences from FedNow</h2>
 * <p>Unlike {@link HttpFedNowClient}, which uses JSON ({@code application/json}),
 * this client uses the canonical ISO 20022 XML format ({@code application/xml}).
 * Both serialization and deserialization are handled by {@link RtpXmlSerializer}.
 *
 * <h2>Production requirements</h2>
 * <p>Live TCH connectivity additionally requires:
 * <ul>
 *   <li>TCH institutional participation agreement and network access keys</li>
 *   <li>TCH PKI client certificates for mutual TLS</li>
 *   <li>Dedicated private-network connectivity to the TCH RTP® network</li>
 * </ul>
 * These are institution-provided credentials — the same class of dependency as
 * Federal Reserve PKI for live FedNow deployment.
 *
 * @see RtpClientConfig
 * @see RtpXmlSerializer
 */
public class HttpRtpClient implements RtpClient {

    private static final Logger log = LoggerFactory.getLogger(HttpRtpClient.class);

    /** Path appended to the configured base endpoint for credit transfer submission. */
    static final String TRANSFERS_PATH = "/transfers";

    private final String rtpEndpoint;
    private final RestClient restClient;
    private final RtpXmlSerializer xmlSerializer;

    /**
     * Creates a client targeting the given endpoint with the specified timeout.
     * Called by {@link RtpClientConfig} for Spring-managed usage, and directly
     * from {@code HttpRtpClientTest} with a WireMock base URL.
     *
     * @param rtpEndpoint    base URL of the TCH RTP endpoint (no trailing slash)
     * @param timeoutSeconds connect and read timeout applied to every request
     * @param xmlSerializer  serializer/parser for ISO 20022 XML envelopes
     */
    public HttpRtpClient(String rtpEndpoint, int timeoutSeconds, RtpXmlSerializer xmlSerializer) {
        this.rtpEndpoint = rtpEndpoint;
        this.xmlSerializer = xmlSerializer;
        this.restClient = buildRestClient(rtpEndpoint, timeoutSeconds);
    }

    @Override
    public Pacs002Message submitCreditTransfer(Pacs008Message message) {
        String url = rtpEndpoint + TRANSFERS_PATH;
        log.info("Submitting pacs.008 to RTP endpoint={}", url);

        String requestXml = xmlSerializer.serializePacs008(message);

        try {
            String responseXml = restClient.post()
                    .uri(TRANSFERS_PATH)
                    .contentType(MediaType.APPLICATION_XML)
                    .body(requestXml)
                    .retrieve()
                    .body(String.class);

            if (responseXml == null || responseXml.isBlank()) {
                log.warn("RTP endpoint returned empty response body");
                return Pacs002Message.rejected(
                        message.getEndToEndId(), message.getTransactionId(),
                        "NARR", "RTP endpoint returned an empty response body");
            }

            Pacs002Message response = xmlSerializer.parsePacs002(responseXml);
            log.debug("RTP raw response messageId={}", response.getMessageId());
            return response;

        } catch (HttpStatusCodeException e) {
            log.warn("RTP endpoint returned HTTP error status={}", e.getStatusCode().value());
            return Pacs002Message.rejected(
                    message.getEndToEndId(), message.getTransactionId(),
                    "NARR",
                    "RTP endpoint returned HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            log.warn("RTP connection error: {}", e.getMessage());
            return Pacs002Message.rejected(
                    message.getEndToEndId(), message.getTransactionId(),
                    "NARR",
                    "RTP connection timeout or network error: " + e.getMessage());
        } catch (RestClientException e) {
            log.warn("RTP client error: {}", e.getMessage());
            return Pacs002Message.rejected(
                    message.getEndToEndId(), message.getTransactionId(),
                    "NARR",
                    "RTP network error: " + e.getMessage());
        }
    }

    private static RestClient buildRestClient(String baseUrl, int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutSeconds * 1000);
        factory.setReadTimeout(timeoutSeconds * 1000);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
