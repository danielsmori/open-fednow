package io.openfednow.acl.adapters;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.openfednow.acl.adapters.fiserv.FiservHttpClient;
import io.openfednow.acl.adapters.fiserv.FiservTokenManager;
import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.acl.core.CoreBankingResponse;
import io.openfednow.iso20022.Pacs008Message;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

/**
 * Layer 2 — Fiserv Core Banking Adapter
 *
 * <p>Connects OpenFedNow to Fiserv core banking platforms via the
 * Fiserv Communicator Open REST API (docs.fiserv.dev). Supports the
 * DNA, Precision, Premier, and Cleartouch platforms.
 *
 * <p>Fiserv serves approximately 42% of U.S. banks and 31% of U.S. credit
 * unions, making this the highest-priority adapter in the OpenFedNow framework
 * (Phase 1, Months 1–9).
 *
 * <h2>Authentication</h2>
 * <p>Uses OAuth 2.0 client credentials grant ({@code POST /fts-apim/oauth2/v2}).
 * Tokens are cached and refreshed proactively by {@link FiservTokenManager}.
 *
 * <h2>Amount encoding</h2>
 * <p>All monetary values are transmitted as Fiserv fixed-point cent strings
 * (e.g. {@code "100050"} = $1,000.50) and decoded on receipt.
 *
 * <h2>Rejection codes</h2>
 * <p>Fiserv-specific codes (e.g. {@code INSF}, {@code INVLD_ACCT}) are mapped
 * to ISO 20022 reason codes before being returned in pacs.002 status reports.
 *
 * <h2>Circuit breaker</h2>
 * <p>All three methods are protected by the {@code corebanking} circuit breaker
 * configured in {@code application.yml}. Fallback behaviour:
 * <ul>
 *   <li>{@link #postCreditTransfer} returns {@code TIMEOUT} — SyncAsyncBridge
 *       issues provisional FedNow acceptance backed by Shadow Ledger</li>
 *   <li>{@link #isCoreSystemAvailable} returns {@code false} — AvailabilityBridge
 *       activates Shadow Ledger bridge mode</li>
 *   <li>{@link #getAvailableBalance} throws — callers consult the Shadow Ledger</li>
 * </ul>
 *
 * <h2>Configuration (application.yml)</h2>
 * <pre>
 * openfednow:
 *   adapter: fiserv          # activates this bean
 *   adapter-fiserv:
 *     base-url: ${FISERV_BASE_URL}
 *     client-id: ${FISERV_CLIENT_ID}
 *     client-secret: ${FISERV_CLIENT_SECRET}
 *     api-key: ${FISERV_API_KEY}
 * </pre>
 *
 * @see FiservHttpClient
 * @see FiservTokenManager
 * @see <a href="https://docs.fiserv.dev">Fiserv Developer Documentation</a>
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
        RestClient restClient = RestClient.builder().baseUrl(baseUrl).build();
        FiservTokenManager tokenManager = new FiservTokenManager(
                restClient, tokenPath, clientId, clientSecret, tokenExpiryBufferSeconds);
        httpClient = new FiservHttpClient(restClient, tokenManager, apiKey);
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

    // ── Circuit breaker fallbacks ─────────────────────────────────────────────

    private CoreBankingResponse fallbackPostCreditTransfer(Pacs008Message message, Throwable t) {
        // Return TIMEOUT so SyncAsyncBridge can issue a provisional FedNow acceptance
        // backed by Shadow Ledger validation and queue the transaction for replay.
        return new CoreBankingResponse(
                CoreBankingResponse.Status.TIMEOUT, null, "CB_OPEN", null);
    }

    private BigDecimal fallbackGetAvailableBalance(String accountId, Throwable t) {
        // The Shadow Ledger is the authoritative balance source when the core is
        // unreachable. Signal the caller to consult it instead.
        throw new IllegalStateException(
                "Fiserv core unreachable (circuit open) — use Shadow Ledger for balance inquiry", t);
    }

    private boolean fallbackIsCoreSystemAvailable(Throwable t) {
        return false;
    }
}
