package io.openfednow.acl.adapters;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.openfednow.acl.adapters.jackhenry.JackHenrySoapClient;
import io.openfednow.acl.adapters.jackhenry.JackHenryTokenManager;
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
 * Layer 2 — Jack Henry Core Banking Adapter
 *
 * <p>Connects OpenFedNow to Jack Henry core banking platforms (SilverLake, Symitar,
 * CIF 20/20) via the jXchange SOAP Service Gateway. Jack Henry platforms serve
 * approximately 21% of U.S. banks and 12% of U.S. credit unions.
 *
 * <h2>Key differences from the Fiserv and FIS adapters</h2>
 * <ul>
 *   <li><b>Protocol:</b> SOAP/XML over HTTPS ({@code text/xml}). Fiserv and FIS
 *       use REST/JSON.</li>
 *   <li><b>Authentication:</b> OAuth 2.0 client credentials with HTTP Basic auth
 *       header. The jXchange platform migrated from legacy WS-Security
 *       username/password authentication to OAuth 2.0 in May 2025.</li>
 *   <li><b>Request headers:</b> Every request (except {@code Ping}) includes a
 *       {@code jXchangeHdr_CType} SOAP header with audit identity, consumer
 *       identity, and correlation IDs.</li>
 *   <li><b>Amount format:</b> Plain decimal strings ({@code "1000.50"}), same as FIS.</li>
 *   <li><b>Error codes:</b> Numeric (e.g. {@code "3050"}) returned in SOAP fault
 *       {@code ErrRec/ErrCode} elements, mapped by
 *       {@link io.openfednow.acl.adapters.jackhenry.JackHenryReasonCodeMapper}.</li>
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
 *   adapter: jackhenry          # activates this bean
 *   adapter-jackhenry:
 *     base-url: ${JH_BASE_URL}
 *     client-id: ${JH_CLIENT_ID}
 *     client-secret: ${JH_CLIENT_SECRET}
 *     institution-routing-id: ${JH_ROUTING_ID}
 *     settlement-gl-account: ${JH_SETTLEMENT_GL}   # FedNow settlement GL account
 * </pre>
 *
 * @see JackHenrySoapClient
 * @see JackHenryTokenManager
 * @see <a href="https://jackhenry.dev/open-enterprise-api-docs/enterprise-soap-api/">
 *      Jack Henry jXchange SOAP API</a>
 */
@Component("jackHenryAdapter")
@ConditionalOnProperty(name = "openfednow.adapter", havingValue = "jackhenry")
public class JackHenryAdapter implements CoreBankingAdapter {

    @Value("${openfednow.adapter-jackhenry.base-url:}")
    private String baseUrl;

    @Value("${openfednow.adapter-jackhenry.client-id:}")
    private String clientId;

    @Value("${openfednow.adapter-jackhenry.client-secret:}")
    private String clientSecret;

    @Value("${openfednow.adapter-jackhenry.institution-routing-id:}")
    private String institutionRoutingId;

    /**
     * Institution's FedNow settlement GL account number. jXchange enforces
     * double-entry bookkeeping — every {@code TrnAdd} DDA credit must have an
     * offsetting GL debit or the bank ledger goes out of balance on posting.
     * Institution-specific; no default. See ADR / issue #70.
     */
    @Value("${openfednow.adapter-jackhenry.settlement-gl-account:}")
    private String settlementGlAccount;

    @Value("${openfednow.adapter-jackhenry.token-path:/oauth2/token}")
    private String tokenPath;

    @Value("${openfednow.adapter-jackhenry.token-expiry-buffer-seconds:60}")
    private long tokenExpiryBufferSeconds;

    private JackHenrySoapClient soapClient;

    /** Spring constructor — fields injected via {@code @Value}; client built in {@link #init()}. */
    public JackHenryAdapter() {}

    /** Test constructor — injects a pre-configured {@link JackHenrySoapClient} directly. */
    JackHenryAdapter(JackHenrySoapClient soapClient) {
        this.soapClient = soapClient;
    }

    @PostConstruct
    void init() {
        if (soapClient != null) return; // already set via test constructor

        // Use SimpleClientHttpRequestFactory (HTTP/1.1) for SOAP compatibility.
        // The jXchange Service Gateway uses HTTP/1.1 SOAP; HTTP/2 upgrade would
        // cause RST_STREAM errors since SOAP bindings are not h2-aware.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

        RestClient tokenClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        RestClient apiClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();

        JackHenryTokenManager tokenManager = new JackHenryTokenManager(
                tokenClient, tokenPath, clientId, clientSecret, tokenExpiryBufferSeconds);
        soapClient = new JackHenrySoapClient(apiClient, tokenManager,
                institutionRoutingId, settlementGlAccount);
    }

    // ── CoreBankingAdapter ────────────────────────────────────────────────────

    @CircuitBreaker(name = "corebanking", fallbackMethod = "fallbackPostCreditTransfer")
    @Override
    public CoreBankingResponse postCreditTransfer(Pacs008Message message) {
        return soapClient.postTransaction(message);
    }

    @CircuitBreaker(name = "corebanking", fallbackMethod = "fallbackGetAvailableBalance")
    @Override
    public BigDecimal getAvailableBalance(String accountId) {
        return soapClient.getBalance(accountId);
    }

    @CircuitBreaker(name = "corebanking", fallbackMethod = "fallbackIsCoreSystemAvailable")
    @Override
    public boolean isCoreSystemAvailable() {
        return soapClient.isHealthy();
    }

    @Override
    public String getVendorName() {
        return "Jack Henry";
    }

    // ── Circuit breaker fallbacks ─────────────────────────────────────────────

    private CoreBankingResponse fallbackPostCreditTransfer(Pacs008Message message, Throwable t) {
        return new CoreBankingResponse(
                CoreBankingResponse.Status.TIMEOUT, null, "CB_OPEN", null);
    }

    private BigDecimal fallbackGetAvailableBalance(String accountId, Throwable t) {
        throw new IllegalStateException(
                "Jack Henry core unreachable (circuit open) — use Shadow Ledger for balance inquiry", t);
    }

    private boolean fallbackIsCoreSystemAvailable(Throwable t) {
        return false;
    }
}
