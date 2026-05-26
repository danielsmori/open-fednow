package io.openfednow.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.resilience4j.retry.Retry;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.function.Supplier;

/**
 * HTTP implementation of {@link FedNowClient}.
 *
 * <p>POSTs pacs.008 messages as JSON to
 * {@code {fednowEndpoint}/transfers} and deserializes the pacs.002 response.
 * Network errors and HTTP error responses are caught and converted to a
 * synthetic RJCT pacs.002 — callers always receive a well-formed
 * {@link Pacs002Message}, never a raw exception.
 *
 * <p>This class is not a {@code @Component}; it is created by
 * {@link FedNowClientConfig} as a Spring bean so the endpoint URL and timeout
 * can be injected from application properties. Tests instantiate it directly
 * via the single public constructor, pointing it at a WireMock base URL.
 */
public class HttpFedNowClient implements FedNowClient {

    private static final Logger log = LoggerFactory.getLogger(HttpFedNowClient.class);

    /** Path appended to the configured base endpoint for credit transfer submission. */
    static final String TRANSFERS_PATH = "/transfers";

    private final String fednowEndpoint;
    private final RestTemplate restTemplate;
    private final Retry retry;

    /**
     * Creates a client with no retry behavior. Equivalent to {@link #HttpFedNowClient(String, int, Retry)}
     * with {@code retry = null}. Test paths use this overload directly.
     */
    public HttpFedNowClient(String fednowEndpoint, int timeoutSeconds) {
        this(fednowEndpoint, timeoutSeconds, null);
    }

    /**
     * Creates a client targeting the given endpoint with the specified timeout
     * and an optional retry policy.
     *
     * <p>When a {@link Retry} is provided, transient network failures and 5xx
     * responses from FedNow are retried per the policy's configured backoff
     * before falling through to the synthetic RJCT path. 4xx responses are
     * <em>not</em> retried — a malformed message won't become well-formed by
     * trying again, and a duplicate-detection rejection should be surfaced
     * to the caller immediately.
     *
     * <p>Idempotency is guaranteed at the FedNow side via the message's
     * {@code EndToEndId} — if a retried submission lands a second time,
     * FedNow returns its cached response for that ID rather than processing
     * twice.
     *
     * @param fednowEndpoint base URL of the FedNow endpoint (no trailing slash)
     * @param timeoutSeconds connect and read timeout applied to every individual request
     * @param retry          retry policy from {@code resilience4j.retry.instances.fednow},
     *                       or {@code null} to disable retries
     */
    public HttpFedNowClient(String fednowEndpoint, int timeoutSeconds, Retry retry) {
        this.fednowEndpoint = fednowEndpoint;
        this.restTemplate = buildRestTemplate(timeoutSeconds);
        this.retry = retry;
    }

    @Override
    public Pacs002Message submitCreditTransfer(Pacs008Message message) {
        String url = fednowEndpoint + TRANSFERS_PATH;
        log.info("Submitting pacs.008 to FedNow endpoint={}", url);

        Supplier<Pacs002Message> attempt = () -> restTemplate.postForObject(url, message, Pacs002Message.class);
        if (retry != null) {
            attempt = Retry.decorateSupplier(retry, attempt);
        }

        try {
            Pacs002Message response = attempt.get();
            if (response == null) {
                log.warn("FedNow returned empty response body");
                return Pacs002Message.rejected(
                        message.getEndToEndId(), message.getTransactionId(),
                        "NARR", "FedNow returned an empty response body");
            }
            log.debug("FedNow raw response messageId={}", response.getMessageId());
            return response;
        } catch (HttpClientErrorException e) {
            // 4xx — client error; the retry policy is configured not to attempt these
            log.warn("FedNow returned client error status={}", e.getStatusCode().value());
            return Pacs002Message.rejected(
                    message.getEndToEndId(), message.getTransactionId(),
                    "NARR",
                    "FedNow returned HTTP " + e.getStatusCode().value());
        } catch (HttpStatusCodeException e) {
            // 5xx after exhausted retries (or no retry policy configured)
            log.warn("FedNow returned server error status={} (after retries if any)",
                    e.getStatusCode().value());
            return Pacs002Message.rejected(
                    message.getEndToEndId(), message.getTransactionId(),
                    "NARR",
                    "FedNow returned HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            log.warn("FedNow connection error (after retries if any): {}", e.getMessage());
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

        // Register JavaTimeModule so OffsetDateTime fields in pacs.002 responses
        // deserialize correctly without Spring Boot's auto-configured ObjectMapper.
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
