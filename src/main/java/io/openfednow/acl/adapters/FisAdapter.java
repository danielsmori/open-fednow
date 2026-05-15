package io.openfednow.acl.adapters;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.openfednow.acl.adapters.fis.FisHttpClient;
import io.openfednow.acl.adapters.fis.FisTokenManager;
import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.acl.core.CoreBankingResponse;
import io.openfednow.iso20022.Pacs008Message;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

/**
 * Layer 2 — FIS Core Banking Adapter
 *
 * <p>Connects OpenFedNow to FIS core banking platforms (Horizon, IBS) via the
 * FIS Code Connect REST API (codeconnect.fisglobal.com). FIS platforms serve
 * approximately 9% of U.S. banks and are the Phase 2 priority after Fiserv.
 *
 * <h2>Key differences from the Fiserv adapter</h2>
 * <ul>
 *   <li><b>Authentication:</b> OAuth 2.0 client credentials using Basic auth
 *       ({@code Authorization: Basic base64(consumerKey:consumerSecret)}) on the
 *       token request. Fiserv sends credentials as form body parameters.</li>
 *   <li><b>Amount format:</b> plain decimal strings ({@code "1000.50"}). Fiserv
 *       uses fixed-point cent integers ({@code "100050"}). No encoder class is
 *       needed — {@link BigDecimal#toPlainString()} is used directly.</li>
 *   <li><b>Headers:</b> {@code feId} (FIS institution ID) + {@code X-Correlation-Id}.
 *       Fiserv uses {@code Api-Key} + {@code Timestamp}.</li>
 *   <li><b>Approval status:</b> FIS uses {@code "ACCEPTED"}; Fiserv uses
 *       {@code "APPROVED"}. Both map to {@link CoreBankingResponse.Status#ACCEPTED}.</li>
 *   <li><b>Error codes:</b> FIS uses a different proprietary code set
 *       ({@code INSUFF_FUNDS}, {@code FRZN_ACCT}, etc.) mapped by
 *       {@link io.openfednow.acl.adapters.fis.FisReasonCodeMapper}.</li>
 * </ul>
 *
 * <h2>Circuit breaker</h2>
 * <p>All three methods are protected by the {@code corebanking} circuit breaker
 * configured in {@code application.yml}. Fallback behaviour is identical to
 * {@link FiservAdapter}: TIMEOUT for transactions, exception for balance,
 * false for availability.
 *
 * <h2>Configuration (application.yml)</h2>
 * <pre>
 * openfednow:
 *   adapter: fis              # activates this bean
 *   adapter-fis:
 *     base-url: ${FIS_BASE_URL}
 *     consumer-key: ${FIS_CONSUMER_KEY}
 *     consumer-secret: ${FIS_CONSUMER_SECRET}
 *     institution-id: ${FIS_INSTITUTION_ID}
 * </pre>
 *
 * @see FisHttpClient
 * @see FisTokenManager
 * @see <a href="https://codeconnect.fisglobal.com">FIS Code Connect Developer Portal</a>
 */
@Component("fisAdapter")
@ConditionalOnProperty(name = "openfednow.adapter", havingValue = "fis")
public class FisAdapter implements CoreBankingAdapter {

    @Value("${openfednow.adapter-fis.base-url:}")
    private String baseUrl;

    @Value("${openfednow.adapter-fis.consumer-key:}")
    private String consumerKey;

    @Value("${openfednow.adapter-fis.consumer-secret:}")
    private String consumerSecret;

    @Value("${openfednow.adapter-fis.institution-id:}")
    private String institutionId;

    @Value("${openfednow.adapter-fis.token-path:/oauth2/v1/token}")
    private String tokenPath;

    @Value("${openfednow.adapter-fis.token-expiry-buffer-seconds:60}")
    private long tokenExpiryBufferSeconds;

    private FisHttpClient httpClient;

    /** Spring constructor — fields injected via {@code @Value}; client built in {@link #init()}. */
    public FisAdapter() {}

    /** Test constructor — injects a pre-configured {@link FisHttpClient} directly. */
    FisAdapter(FisHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @PostConstruct
    void init() {
        if (httpClient != null) return; // already set via test constructor

        // Use SimpleClientHttpRequestFactory (HTTP/1.1) for stability with the
        // Code Connect gateway. HTTP/2 can be enabled by replacing this factory
        // with JdkClientHttpRequestFactory configured for HTTP_2 once FIS
        // confirms h2 support on their production endpoints.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

        RestClient tokenClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        RestClient apiClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();

        FisTokenManager tokenManager = new FisTokenManager(
                tokenClient, tokenPath, consumerKey, consumerSecret, tokenExpiryBufferSeconds);
        httpClient = new FisHttpClient(apiClient, tokenManager, institutionId);
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
        return "FIS";
    }

    // ── Circuit breaker fallbacks ─────────────────────────────────────────────

    private CoreBankingResponse fallbackPostCreditTransfer(Pacs008Message message, Throwable t) {
        return new CoreBankingResponse(
                CoreBankingResponse.Status.TIMEOUT, null, "CB_OPEN", null);
    }

    private BigDecimal fallbackGetAvailableBalance(String accountId, Throwable t) {
        throw new IllegalStateException(
                "FIS core unreachable (circuit open) — use Shadow Ledger for balance inquiry", t);
    }

    private boolean fallbackIsCoreSystemAvailable(Throwable t) {
        return false;
    }
}
