package io.openfednow.acl.adapters.fis;

import io.openfednow.acl.core.CoreBankingResponse;
import io.openfednow.iso20022.Pacs008Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * HTTP transport layer for the FIS Code Connect / IBS REST API.
 *
 * <p>Constructs all required FIS request headers, delegates body serialization
 * to the DTO records, and maps HTTP responses to {@link CoreBankingResponse}
 * using {@link FisReasonCodeMapper}.
 *
 * <h2>Key differences from the Fiserv adapter</h2>
 * <ul>
 *   <li><b>Auth credentials:</b> consumer key and secret are sent as a Base64-encoded
 *       Basic authorization header on the token request (Fiserv sends them as form
 *       body parameters). See {@link FisTokenManager}.</li>
 *   <li><b>Amount format:</b> plain decimal string ({@code "1000.50"}) rather than
 *       Fiserv's fixed-point cent integer ({@code "100050"}). No encoder class is
 *       needed — {@link BigDecimal#toPlainString()} is used directly.</li>
 *   <li><b>Headers:</b> {@code feId} (FI institution ID) + {@code X-Correlation-Id}
 *       (per-request UUID) rather than Fiserv's {@code Api-Key} + {@code Timestamp}.</li>
 *   <li><b>Approval status:</b> FIS uses {@code "ACCEPTED"}; Fiserv uses
 *       {@code "APPROVED"}. Both map to {@link CoreBankingResponse.Status#ACCEPTED}.</li>
 * </ul>
 *
 * <h2>Required headers (per FIS Code Connect API spec)</h2>
 * <ul>
 *   <li>{@code Authorization: Bearer {token}} — OAuth 2.0 bearer token</li>
 *   <li>{@code feId: {institutionId}} — FIS financial-institution identifier</li>
 *   <li>{@code X-Correlation-Id: {uuid}} — unique per request, for distributed tracing</li>
 * </ul>
 *
 * <h2>API paths (FIS IBS)</h2>
 * <ul>
 *   <li>{@code POST /ibs/v1/transactions} — post a credit transfer</li>
 *   <li>{@code GET  /ibs/v1/accounts/{accountId}/balance} — balance inquiry</li>
 *   <li>{@code GET  /ibs/v1/health} — system availability check</li>
 * </ul>
 *
 * @see <a href="https://codeconnect.fisglobal.com">FIS Code Connect Developer Portal</a>
 */
public class FisHttpClient {

    private static final Logger log = LoggerFactory.getLogger(FisHttpClient.class);

    public static final String PATH_TRANSACTIONS = "/ibs/v1/transactions";
    public static final String PATH_BALANCE      = "/ibs/v1/accounts/{accountId}/balance";
    public static final String PATH_HEALTH       = "/ibs/v1/health";

    private final RestClient restClient;
    private final FisTokenManager tokenManager;
    private final String institutionId;

    public FisHttpClient(RestClient restClient, FisTokenManager tokenManager, String institutionId) {
        this.restClient = restClient;
        this.tokenManager = tokenManager;
        this.institutionId = institutionId;
    }

    /**
     * Posts a credit transfer to the FIS IBS core banking system.
     *
     * <p>Translates the pacs.008 message to a {@link FisTransactionRequest},
     * submits it to the FIS transactions endpoint, and maps the response to a
     * {@link CoreBankingResponse}. Any {@link RestClientException} (network
     * errors, connection resets, malformed responses) is caught and returned as
     * {@link CoreBankingResponse.Status#TIMEOUT} so the circuit breaker can
     * accumulate failures without an unchecked exception propagating.
     *
     * @param message validated pacs.008 credit transfer
     * @return normalized {@link CoreBankingResponse}
     */
    public CoreBankingResponse postTransaction(Pacs008Message message) {
        FisTransactionRequest request = buildTransactionRequest(message);
        // Pre-fetch the token before building the RestClient chain to prevent
        // nested HTTP calls that can trigger stale keep-alive connection reuse.
        String token = tokenManager.getAccessToken();
        try {
            FisTransactionResponse response = restClient.post()
                    .uri(PATH_TRANSACTIONS)
                    .headers(h -> applyHeaders(h, token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(FisTransactionResponse.class);

            return mapTransactionResponse(response);

        } catch (RestClientException e) {
            log.warn("FIS API network error posting transaction {}: {}",
                    message.getTransactionId(), e.getMessage());
            return new CoreBankingResponse(
                    CoreBankingResponse.Status.TIMEOUT, null, "NETWORK_ERROR", null);
        }
    }

    /**
     * Queries the available balance for an account from FIS IBS.
     *
     * <p>FIS returns the balance as a plain decimal string (e.g. {@code "5000.00"}),
     * which is parsed directly into a {@link BigDecimal} — no fixed-point decoding
     * step is required (unlike the Fiserv adapter).
     *
     * @param accountId institution's internal account identifier
     * @return available balance in USD
     * @throws IllegalStateException if the response is empty or missing the balance field
     */
    public BigDecimal getBalance(String accountId) {
        String token = tokenManager.getAccessToken();
        FisBalanceResponse response = restClient.get()
                .uri(PATH_BALANCE, accountId)
                .headers(h -> applyHeaders(h, token))
                .retrieve()
                .body(FisBalanceResponse.class);

        if (response == null || response.availBal() == null) {
            throw new IllegalStateException(
                    "FIS balance response empty for account: " + accountId);
        }

        return new BigDecimal(response.availBal());
    }

    /**
     * Checks whether the FIS IBS system is reachable and healthy.
     *
     * @return {@code true} if the health endpoint returns 2xx; {@code false} on any error
     */
    public boolean isHealthy() {
        try {
            String token = tokenManager.getAccessToken();
            restClient.get()
                    .uri(PATH_HEALTH)
                    .headers(h -> applyHeaders(h, token))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("FIS health check failed: {}", e.getMessage());
            return false;
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private FisTransactionRequest buildTransactionRequest(Pacs008Message msg) {
        // FIS uses plain decimal strings for amounts — no fixed-point encoding.
        String amount = msg.getInterbankSettlementAmount()
                .setScale(2, java.math.RoundingMode.HALF_UP)
                .toPlainString();

        return new FisTransactionRequest(
                msg.getTransactionId(),
                msg.getEndToEndId(),
                amount,
                msg.getInterbankSettlementCurrency(),
                msg.getDebtorAgentRoutingNumber(),
                msg.getCreditorAgentRoutingNumber(),
                msg.getDebtorAccountNumber(),
                msg.getCreditorAccountNumber(),
                msg.getDebtorName(),
                msg.getCreditorName(),
                msg.getRemittanceInformation(),
                "INSTANT_CREDIT"
        );
    }

    private void applyHeaders(org.springframework.http.HttpHeaders h, String token) {
        h.setBearerAuth(token);
        h.set("feId", institutionId);
        h.set("X-Correlation-Id", UUID.randomUUID().toString());
    }

    private CoreBankingResponse mapTransactionResponse(FisTransactionResponse response) {
        if (response == null) {
            log.warn("FIS returned empty transaction response body");
            return new CoreBankingResponse(
                    CoreBankingResponse.Status.TIMEOUT, null, "EMPTY_RESPONSE", null);
        }

        if (FisReasonCodeMapper.isAccepted(response.status())) {
            log.debug("FIS transaction accepted, ref={}", response.fiTransId());
            return new CoreBankingResponse(
                    CoreBankingResponse.Status.ACCEPTED,
                    null,
                    response.status(),
                    response.fiTransId());
        }

        if ("PENDING".equals(response.status())) {
            return new CoreBankingResponse(
                    CoreBankingResponse.Status.PENDING,
                    null,
                    response.status(),
                    response.fiTransId());
        }

        String iso20022Code = FisReasonCodeMapper.toIso20022(response.rsnCode());
        log.debug("FIS transaction rejected: fisCode={} → iso20022={}",
                response.rsnCode(), iso20022Code);
        return new CoreBankingResponse(
                CoreBankingResponse.Status.REJECTED,
                iso20022Code,
                response.rsnCode(),
                response.fiTransId());
    }
}
