package io.openfednow.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.resilience4j.retry.Retry;
import io.openfednow.gateway.signing.FedNowJwsSigner;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
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

    /** HTTP header FedNow uses to convey the JWS detached signature. */
    static final String JWS_HEADER = "X-JWS-Signature";

    private final String fednowEndpoint;
    private final RestTemplate restTemplate;
    private final Retry retry;

    /**
     * Creates a client with no retry behavior and no request signing. Test paths
     * against the WireMock simulator use this overload directly.
     */
    public HttpFedNowClient(String fednowEndpoint, int timeoutSeconds) {
        this(fednowEndpoint, timeoutSeconds, null, null);
    }

    /**
     * Retry-enabled constructor without a signer — preserved for tests that
     * exercise the retry behavior without needing to set up a keypair.
     */
    public HttpFedNowClient(String fednowEndpoint, int timeoutSeconds, Retry retry) {
        this(fednowEndpoint, timeoutSeconds, retry, null);
    }

    /**
     * Full constructor: endpoint, timeout, retry policy, and optional JWS signer.
     *
     * <p>When a {@link Retry} is provided, transient network failures and 5xx
     * responses from FedNow are retried per the policy's configured backoff
     * before falling through to the synthetic RJCT path. 4xx responses are
     * <em>not</em> retried — a malformed message won't become well-formed by
     * trying again, and a duplicate-detection rejection should be surfaced
     * to the caller immediately.
     *
     * <p>When a {@link FedNowJwsSigner} is provided, the client installs a
     * {@link ClientHttpRequestInterceptor} that computes a JWS detached signature
     * over the fully-serialized request body and attaches it as the
     * {@code X-JWS-Signature} HTTP header. Signing happens once per attempt
     * (including retries) because the body bytes are handed to the interceptor
     * on every call.
     *
     * <p>Idempotency is guaranteed at the FedNow side via the message's
     * {@code EndToEndId} — a retried submission with the same signature and
     * body is treated as a resubmission of the same logical message.
     *
     * @param fednowEndpoint base URL of the FedNow endpoint (no trailing slash)
     * @param timeoutSeconds connect and read timeout applied to every individual request
     * @param retry          retry policy from {@code resilience4j.retry.instances.fednow},
     *                       or {@code null} to disable retries
     * @param signer         JWS signer bean from {@code FedNowSigningConfig},
     *                       or {@code null} to send unsigned requests (sandbox / dev)
     */
    public HttpFedNowClient(String fednowEndpoint, int timeoutSeconds, Retry retry,
                            FedNowJwsSigner signer) {
        this.fednowEndpoint = fednowEndpoint;
        this.restTemplate = buildRestTemplate(timeoutSeconds);
        if (signer != null) {
            this.restTemplate.setInterceptors(List.of(new JwsSigningInterceptor(signer)));
            log.info("FedNow outbound signing active — every submission carries X-JWS-Signature");
        }
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

    /**
     * Adds the {@code X-JWS-Signature} header computed over the request body
     * by the injected {@link FedNowJwsSigner}. Applied on every attempt, so
     * a retry after a 5xx recomputes the signature — necessary because a
     * middlebox could rewrite the body (though FedNow forbids this in prod,
     * a defensive re-sign is cheap and correct).
     */
    static final class JwsSigningInterceptor implements ClientHttpRequestInterceptor {
        private final FedNowJwsSigner signer;

        JwsSigningInterceptor(FedNowJwsSigner signer) {
            this.signer = signer;
        }

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            String jws = signer.sign(body);
            request.getHeaders().set(JWS_HEADER, jws);
            return execution.execute(request, body);
        }
    }
}
