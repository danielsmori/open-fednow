package io.openfednow.simulator;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.openfednow.gateway.HttpFedNowClient;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.openfednow.iso20022.Pacs002Message.TransactionStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FedNow Simulator — integration tests for the outbound FedNow HTTP path.
 *
 * <p>A WireMock server stands in for the Federal Reserve FedNow endpoint.
 * Each test configures stubs that replay a specific FedNow response scenario
 * and verifies that {@link HttpFedNowClient} parses and maps the result
 * correctly.
 *
 * <p>No Spring context is loaded. {@link HttpFedNowClient} is instantiated
 * directly and pointed at the WireMock base URL, keeping the suite fast and
 * independent of Redis, RabbitMQ, and PostgreSQL.
 *
 * <h2>Scenarios covered</h2>
 * <ul>
 *   <li>ACSC — FedNow accepts and settles the payment</li>
 *   <li>RJCT AM04 — FedNow rejects for insufficient funds</li>
 *   <li>RJCT AC01 — FedNow rejects for invalid account</li>
 *   <li>ACSP — FedNow provisionally accepts (settlement in process)</li>
 *   <li>HTTP 4xx error — mapped to synthetic RJCT with reason code NARR</li>
 *   <li>Network timeout — mapped to synthetic RJCT with reason code NARR</li>
 *   <li>Request payload — pacs.008 fields serialized correctly to /transfers</li>
 * </ul>
 */
@WireMockTest
class FedNowSimulatorTest {

