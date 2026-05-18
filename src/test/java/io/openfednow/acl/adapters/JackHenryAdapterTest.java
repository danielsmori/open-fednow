package io.openfednow.acl.adapters;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.openfednow.acl.adapters.jackhenry.JackHenrySoapClient;
import io.openfednow.acl.adapters.jackhenry.JackHenryTokenManager;
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
 * Integration tests for {@link JackHenryAdapter} using WireMock.
 *
 * <p>WireMock runs in-process (no Docker required). Each test stubs the
 * Jack Henry OAuth token endpoint and the jXchange Service Gateway SOAP endpoint,
 * then verifies that {@link JackHenryAdapter} produces the correct
 * {@link CoreBankingResponse}.
 *
 * <p>Key differences exercised vs. the Fiserv and FIS adapter tests:
 * <ul>
 *   <li>Request and response bodies are SOAP/XML, not JSON</li>
 *   <li>Business rejections arrive as SOAP faults with numeric {@code ErrCode} values</li>
 *   <li>Amounts are decimal strings ({@code "1000.50"}), same as FIS</li>
 *   <li>Operation routing uses the {@code SOAPAction} header
 *       ({@code TrnAdd}, {@code AcctBalInq}, {@code Ping})</li>
 *   <li>Token request uses HTTP Basic auth header (same pattern as FIS)</li>
 * </ul>
 */
class JackHenryAdapterTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    private JackHenryAdapter adapter;

    @BeforeEach
    void setUp() {
        // WireMockExtension with a static field does not auto-reset between tests;
        // reset explicitly so stubs from previous tests don't bleed through.
        wm.resetAll();

        // Force HTTP/1.1 to prevent Java's built-in HttpClient from negotiating H2C
        // (HTTP/2 cleartext), which causes RST_STREAM errors against WireMock.
        SimpleClientHttpRequestFactory http11 = new SimpleClientHttpRequestFactory();

        // Separate RestClient instances to avoid stale keep-alive connection
        // reuse between the token-fetch call and subsequent SOAP calls.
        RestClient tokenRestClient = RestClient.builder()
                .baseUrl(wm.baseUrl())
                .requestFactory(http11)
                .build();
        RestClient apiRestClient = RestClient.builder()
                .baseUrl(wm.baseUrl())
                .requestFactory(http11)
                .build();

        JackHenryTokenManager tokenManager = new JackHenryTokenManager(
                tokenRestClient, "/oauth2/token",
                "test-client-id", "test-client-secret", 60);
        JackHenrySoapClient soapClient = new JackHenrySoapClient(
                apiRestClient, tokenManager, "021000021");
        adapter = new JackHenryAdapter(soapClient);

        // Stub the OAuth token endpoint for every test.
        wm.stubFor(post(urlEqualTo("/oauth2/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "access_token": "test-jh-bearer-token",
                                  "token_type": "Bearer",
                                  "expires_in": 3600
                                }
                                """)));
    }

    // ── Transaction posting ──────────────────────────────────────────────────

    @Test
    void postCreditTransfer_posted_returnsAccepted() {
        wm.stubFor(post(urlEqualTo(JackHenrySoapClient.PATH_SERVICE_GATEWAY))
                .withHeader("SOAPAction", equalTo("TrnAdd"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(trnAddPostedResponse("JH-TRN-001"))));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("500.00"));

        assertThat(response.isAccepted()).isTrue();
        assertThat(response.getTransactionReference()).isEqualTo("JH-TRN-001");
        assertThat(response.getIso20022ReasonCode()).isNull();
    }

    @Test
    void postCreditTransfer_insufficientFunds_returnsRejectedWithAM04() {
        wm.stubFor(post(urlEqualTo(JackHenrySoapClient.PATH_SERVICE_GATEWAY))
                .withHeader("SOAPAction", equalTo("TrnAdd"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(soapFaultResponse("3050", "Insufficient funds in account"))));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("50000.00"));

        assertThat(response.isRejected()).isTrue();
        assertThat(response.getIso20022ReasonCode()).isEqualTo("AM04");
        assertThat(response.getVendorStatusCode()).isEqualTo("3050");
    }

    @Test
    void postCreditTransfer_blockedAccount_returnsRejectedWithAC06() {
        wm.stubFor(post(urlEqualTo(JackHenrySoapClient.PATH_SERVICE_GATEWAY))
                .withHeader("SOAPAction", equalTo("TrnAdd"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(soapFaultResponse("3052", "Account is frozen"))));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("100.00"));

        assertThat(response.isRejected()).isTrue();
        assertThat(response.getIso20022ReasonCode()).isEqualTo("AC06");
        assertThat(response.getVendorStatusCode()).isEqualTo("3052");
    }

    @Test
    void postCreditTransfer_closedAccount_returnsRejectedWithAC04() {
        wm.stubFor(post(urlEqualTo(JackHenrySoapClient.PATH_SERVICE_GATEWAY))
                .withHeader("SOAPAction", equalTo("TrnAdd"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(soapFaultResponse("3051", "Account is closed"))));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("100.00"));

        assertThat(response.isRejected()).isTrue();
        assertThat(response.getIso20022ReasonCode()).isEqualTo("AC04");
    }

    @Test
    void postCreditTransfer_pending_returnsPending() {
        wm.stubFor(post(urlEqualTo(JackHenrySoapClient.PATH_SERVICE_GATEWAY))
                .withHeader("SOAPAction", equalTo("TrnAdd"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(trnAddPendingResponse("JH-PEND-002"))));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("1000.00"));

        assertThat(response.isPending()).isTrue();
        assertThat(response.getTransactionReference()).isEqualTo("JH-PEND-002");
    }

    @Test
    void postCreditTransfer_networkError_returnsTimeout() {
        wm.stubFor(post(urlEqualTo(JackHenrySoapClient.PATH_SERVICE_GATEWAY))
                .willReturn(aResponse()
                        .withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("200.00"));

        assertThat(response.getStatus()).isEqualTo(CoreBankingResponse.Status.TIMEOUT);
        assertThat(response.getVendorStatusCode()).isEqualTo("NETWORK_ERROR");
    }

    /**
     * Verifies that jXchange amounts are sent as plain decimal strings, NOT as
     * fixed-point cent integers. jXchange expects "1000.50" (like FIS), not
     * "100050" (Fiserv's format).
     */
    @Test
    void postCreditTransfer_requestContainsDecimalAmountAndSoapBody() {
        wm.stubFor(post(urlEqualTo(JackHenrySoapClient.PATH_SERVICE_GATEWAY))
                .withHeader("SOAPAction", equalTo("TrnAdd"))
                .withRequestBody(containing("<jx:TrnAmt>1000.50</jx:TrnAmt>"))
                .withRequestBody(containing("<jx:TrnAddRqst>"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(trnAddPostedResponse("REF-AMT"))));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("1000.50"));

        assertThat(response.isAccepted()).isTrue();
    }

    /**
     * Verifies that the jXchange SOAP header includes the required fields:
     * Bearer token authorization and jXchangeHdr with ValidConsmName/ValidConsmProd.
     */
    @Test
    void postCreditTransfer_requestIncludesBearerTokenAndJxchangeHeader() {
        // Token endpoint: verify Basic auth is used
        wm.stubFor(post(urlEqualTo("/oauth2/token"))
                .withHeader("Authorization", matching("Basic .+"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"access_token":"test-jh-bearer-token","token_type":"Bearer","expires_in":3600}
                                """)));

        // SOAP endpoint: verify Bearer token and jXchange header fields
        wm.stubFor(post(urlEqualTo(JackHenrySoapClient.PATH_SERVICE_GATEWAY))
                .withHeader("Authorization", equalTo("Bearer test-jh-bearer-token"))
                .withHeader("SOAPAction", equalTo("TrnAdd"))
                .withRequestBody(containing("<jx:ValidConsmName>OPENFEDNOW</jx:ValidConsmName>"))
                .withRequestBody(containing("<jx:AuditUsrId>OPENFEDNOW</jx:AuditUsrId>"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(trnAddPostedResponse("REF-HDR"))));

        CoreBankingResponse response = adapter.postCreditTransfer(buildMessage("100.00"));

        assertThat(response.isAccepted()).isTrue();
        wm.verify(postRequestedFor(urlEqualTo("/oauth2/token"))
                .withHeader("Authorization", matching("Basic .+")));
    }

    // ── Balance inquiry ──────────────────────────────────────────────────────

    /**
     * Verifies that jXchange balance responses (plain decimal strings in AvailBal)
     * are parsed correctly — no fixed-point decoding required.
     */
    @Test
    void getAvailableBalance_parsesDecimalBalance() {
        wm.stubFor(post(urlEqualTo(JackHenrySoapClient.PATH_SERVICE_GATEWAY))
                .withHeader("SOAPAction", equalTo("AcctBalInq"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(acctBalInqResponse("ACC-98765", "5000.00"))));

        BigDecimal balance = adapter.getAvailableBalance("ACC-98765");

        assertThat(balance).isEqualByComparingTo(new BigDecimal("5000.00"));
    }

    // ── Health check ─────────────────────────────────────────────────────────

    @Test
    void isCoreSystemAvailable_pingResponse_returnsTrue() {
        wm.stubFor(post(urlEqualTo(JackHenrySoapClient.PATH_SERVICE_GATEWAY))
                .withHeader("SOAPAction", equalTo("Ping"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/xml; charset=utf-8")
                        .withBody(pingResponse())));

        assertThat(adapter.isCoreSystemAvailable()).isTrue();
    }

    @Test
    void isCoreSystemAvailable_serverError_returnsFalse() {
        wm.stubFor(post(urlEqualTo(JackHenrySoapClient.PATH_SERVICE_GATEWAY))
                .withHeader("SOAPAction", equalTo("Ping"))
                .willReturn(aResponse().withStatus(503)));

        assertThat(adapter.isCoreSystemAvailable()).isFalse();
    }

    @Test
    void getVendorName_returnsJackHenry() {
        assertThat(adapter.getVendorName()).isEqualTo("Jack Henry");
    }

    // ── SOAP response helpers ─────────────────────────────────────────────────

    private static String trnAddPostedResponse(String trnId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                            xmlns:jx="http://jackhenry.com/jxchange/2008">
                  <s:Body>
                    <jx:TrnAddRslt>
                      <jx:TrnId>%s</jx:TrnId>
                      <jx:Status>POSTED</jx:Status>
                      <jx:MsgRsltRec>
                        <jx:MsgRsltSts>Success</jx:MsgRsltSts>
                        <jx:Severity>Info</jx:Severity>
                      </jx:MsgRsltRec>
                    </jx:TrnAddRslt>
                  </s:Body>
                </s:Envelope>""".formatted(trnId);
    }

    private static String trnAddPendingResponse(String trnId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                            xmlns:jx="http://jackhenry.com/jxchange/2008">
                  <s:Body>
                    <jx:TrnAddRslt>
                      <jx:TrnId>%s</jx:TrnId>
                      <jx:Status>PENDING</jx:Status>
                      <jx:MsgRsltRec>
                        <jx:MsgRsltSts>Pending</jx:MsgRsltSts>
                        <jx:Severity>Info</jx:Severity>
                      </jx:MsgRsltRec>
                    </jx:TrnAddRslt>
                  </s:Body>
                </s:Envelope>""".formatted(trnId);
    }

    private static String soapFaultResponse(String errCode, String errDesc) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                            xmlns:jx="http://jackhenry.com/jxchange/2008">
                  <s:Body>
                    <s:Fault>
                      <s:Code><s:Value>s:Receiver</s:Value></s:Code>
                      <s:Reason>
                        <s:Text xml:lang="en">Transaction rejected</s:Text>
                      </s:Reason>
                      <s:Detail>
                        <jx:ErrRec>
                          <jx:ErrCode>%s</jx:ErrCode>
                          <jx:ErrCat>Error</jx:ErrCat>
                          <jx:ErrDesc>%s</jx:ErrDesc>
                        </jx:ErrRec>
                      </s:Detail>
                    </s:Fault>
                  </s:Body>
                </s:Envelope>""".formatted(errCode, errDesc);
    }

    private static String acctBalInqResponse(String accountId, String availBal) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                            xmlns:jx="http://jackhenry.com/jxchange/2008">
                  <s:Body>
                    <jx:AcctBalInqRslt>
                      <jx:AcctId>%s</jx:AcctId>
                      <jx:AcctType>DDA</jx:AcctType>
                      <jx:AcctBal>
                        <jx:AvailBal>%s</jx:AvailBal>
                        <jx:CurBal>%s</jx:CurBal>
                      </jx:AcctBal>
                      <jx:AcctStatus>Active</jx:AcctStatus>
                    </jx:AcctBalInqRslt>
                  </s:Body>
                </s:Envelope>""".formatted(accountId, availBal, availBal);
    }

    private static String pingResponse() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                            xmlns:jx="http://jackhenry.com/jxchange/2008">
                  <s:Body>
                    <jx:PingRslt>
                      <jx:Status>OK</jx:Status>
                    </jx:PingRslt>
                  </s:Body>
                </s:Envelope>""";
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Pacs008Message buildMessage(String amount) {
        return Pacs008Message.builder()
                .messageId("MSG-JH-001")
                .endToEndId("E2E-JH-001")
                .transactionId("TXN-JH-001")
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
                .remittanceInformation("Invoice #003")
                .build();
    }
}
