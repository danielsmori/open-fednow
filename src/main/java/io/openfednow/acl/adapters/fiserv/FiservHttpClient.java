package io.openfednow.acl.adapters.fiserv;

import io.openfednow.acl.core.CoreBankingResponse;
import io.openfednow.iso20022.Pacs008Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Transport for legacy local HTTP fixtures. The paths and headers below are
 * fixture assumptions, not verified Banking Hub operations. Do not point this
 * client at a provider host without a product-specific contract and review.
 */
public class FiservHttpClient {

    private static final Logger log = LoggerFactory.getLogger(FiservHttpClient.class);

    public static final String PATH_TRANSACTIONS = "/banking/v1/transactions";
    public static final String PATH_BALANCES     = "/banking/v1/accounts/{accountId}/balances";
    public static final String PATH_HEALTH       = "/banking/v1/health";

    private final RestClient restClient;
    private final FiservTokenManager tokenManager;
    private final String apiKey;

    public FiservHttpClient(RestClient restClient,
                             FiservTokenManager tokenManager,
                             String apiKey) {
        this.restClient = restClient;
        this.tokenManager = tokenManager;
        this.apiKey = apiKey;
    }

    /**
     * Submits the locally modeled credit transfer request.
     *
     * <p>Translates the pacs.008 message to a {@link FiservTransactionRequest},
     * submits it to the fixture transactions endpoint, and maps the response to
     * a {@link CoreBankingResponse}. Any {@link RestClientException} (network
     * errors, connection resets, malformed responses) is caught and returned
     * as {@link CoreBankingResponse.Status#TIMEOUT} so the circuit breaker can
     * accumulate failures without an unchecked exception propagating.
     *
     * @param message validated pacs.008 credit transfer
     * @return normalized {@link CoreBankingResponse}
     */
    public CoreBankingResponse postTransaction(Pacs008Message message) {
        FiservTransactionRequest request = buildTransactionRequest(message);
        // Fetch the token before entering the RestClient builder chain so that
        // the token HTTP call and the transaction HTTP call are never nested
        // (nested calls over the same RestClient trigger keep-alive reuse issues
        // with Java's built-in HttpURLConnection pool).
        String token = tokenManager.getAccessToken();
        try {
            FiservTransactionResponse response = restClient.post()
                    .uri(PATH_TRANSACTIONS)
                    .headers(h -> applyRequestHeaders(h, token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(FiservTransactionResponse.class);

            return mapTransactionResponse(response);

        } catch (RestClientException e) {
            log.warn("Fiserv API network error posting transaction {}: {}",
                    message.getTransactionId(), e.getMessage());
            return new CoreBankingResponse(
                    CoreBankingResponse.Status.TIMEOUT, null, "NETWORK_ERROR", null);
        }
    }

    /**
     * Queries the locally modeled available balance endpoint.
     *
     * @param accountId fixture account identifier
     * @return fixture balance decoded as USD
     * @throws IllegalStateException if the response is empty or missing the balance field
     */
    public BigDecimal getBalance(String accountId) {
        String token = tokenManager.getAccessToken();
        FiservBalanceResponse response = restClient.get()
                .uri(PATH_BALANCES, accountId)
                .headers(h -> applyRequestHeaders(h, token))
                .retrieve()
                .body(FiservBalanceResponse.class);

        if (response == null || response.availableBalance() == null) {
            throw new IllegalStateException(
                    "Fiserv balance response empty for account: " + accountId);
        }

        return FiservAmountEncoder.decode(response.availableBalance());
    }

    /**
     * Checks whether the locally modeled endpoint is reachable.
     *
     * @return {@code true} if the health endpoint returns 2xx; {@code false} on any error
     */
    public boolean isHealthy() {
        try {
            String token = tokenManager.getAccessToken();
            restClient.get()
                    .uri(PATH_HEALTH)
                    .headers(h -> applyRequestHeaders(h, token))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Fiserv health check failed: {}", e.getMessage());
            return false;
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private FiservTransactionRequest buildTransactionRequest(Pacs008Message msg) {
        return new FiservTransactionRequest(
                msg.getTransactionId(),
                msg.getEndToEndId(),
                FiservAmountEncoder.encode(msg.getInterbankSettlementAmount()),
                msg.getInterbankSettlementCurrency(),
                msg.getDebtorAgentRoutingNumber(),
                msg.getCreditorAgentRoutingNumber(),
                msg.getDebtorAccountNumber(),
                msg.getCreditorAccountNumber(),
                msg.getDebtorName(),
                msg.getCreditorName(),
                msg.getRemittanceInformation(),
                "INSTANT"
        );
    }

    private void applyRequestHeaders(org.springframework.http.HttpHeaders h, String token) {
        h.setBearerAuth(token);
        h.set("Api-Key", apiKey);
        h.set("Client-Request-Id", UUID.randomUUID().toString());
        h.set("Timestamp", String.valueOf(Instant.now().toEpochMilli()));
    }

    private CoreBankingResponse mapTransactionResponse(FiservTransactionResponse response) {
        if (response == null) {
            log.warn("Fiserv returned empty transaction response body");
            return new CoreBankingResponse(
                    CoreBankingResponse.Status.TIMEOUT, null, "EMPTY_RESPONSE", null);
        }

        if (FiservReasonCodeMapper.isApproved(response.status())) {
            log.debug("Fiserv transaction accepted, ref={}", response.transactionRef());
            return new CoreBankingResponse(
                    CoreBankingResponse.Status.ACCEPTED,
                    null,
                    response.status(),
                    response.transactionRef());
        }

        if ("PENDING".equals(response.status())) {
            return new CoreBankingResponse(
                    CoreBankingResponse.Status.PENDING,
                    null,
                    response.status(),
                    response.transactionRef());
        }

        String iso20022Code = FiservReasonCodeMapper.toIso20022(response.reasonCode());
        log.debug("Fiserv transaction rejected: fiservCode={} → iso20022={}",
                response.reasonCode(), iso20022Code);
        return new CoreBankingResponse(
                CoreBankingResponse.Status.REJECTED,
                iso20022Code,
                response.reasonCode(),
                response.transactionRef());
    }
}
