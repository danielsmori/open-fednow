package io.openfednow.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * HTTP implementation of {@link FedNowClient}.
 *
 * <p>POSTs pacs.008 messages as JSON to
 * {@code {openfednow.gateway.fednow-endpoint}/transfers} and deserializes
 * the pacs.002 response body. Network errors and HTTP error responses are
 * caught and converted to a synthetic RJCT pacs.002 — callers always receive
 * a well-formed {@link Pacs002Message}, never a raw exception.
 *
 * <p>A package-private constructor (taking the endpoint URL and timeout seconds
 * directly) is provided for use in {@code FedNowSimulatorTest}, which points
 * this client at a WireMock server rather than the real FedNow endpoint.
 */
@Component
public class HttpFedNowClient implements FedNowClient {

    /** Path appended to the configured base endpoint for credit transfer submission. */
    static final String TRANSFERS_PATH = "/transfers";

    private final String fednowEndpoint;
    private final RestTemplate restTemplate;

    /** Spring-managed constructor — reads endpoint and timeout from application properties. */
    public HttpFedNowClient(
            @Value("${openfednow.gateway.fednow-endpoint}") String fednowEndpoint,
            @Value("${openfednow.gateway.response-timeout-seconds}") int timeoutSeconds) {
        this.fednowEndpoint = fednowEndpoint;
        this.restTemplate = buildRestTemplate(timeoutSeconds);
    }

    /**
     * Package-private: supply the endpoint URL and timeout directly.
     * Used by {@code FedNowSimulatorTest} to target a local WireMock server.
     */
    HttpFedNowClient(String fednowEndpoint, int timeoutSeconds) {
        this.fednowEndpoint = fednowEndpoint;
        this.restTemplate = buildRestTemplate(timeoutSeconds);
    }

    @Override
    public Pacs002Message submitCreditTransfer(Pacs008Message message) {
        String url = fednowEndpoint + TRANSFERS_PATH;
        try {
            Pacs002Message response = restTemplate.postForObject(url, message, Pacs002Message.class);
            if (response == null) {
                return Pacs002Message.rejected(
                        message.getEndToEndId(), message.getTransactionId(),
                        "NARR", "FedNow returned an empty response body");
            }
            return response;
        } catch (HttpStatusCodeException e) {
            return Pacs002Message.rejected(
                    message.getEndToEndId(), message.getTransactionId(),
                    "NARR",
                    "FedNow returned HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            return Pacs002Message.rejected(
                    message.getEndToEndId(), message.getTransactionId(),
                    "NARR",
                    "FedNow connection timeout or network error: " + e.getMessage());
        }
    }

    private static RestTemplate buildRestTemplate(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutSeconds * 1000);
        factory.setReadTimeout(timeoutSeconds * 1000);

        // Register the Java Time module so OffsetDateTime fields in pacs.002
        // responses are deserialized correctly without Spring Boot's auto-config.
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(objectMapper);

        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.setMessageConverters(List.of(converter));
        return restTemplate;
    }
}
