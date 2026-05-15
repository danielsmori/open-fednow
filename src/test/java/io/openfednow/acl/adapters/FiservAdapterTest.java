package io.openfednow.acl.adapters;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.openfednow.acl.adapters.fiserv.FiservHttpClient;
import io.openfednow.acl.adapters.fiserv.FiservTokenManager;
import io.openfednow.acl.core.CoreBankingResponse;
import io.openfednow.iso20022.Pacs008Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link FiservAdapter} using WireMock.
 *
 * <p>WireMock runs in-process (no Docker required). Each test stubs the
 * Fiserv OAuth token endpoint and one API endpoint, then verifies that
 * {@link FiservAdapter} produces the correct {@link CoreBankingResponse}.
 *
 * <p>These tests cover:
 * <ul>
 *   <li>OAuth token fetch and inclusion in API request headers</li>
 *   <li>Transaction posting: approved, rejected (with ISO 20022 code mapping), pending</li>
 *   <li>Balance inquiry: successful decode from Fiserv fixed-point format</li>
 *   <li>Health check: healthy and unhealthy states</li>
 *   <li>Network timeout: adapter returns TIMEOUT (not an exception)</li>
 * </ul>
 */
class FiservAdapterTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    private FiservAdapter adapter;

    @BeforeEach
    void setUp() {
        // WireMockExtension with a static field does not auto-reset between tests;
        // reset explicitly so stubs from previous tests don't bleed through.
        wm.resetAll();

        // Force HTTP/1.1 to prevent Java's built-in HttpClient from negotiating H2C
        // (HTTP/2 cleartext), which causes RST_STREAM errors against WireMock.
        SimpleClientHttpRequestFactory http11 = new SimpleClientHttpRequestFactory();

        // Separate RestClient instances so the token-fetch connection pool does not
        // interfere with the API connection pool (avoids stale-connection EOF errors).
        RestClient tokenRestClient = RestClient.builder()
                .baseUrl(wm.baseUrl())
                .requestFactory(http11)
                .build();
        RestClient apiRestClient = RestClient.builder()
                .baseUrl(wm.baseUrl())
                .requestFactory(http11)
                .build();
        FiservTokenManager tokenManager = new FiservTokenManager(
                tokenRestClient, "/fts-apim/oauth2/v2", "test-client", "test-secret", 60);
        FiservHttpClient httpClient = new FiservHttpClient(apiRestClient, tokenManager, "test-api-key");
        adapter = new FiservAdapter(httpClient);

        // Stub the OAuth token endpoint for every test
        wm.stubFor(post(urlEqualTo("/fts-apim/oauth2/v2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "access_token": "test-bearer-token",
                                  "token_type": "Bearer",
                                  "expires_in": 3600
                                }
                                """)));
    }

    // ── Transaction posting ──────────────────────────────────────────────────

    @Test
    void diagnostic_restClientDeserializesTransactionResponse() {
        wm.stubFor(get(urlEqualTo("/diag"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"APPROVED\",\"transactionRef\":\"R\",\"reasonCode\":null,\"message\":\"OK\"}")));

        RestClient rc = RestClient.builder().baseUrl(wm.baseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory()).build();
        var r = rc.get().uri("/diag").retrieve()
                .body(io.openfednow.acl.adapters.fiserv.FiservTransactionResponse.class);
        assertThat(r).isNotNull();
        assertThat(r.status()).isEqualTo("APPROVED");
    }

    @Test
    void postCreditTransfer_approved_returnsAccepted() {
        wm.stubFor(post(urlEqualTo(FiservHttpClient.PATH_TRANSACTIONS))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "status": "APPROVED",
                                  "transactionRef": "FISERV-REF-001",
                                  "reasonCode": null,
                                  "message": "Transaction approved"
                                }
                                """)));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("500.00"));

        assertThat(response.isAccepted()).isTrue();
        assertThat(response.getTransactionReference()).isEqualTo("FISERV-REF-001");
        assertThat(response.getIso20022ReasonCode()).isNull();
    }

    @Test
    void postCreditTransfer_insufficientFunds_returnsRejectedWithAM04() {
        wm.stubFor(post(urlEqualTo(FiservHttpClient.PATH_TRANSACTIONS))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "status": "REJECTED",
                                  "transactionRef": null,
                                  "reasonCode": "INSF",
                                  "message": "Insufficient funds in debtor account"
                                }
                                """)));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("50000.00"));

        assertThat(response.isRejected()).isTrue();
        assertThat(response.getIso20022ReasonCode()).isEqualTo("AM04");
        assertThat(response.getVendorStatusCode()).isEqualTo("INSF");
    }

    @Test
    void postCreditTransfer_closedAccount_returnsRejectedWithAC04() {
        wm.stubFor(post(urlEqualTo(FiservHttpClient.PATH_TRANSACTIONS))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "status": "REJECTED",
                                  "transactionRef": null,
                                  "reasonCode": "CLSD_ACCT",
                                  "message": "Creditor account is closed"
                                }
                                """)));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("100.00"));

        assertThat(response.isRejected()).isTrue();
        assertThat(response.getIso20022ReasonCode()).isEqualTo("AC04");
    }

    @Test
    void postCreditTransfer_pending_returnsPending() {
        wm.stubFor(post(urlEqualTo(FiservHttpClient.PATH_TRANSACTIONS))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "status": "PENDING",
                                  "transactionRef": "FISERV-PEND-002",
                                  "reasonCode": null,
                                  "message": "Transaction queued for asynchronous processing"
                                }
                                """)));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("1000.00"));

        assertThat(response.isPending()).isTrue();
        assertThat(response.getTransactionReference()).isEqualTo("FISERV-PEND-002");
    }

    @Test
    void postCreditTransfer_networkTimeout_returnsTimeout() {
        wm.stubFor(post(urlEqualTo(FiservHttpClient.PATH_TRANSACTIONS))
                .willReturn(aResponse()
                        .withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("200.00"));

        assertThat(response.getStatus()).isEqualTo(CoreBankingResponse.Status.TIMEOUT);
        assertThat(response.getVendorStatusCode()).isEqualTo("NETWORK_ERROR");
    }

    @Test
    void postCreditTransfer_requestContainsAmountInFixedPointFormat() {
        wm.stubFor(post(urlEqualTo(FiservHttpClient.PATH_TRANSACTIONS))
                .withRequestBody(WireMock.matchingJsonPath("$.amount", equalTo("100050")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"status":"APPROVED","transactionRef":"REF-AMT","reasonCode":null,"message":"OK"}
                                """)));

        CoreBankingResponse response = adapter.postCreditTransfer(
                buildMessage("1000.50"));

        assertThat(response.isAccepted()).isTrue();
    }

    @Test
    void postCreditTransfer_requestIncludesBearerToken() {
        wm.stubFor(post(urlEqualTo(FiservHttpClient.PATH_TRANSACTIONS))
                .withHeader("Authorization", equalTo("Bearer test-bearer-token"))
                .withHeader("Api-Key", equalTo("test-api-key"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"status":"APPROVED","transactionRef":"REF-HDR","reasonCode":null,"message":"OK"}
                                """)));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("100.00"));

        assertThat(response.isAccepted()).isTrue();
        wm.verify(postRequestedFor(urlEqualTo("/fts-apim/oauth2/v2")));
    }

    // ── Balance inquiry ──────────────────────────────────────────────────────

    @Test
    void getAvailableBalance_decodesFixedPointAmount() {
        wm.stubFor(get(urlEqualTo("/banking/v1/accounts/ACC-12345/balances"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "accountId": "ACC-12345",
                                  "availableBalance": "500000",
                                  "currency": "USD",
                                  "status": "ACTIVE"
                                }
                                """)));

        BigDecimal balance = adapter.getAvailableBalance("ACC-12345");

        assertThat(balance).isEqualByComparingTo(new BigDecimal("5000.00"));
    }

    // ── Health check ─────────────────────────────────────────────────────────

    @Test
    void isCoreSystemAvailable_healthyResponse_returnsTrue() {
        wm.stubFor(get(urlEqualTo(FiservHttpClient.PATH_HEALTH))
                .willReturn(aResponse().withStatus(200)));

        assertThat(adapter.isCoreSystemAvailable()).isTrue();
    }

    @Test
    void isCoreSystemAvailable_serverError_returnsFalse() {
        wm.stubFor(get(urlEqualTo(FiservHttpClient.PATH_HEALTH))
                .willReturn(aResponse().withStatus(503)));

        assertThat(adapter.isCoreSystemAvailable()).isFalse();
    }

    @Test
    void getVendorName_returnsFiserv() {
        assertThat(adapter.getVendorName()).isEqualTo("Fiserv");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Pacs008Message buildMessage(String amount) {
        return Pacs008Message.builder()
                .messageId("MSG-FISERV-001")
                .endToEndId("E2E-FISERV-001")
                .transactionId("TXN-FISERV-001")
                .creationDateTime(OffsetDateTime.now())
                .numberOfTransactions(1)
                .interbankSettlementAmount(new BigDecimal(amount))
                .interbankSettlementCurrency("USD")
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("026009593")
                .debtorAccountNumber("DEB-ACC-001")
                .creditorAccountNumber("CRD-ACC-001")
                .debtorName("Alice Smith")
                .creditorName("Bob Jones")
                .remittanceInformation("Invoice #001")
                .build();
    }
}