    private HttpFedNowClient client;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        // 5-second timeout is sufficient for local WireMock calls.
        // The timeout scenario creates its own short-timeout client instance.
        client = new HttpFedNowClient(wmInfo.getHttpBaseUrl(), 5);
    }

    // --- ACSC: payment accepted and settled ---

    @Test
    void submitCreditTransfer_returnsAcsc_whenFedNowAccepts() {
        stubFor(post(urlEqualTo("/transfers"))
                .willReturn(okJson("""
                        {
                          "originalEndToEndId":    "E2E-ACSC-001",
                          "originalTransactionId": "TXN-ACSC-001",
                          "transactionStatus":     "ACSC"
                        }
                        """)));

        Pacs002Message response = client.submitCreditTransfer(
                buildMessage("E2E-ACSC-001", "TXN-ACSC-001", "1000.00"));

        assertThat(response.getTransactionStatus()).isEqualTo(ACSC);
        assertThat(response.getOriginalEndToEndId()).isEqualTo("E2E-ACSC-001");
        assertThat(response.getOriginalTransactionId()).isEqualTo("TXN-ACSC-001");
        assertThat(response.getRejectReasonCode()).isNull();
    }

    // --- RJCT: payment rejections ---

    @Test
    void submitCreditTransfer_returnsRjctAm04_whenFedNowRejectsInsufficientFunds() {
        stubFor(post(urlEqualTo("/transfers"))
                .willReturn(okJson("""
                        {
                          "originalEndToEndId":    "E2E-AM04-001",
                          "originalTransactionId": "TXN-AM04-001",
                          "transactionStatus":     "RJCT",
                          "rejectReasonCode":      "AM04",
                          "rejectReasonDescription": "Insufficient funds"
                        }
                        """)));

        Pacs002Message response = client.submitCreditTransfer(
                buildMessage("E2E-AM04-001", "TXN-AM04-001", "999999.00"));

        assertThat(response.getTransactionStatus()).isEqualTo(RJCT);
        assertThat(response.getRejectReasonCode()).isEqualTo("AM04");
        assertThat(response.getRejectReasonDescription()).containsIgnoringCase("funds");
    }

    @Test
    void submitCreditTransfer_returnsRjctAc01_whenFedNowRejectsInvalidAccount() {
        stubFor(post(urlEqualTo("/transfers"))
                .willReturn(okJson("""
                        {
                          "originalEndToEndId":    "E2E-AC01-001",
                          "originalTransactionId": "TXN-AC01-001",
                          "transactionStatus":     "RJCT",
                          "rejectReasonCode":      "AC01",
                          "rejectReasonDescription": "Invalid account number"
                        }
                        """)));

        Pacs002Message response = client.submitCreditTransfer(
                buildMessage("E2E-AC01-001", "TXN-AC01-001", "500.00"));

        assertThat(response.getTransactionStatus()).isEqualTo(RJCT);
        assertThat(response.getRejectReasonCode()).isEqualTo("AC01");
        assertThat(response.getRejectReasonDescription()).containsIgnoringCase("account");
    }

    // --- ACSP: provisional acceptance (settlement in process) ---

    @Test
    void submitCreditTransfer_returnsAcsp_whenFedNowSignalsSettlementInProcess() {
        stubFor(post(urlEqualTo("/transfers"))
                .willReturn(okJson("""
                        {
                          "originalEndToEndId":    "E2E-ACSP-001",
                          "originalTransactionId": "TXN-ACSP-001",
                          "transactionStatus":     "ACSP"
                        }
                        """)));

        Pacs002Message response = client.submitCreditTransfer(
                buildMessage("E2E-ACSP-001", "TXN-ACSP-001", "250.00"));

        assertThat(response.getTransactionStatus()).isEqualTo(ACSP);
        assertThat(response.getRejectReasonCode()).isNull();
    }

    // --- HTTP error → synthetic RJCT NARR ---

    @Test
    void submitCreditTransfer_returnsRjctNarr_onHttp422Response() {
        stubFor(post(urlEqualTo("/transfers"))
                .willReturn(aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"Unprocessable message format\"}")));

        Pacs002Message response = client.submitCreditTransfer(
                buildMessage("E2E-ERR-001", "TXN-ERR-001", "100.00"));

        assertThat(response.getTransactionStatus()).isEqualTo(RJCT);
        assertThat(response.getRejectReasonCode()).isEqualTo("NARR");
        assertThat(response.getRejectReasonDescription()).contains("422");
        // Original IDs are preserved so the saga can correlate the failure
        assertThat(response.getOriginalEndToEndId()).isEqualTo("E2E-ERR-001");
        assertThat(response.getOriginalTransactionId()).isEqualTo("TXN-ERR-001");
    }

    // --- Network timeout → synthetic RJCT NARR ---

    @Test
    void submitCreditTransfer_returnsRjctNarr_onNetworkTimeout(WireMockRuntimeInfo wmInfo) {
        stubFor(post(urlEqualTo("/transfers"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(2000)   // 2-second artificial delay
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"transactionStatus\": \"ACSC\"}")));

        // Client with 1-second read timeout — must trigger before WireMock responds
        HttpFedNowClient shortTimeoutClient = new HttpFedNowClient(wmInfo.getHttpBaseUrl(), 1);
        Pacs002Message response = shortTimeoutClient.submitCreditTransfer(
                buildMessage("E2E-TOUT-001", "TXN-TOUT-001", "300.00"));

        assertThat(response.getTransactionStatus()).isEqualTo(RJCT);
        assertThat(response.getRejectReasonCode()).isEqualTo("NARR");
        assertThat(response.getRejectReasonDescription()).containsIgnoringCase("timeout");
    }

    // --- Request payload verification ---

    @Test
    void pacs008IsPostedToTransfersEndpointWithCorrectPayload() {
        stubFor(post(urlEqualTo("/transfers"))
                .willReturn(okJson("""
                        {
                          "originalEndToEndId":    "E2E-VRF-001",
                          "originalTransactionId": "TXN-VRF-001",
                          "transactionStatus":     "ACSC"
                        }
                        """)));

        client.submitCreditTransfer(Pacs008Message.builder()
                .endToEndId("E2E-VRF-001")
                .transactionId("TXN-VRF-001")
                .interbankSettlementAmount(new BigDecimal("750.00"))
                .interbankSettlementCurrency("USD")
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("021000089")
                .debtorAccountNumber("123456789")
                .creditorAccountNumber("987654321")
                .debtorName("Alice Smith")
                .creditorName("Bob Jones")
                .numberOfTransactions(1)
                .build());

        verify(postRequestedFor(urlEqualTo("/transfers"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.endToEndId",
                        equalTo("E2E-VRF-001")))
                .withRequestBody(matchingJsonPath("$.debtorAgentRoutingNumber",
                        equalTo("021000021")))
                .withRequestBody(matchingJsonPath("$.creditorAgentRoutingNumber",
                        equalTo("021000089")))
                .withRequestBody(matchingJsonPath("$.interbankSettlementCurrency",
                        equalTo("USD"))));
    }

    // --- Helper ---

    private Pacs008Message buildMessage(String endToEndId, String transactionId, String amount) {
        return Pacs008Message.builder()
                .messageId("MSG-" + endToEndId)
                .endToEndId(endToEndId)
                .transactionId(transactionId)
                .interbankSettlementAmount(new BigDecimal(amount))
                .interbankSettlementCurrency("USD")
                .creationDateTime(OffsetDateTime.now())
                .numberOfTransactions(1)
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("021000089")
                .debtorAccountNumber("123456789")
                .creditorAccountNumber("987654321")
                .debtorName("Alice Smith")
                .creditorName("Bob Jones")
                .build();
    }
}
