package io.openfednow.acl.adapters;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.openfednow.acl.adapters.fiserv.FiservHttpClient;
import io.openfednow.acl.adapters.fiserv.FiservTokenManager;
import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.acl.core.CoreReliabilityCapabilities;
import io.openfednow.acl.core.CoreBankingResponse;
import io.openfednow.iso20022.Pacs008Message;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URI;

/**
 * Legacy Fiserv-shaped local fixture adapter. Its request paths, headers, amount
 * encoding and response mapping have not been matched to a selected Banking Hub
 * product/version or exercised against a Fiserv environment. Selecting this bean
 * does not establish core authority or enable the SQL reliability send path.
 * See docs/integrations/fiserv-banking-hub.md before using any external endpoint.
 */
@Component("fiservAdapter")
@ConditionalOnProperty(name = "openfednow.adapter", havingValue = "fiserv")
public class FiservAdapter implements CoreBankingAdapter {

    @Value("${openfednow.adapter-fiserv.base-url:}")
    private String baseUrl;

    @Value("${openfednow.adapter-fiserv.client-id:}")
    private String clientId;

    @Value("${openfednow.adapter-fiserv.client-secret:}")
    private String clientSecret;

    @Value("${openfednow.adapter-fiserv.api-key:}")
    private String apiKey;

    @Value("${openfednow.adapter-fiserv.token-path:/fts-apim/oauth2/v2}")
    private String tokenPath;

    @Value("${openfednow.adapter-fiserv.token-expiry-buffer-seconds:60}")
    private long tokenExpiryBufferSeconds;

    private FiservHttpClient httpClient;

    /** Spring constructor — fields injected via {@code @Value}; client built in {@link #init()}. */
    public FiservAdapter() {}

    /** Test constructor — injects a pre-configured {@link FiservHttpClient} directly. */
    FiservAdapter(FiservHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @PostConstruct
    void init() {
        if (httpClient != null) return; // already set via test constructor
        if (!isLoopbackFixtureUrl(baseUrl)) {
            throw new IllegalStateException("Legacy Fiserv fixture requires a loopback base URL");
        }
        RestClient restClient = RestClient.builder().baseUrl(baseUrl).build();
        FiservTokenManager tokenManager = new FiservTokenManager(
                restClient, tokenPath, clientId, clientSecret, tokenExpiryBufferSeconds);
        httpClient = new FiservHttpClient(restClient, tokenManager, apiKey);
    }

    static boolean isLoopbackFixtureUrl(String url) {
        try {
            URI uri = URI.create(url);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && ("localhost".equalsIgnoreCase(uri.getHost())
                    || "127.0.0.1".equals(uri.getHost())
                    || "[::1]".equals(uri.getHost()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ── CoreBankingAdapter ────────────────────────────────────────────────────

    @CircuitBreaker(name = "corebanking", fallbackMethod = "fallbackPostCreditTransfer")
    @Override
    public CoreBankingResponse postCreditTransfer(Pacs008Message message) {
        return httpClient.postTransaction(message);
    }

    @CircuitBreaker(name = "corebanking", fallbackMethod = "fallbackGetAvailableBalance")
    @Override
    public BigDecimal getAvailableBalance(String accountId) {
        return httpClient.getBalance(accountId);
    }

    @CircuitBreaker(name = "corebanking", fallbackMethod = "fallbackIsCoreSystemAvailable")
    @Override
    public boolean isCoreSystemAvailable() {
        return httpClient.isHealthy();
    }

    @Override
    public String getVendorName() {
        return "Fiserv";
    }

    @Override
    public CoreReliabilityCapabilities reliabilityCapabilities() {
        return CoreReliabilityCapabilities.vendorShapedFixture();
    }

    // ── Circuit breaker fallbacks ─────────────────────────────────────────────

    private CoreBankingResponse fallbackPostCreditTransfer(Pacs008Message message, Throwable t) {
        // The external outcome is unknown. A caller must not infer failure or
        // authorize a new financial mutation from this fallback.
        return new CoreBankingResponse(
                CoreBankingResponse.Status.TIMEOUT, null, "CB_OPEN", null);
    }

    private BigDecimal fallbackGetAvailableBalance(String accountId, Throwable t) {
        throw new IllegalStateException(
                "Fiserv-shaped fixture balance unavailable; no authoritative balance observation", t);
    }

    private boolean fallbackIsCoreSystemAvailable(Throwable t) {
        return false;
    }
}
