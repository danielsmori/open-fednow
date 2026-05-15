package io.openfednow.acl.adapters;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.openfednow.acl.adapters.fis.FisHttpClient;
import io.openfednow.acl.adapters.fis.FisTokenManager;
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
 * Integration tests for {@link FisAdapter} using WireMock.
 *
 * <p>WireMock runs in-process (no Docker required). Each test stubs the
 * FIS Code Connect OAuth token endpoint and one API endpoint, then verifies
 * that {@link FisAdapter} produces the correct {@link CoreBankingResponse}.
 *
 * <p>Key differences exercised vs. the Fiserv adapter tests:
 * <ul>
 *   <li>Token request uses HTTP Basic auth header (not form-body credentials)</li>
 *   <li>Transaction request body uses {@code feId} institution-ID header
 *       (not {@code Api-Key})</li>
 *   <li>Amounts are transmitted as plain decimal strings ({@code "1000.50"}),
 *       not as fixed-point cent integers ({@code "100050"})</li>
 *   <li>Approval status in the response is {@code "ACCEPTED"} (not {@code "APPROVED"})</li>
 *   <li>Rejection codes follow the FIS IBS naming convention
 *       (e.g. {@code "INSUFF_FUNDS"} vs. Fiserv's {@code "INSF"})</li>
 * </ul>
 */
class FisAdapterTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    private FisAdapter adapter;

    @BeforeEach
    void setUp() {
        // WireMockExtension with a static field does not auto-reset between tests;
        // reset explicitly so stubs from previous tests don't bleed through.
        wm.resetAll();

        // Force HTTP/1.1 to prevent Java's built-in HttpClient from negotiating H2C
        // (HTTP/2 cleartext), which causes RST_STREAM errors against WireMock.
        SimpleClientHttpRequestFactory http11 = new SimpleClientHttpRequestFactory();

        // Separate RestClient instances to avoid stale keep-alive connection
        // reuse between the token-fetch call and subsequent API calls.
        RestClient tokenRestClient = RestClient.builder()
                .baseUrl(wm.baseUrl())
                .requestFactory(http11)
                .build();
        RestClient apiRestClient = RestClient.builder()
                .baseUrl(wm.baseUrl())
                .requestFactory(http11)
                .build();

        FisTokenManager tokenManager = new FisTokenManager(
                tokenRestClient, "/oauth2/v1/token",
                "test-consumer-key", "test-consumer-secret", 60);
        FisHttpClient httpClient = new FisHttpClient(
                apiRestClient, tokenManager, "TEST-FI-001");
        adapter = new FisAdapter(httpClient);

        // Stub the OAuth token endpoint for every test.
        // FIS uses HTTP Basic auth on the token request; WireMock verifies the
        // Authorization header in postCreditTransfer_requestIncludesBasicAuthAndFeId.
        wm.stubFor(post(urlEqualTo("/oauth2/v1/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "access_token": "test-fis-bearer-token",
                                  "token_type": "Bearer",
                                  "expires_in": 3600
                                }
                                """)));
    }

    // ── Transaction posting ──────────────────────────────────────────────────

    @Test
    void postCreditTransfer_accepted_returnsAccepted() {
        wm.stubFor(post(urlEqualTo(FisHttpClient.PATH_TRANSACTIONS))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "status": "ACCEPTED",
                                  "fiTransId": "FIS-TXN-001",
                                  "rsnCode": null,
                                  "rsnDesc": "Transaction accepted"
                                }
                                """)));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("500.00"));

        assertThat(response.isAccepted()).isTrue();
        assertThat(response.getTransactionReference()).isEqualTo("FIS-TXN-001");
        assertThat(response.getIso20022ReasonCode()).isNull();
    }

    @Test
    void postCreditTransfer_insufficientFunds_returnsRejectedWithAM04() {
        wm.stubFor(post(urlEqualTo(FisHttpClient.PATH_TRANSACTIONS))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "status": "REJECTED",
                                  "fiTransId": null,
                                  "rsnCode": "INSUFF_FUNDS",
                                  "rsnDesc": "Insufficient funds in debtor account"
                                }
                                """)));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("50000.00"));

        assertThat(response.isRejected()).isTrue();
        assertThat(response.getIso20022ReasonCode()).isEqualTo("AM04");
        assertThat(response.getVendorStatusCode()).isEqualTo("INSUFF_FUNDS");
    }

    @Test
    void postCreditTransfer_frozenAccount_returnsRejectedWithAC06() {
        wm.stubFor(post(urlEqualTo(FisHttpClient.PATH_TRANSACTIONS))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "status": "REJECTED",
                                  "fiTransId": null,
                                  "rsnCode": "FRZN_ACCT",
                                  "rsnDesc": "Creditor account is frozen"
                                }
                                """)));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("100.00"));

        assertThat(response.isRejected()).isTrue();
        assertThat(response.getIso20022ReasonCode()).isEqualTo("AC06");
    }

    @Test
    void postCreditTransfer_pending_returnsPending() {
        wm.stubFor(post(urlEqualTo(FisHttpClient.PATH_TRANSACTIONS))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "status": "PENDING",
                                  "fiTransId": "FIS-PEND-002",
                                  "rsnCode": null,
                                  "rsnDesc": "Transaction queued for processing"
                                }
                                """)));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("1000.00"));

        assertThat(response.isPending()).isTrue();
        assertThat(response.getTransactionReference()).isEqualTo("FIS-PEND-002");
    }

    @Test
    void postCreditTransfer_networkError_returnsTimeout() {
        wm.stubFor(post(urlEqualTo(FisHttpClient.PATH_TRANSACTIONS))
                .willReturn(aResponse()
                        .withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("200.00"));

        assertThat(response.getStatus()).isEqualTo(CoreBankingResponse.Status.TIMEOUT);
        assertThat(response.getVendorStatusCode()).isEqualTo("NETWORK_ERROR");
    }

    /**
     * Verifies that FIS amounts are sent as plain decimal strings, NOT as
     * fixed-point cent integers. This is the key difference from the Fiserv
     * adapter, which encodes $1,000.50 as "100050" (cents). FIS expects "1000.50".
     */
    @Test
    void postCreditTransfer_requestContainsDecimalAmount() {
        wm.stubFor(post(urlEqualTo(FisHttpClient.PATH_TRANSACTIONS))
                .withRequestBody(WireMock.matchingJsonPath("$.amount", equalTo("1000.50")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"status":"ACCEPTED","fiTransId":"REF-AMT","rsnCode":null,"rsnDesc":"OK"}
                                """)));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("1000.50"));

        assertThat(response.isAccepted()).isTrue();
    }

    /**
     * Verifies that the FIS token is obtained via the Basic auth flow and that
     * the {@code feId} institution-ID header is included in API calls.
     *
     * <p>FIS authenticates the token request with:
     * {@code Authorization: Basic base64(consumerKey:consumerSecret)}
     * rather than posting credentials in the form body (Fiserv's approach).
     */
    @Test
    void postCreditTransfer_requestIncludesBasicAuthTokenAndFeId() {
        // Token endpoint: verify Basic auth header is used
        wm.stubFor(post(urlEqualTo("/oauth2/v1/token"))
                .withHeader("Authorization", matching("Basic .+"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"access_token":"test-fis-bearer-token","token_type":"Bearer","expires_in":3600}
                                """)));

        // Transaction endpoint: verify Bearer token and feId header
        wm.stubFor(post(urlEqualTo(FisHttpClient.PATH_TRANSACTIONS))
                .withHeader("Authorization", equalTo("Bearer test-fis-bearer-token"))
                .withHeader("feId", equalTo("TEST-FI-001"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"status":"ACCEPTED","fiTransId":"REF-HDR","rsnCode":null,"rsnDesc":"OK"}
                                """)));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("100.00"));

        assertThat(response.isAccepted()).isTrue();
        wm.verify(postRequestedFor(urlEqualTo("/oauth2/v1/token"))
                .withHeader("Authorization", matching("Basic .+")));
    }

    // ── Balance inquiry ──────────────────────────────────────────────────────

    /**
     * Verifies that FIS balance responses (plain decimal strings) are parsed
     * correctly — no fixed-point decoding is needed, unlike the Fiserv adapter.
     */
    @Test
    void getAvailableBalance_parsesDecimalString() {
        wm.stubFor(get(urlEqualTo("/ibs/v1/accounts/ACC-98765/balance"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "acctId": "ACC-98765",
                                  "availBal": "5000.00",
                                  "currency": "USD",
                                  "acctStatus": "ACTIVE"
                                }
                                """)));

        BigDecimal balance = adapter.getAvailableBalance("ACC-98765");

        assertThat(balance).isEqualByComparingTo(new BigDecimal("5000.00"));
    }

    // ── Health check ─────────────────────────────────────────────────────────

    @Test
    void isCoreSystemAvailable_healthyResponse_returnsTrue() {
        wm.stubFor(get(urlEqualTo(FisHttpClient.PATH_HEALTH))
                .willReturn(aResponse().withStatus(200)));

        assertThat(adapter.isCoreSystemAvailable()).isTrue();
    }

    @Test
    void isCoreSystemAvailable_serverError_returnsFalse() {
        wm.stubFor(get(urlEqualTo(FisHttpClient.PATH_HEALTH))
                .willReturn(aResponse().withStatus(503)));

        assertThat(adapter.isCoreSystemAvailable()).isFalse();
    }

    @Test
    void getVendorName_returnsFis() {
        assertThat(adapter.getVendorName()).isEqualTo("FIS");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Pacs008Message buildMessage(String amount) {
        return Pacs008Message.builder()
                .messageId("MSG-FIS-001")
                .endToEndId("E2E-FIS-001")
                .transactionId("TXN-FIS-001")
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
                .remittanceInformation("Invoice #002")
                .build();
    }
}
