package io.openfednow.gateway;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link HttpRtpClient} using WireMock.
 *
 * <p>WireMock stands in for the TCH RTP® endpoint. Validates that
 * {@link HttpRtpClient} correctly:
 * <ul>
 *   <li>Sends pacs.008 as {@code application/xml}</li>
 *   <li>Parses pacs.002 XML responses via {@link RtpXmlSerializer}</li>
 *   <li>Maps ACSC, RJCT, and network-error scenarios to the correct
 *       {@link Pacs002Message} outcomes</li>
 * </ul>
 *
 * <p>Key differences from {@link HttpFedNowClient}: the wire format is XML, not JSON.
 * The structural pattern (WireMock + timeout + synthetic RJCT on error) is identical.
 */
class HttpRtpClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    private HttpRtpClient client;
    private final RtpXmlSerializer serializer = new RtpXmlSerializer();

    @BeforeEach
    void setUp() {
        wm.resetAll();
        client = new HttpRtpClient(wm.baseUrl(), 5, serializer);
    }

    @Test
    void submitCreditTransfer_acsc_returnsAccepted() {
        wm.stubFor(post(urlEqualTo(HttpRtpClient.TRANSFERS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml; charset=utf-8")
                        .withBody(pacs002AcscXml("STATUS-001", "E2E-001", "TXN-001"))));

        Pacs002Message response = client.submitCreditTransfer(buildMessage("500.00"));

        assertThat(response.getTransactionStatus())
                .isEqualTo(Pacs002Message.TransactionStatus.ACSC);
        assertThat(response.getOriginalEndToEndId()).isEqualTo("E2E-001");
        assertThat(response.getRejectReasonCode()).isNull();
    }

    @Test
    void submitCreditTransfer_rjct_returnsRejectionWithReasonCode() {
        wm.stubFor(post(urlEqualTo(HttpRtpClient.TRANSFERS_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml; charset=utf-8")
                        .withBody(pacs002RjctXml("STATUS-002", "E2E-002", "TXN-002", "AM04", "Insufficient funds"))));

        Pacs002Message response = client.submitCreditTransfer(buildMessage("50000.00"));

        assertThat(response.getTransactionStatus())
                .isEqualTo(Pacs002Message.TransactionStatus.RJCT);
        assertThat(response.getRejectReasonCode()).isEqualTo("AM04");
        assertThat(response.getRejectReasonDescription()).isEqualTo("Insufficient funds");
    }

    @Test
    void submitCreditTransfer_httpError_returnsSyntheticRjct() {
        wm.stubFor(post(urlEqualTo(HttpRtpClient.TRANSFERS_PATH))
                .willReturn(aResponse().withStatus(503)));

        Pacs002Message response = client.submitCreditTransfer(buildMessage("100.00"));

        assertThat(response.getTransactionStatus())
                .isEqualTo(Pacs002Message.TransactionStatus.RJCT);
        assertThat(response.getRejectReasonCode()).isEqualTo("NARR");
        assertThat(response.getRejectReasonDescription()).contains("503");
    }

    @Test
    void submitCreditTransfer_networkError_returnsSyntheticRjct() {
        wm.stubFor(post(urlEqualTo(HttpRtpClient.TRANSFERS_PATH))
                .willReturn(aResponse()
                        .withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        Pacs002Message response = client.submitCreditTransfer(buildMessage("200.00"));

        assertThat(response.getTransactionStatus())
                .isEqualTo(Pacs002Message.TransactionStatus.RJCT);
        assertThat(response.getRejectReasonCode()).isEqualTo("NARR");
    }

    /**
     * Verifies that the request is sent as {@code application/xml} with a pacs.008
     * XML body — the canonical RTP format, distinct from FedNow's JSON encoding.
     */
    @Test
    void submitCreditTransfer_sendsXmlContentType() {
        wm.stubFor(post(urlEqualTo(HttpRtpClient.TRANSFERS_PATH))
                .withHeader("Content-Type", containing("application/xml"))
                .withRequestBody(containing("pacs.008.001.08"))
                .withRequestBody(containing("<EndToEndId>E2E-XML-001</EndToEndId>"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(pacs002AcscXml("STATUS-XML", "E2E-XML-001", "TXN-XML-001"))));

        Pacs008Message msg = Pacs008Message.builder()
                .messageId("MSG-XML-001")
                .endToEndId("E2E-XML-001")
                .transactionId("TXN-XML-001")
                .creationDateTime(OffsetDateTime.now())
                .numberOfTransactions(1)
                .interbankSettlementAmount(new BigDecimal("750.00"))
                .interbankSettlementCurrency("USD")
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("026009593")
                .debtorAccountNumber("DEB-001")
                .creditorAccountNumber("CRD-001")
                .debtorName("Alice Smith")
                .creditorName("Bob Jones")
                .build();

        Pacs002Message response = client.submitCreditTransfer(msg);

        assertThat(response.getTransactionStatus())
                .isEqualTo(Pacs002Message.TransactionStatus.ACSC);
        wm.verify(postRequestedFor(urlEqualTo(HttpRtpClient.TRANSFERS_PATH))
                .withHeader("Content-Type", containing("application/xml")));
    }

    // ── SOAP response helpers ─────────────────────────────────────────────────

    private static String pacs002AcscXml(String msgId, String e2eId, String txId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
                  <FIToFIPmtStsRpt>
                    <GrpHdr>
                      <MsgId>%s</MsgId>
                      <CreDtTm>2024-01-15T10:30:00Z</CreDtTm>
                    </GrpHdr>
                    <TxInfAndSts>
                      <OrgnlEndToEndId>%s</OrgnlEndToEndId>
                      <OrgnlTxId>%s</OrgnlTxId>
                      <TxSts>ACSC</TxSts>
                    </TxInfAndSts>
                  </FIToFIPmtStsRpt>
                </Document>""".formatted(msgId, e2eId, txId);
    }

    private static String pacs002RjctXml(String msgId, String e2eId, String txId,
                                          String reasonCode, String reasonDesc) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
                  <FIToFIPmtStsRpt>
                    <GrpHdr>
                      <MsgId>%s</MsgId>
                      <CreDtTm>2024-01-15T10:30:00Z</CreDtTm>
                    </GrpHdr>
                    <TxInfAndSts>
                      <OrgnlEndToEndId>%s</OrgnlEndToEndId>
                      <OrgnlTxId>%s</OrgnlTxId>
                      <TxSts>RJCT</TxSts>
                      <StsRsnInf>
                        <Rsn><Cd>%s</Cd></Rsn>
                        <AddtlInf>%s</AddtlInf>
                      </StsRsnInf>
                    </TxInfAndSts>
                  </FIToFIPmtStsRpt>
                </Document>""".formatted(msgId, e2eId, txId, reasonCode, reasonDesc);
    }

    private Pacs008Message buildMessage(String amount) {
        return Pacs008Message.builder()
                .messageId("MSG-RTP-001")
                .endToEndId("E2E-001")
                .transactionId("TXN-001")
                .creationDateTime(OffsetDateTime.now())
                .numberOfTransactions(1)
                .interbankSettlementAmount(new BigDecimal(amount))
                .interbankSettlementCurrency("USD")
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("026009593")
                .debtorAccountNumber("DEB-001")
                .creditorAccountNumber("CRD-001")
                .debtorName("Alice Smith")
                .creditorName("Bob Jones")
                .build();
    }
}
