package io.openfednow.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static io.openfednow.iso20022.Pacs002Message.TransactionStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RtpGateway}.
 *
 * <p>No Spring context is loaded. {@link MessageRouter}, {@link CertificateManager},
 * and {@link RtpClient} are mocked directly. {@link RtpXmlParser} and
 * {@link RtpXmlSerializer} are used as real instances (pure XML parsing/generation,
 * no external dependencies).
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>/rtp/health — reflects sandbox vs. live mode</li>
 *   <li>/rtp/receive (JSON) — routes through {@link MessageRouter#routeInbound}</li>
 *   <li>/rtp/receive (JSON) — propagates RJCT response unmodified</li>
 *   <li>/rtp/receive (XML) — invokes TCH certificate validation and returns XML response</li>
 *   <li>/rtp/send — delegates to {@link RtpClient#submitCreditTransfer}</li>
 *   <li>/rtp/send — invokes TCH certificate validation</li>
 * </ul>
 */
class RtpGatewayTest {

    private MessageRouter messageRouter;
    private CertificateManager certificateManager;
    private RtpClient rtpClient;
    private RtpGateway gateway;

    private final RtpXmlParser xmlParser = new RtpXmlParser();
    private final RtpXmlSerializer xmlSerializer = new RtpXmlSerializer();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        messageRouter = mock(MessageRouter.class);
        certificateManager = mock(CertificateManager.class);
        rtpClient = mock(RtpClient.class);
        gateway = new RtpGateway(messageRouter, certificateManager, xmlParser, xmlSerializer, rtpClient,
                mock(io.openfednow.processing.cancellation.CancellationService.class));
    }

    // ── health ───────────────────────────────────────────────────────────────

    @Test
    void healthReturns200() {
        ResponseEntity<String> response = gateway.health();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void healthBodyIdentifiesGateway() {
        ResponseEntity<String> response = gateway.health();
        assertThat(response.getBody())
                .contains("RTP Gateway")
                .contains("XML parsing and serialization active");
    }

    // ── receiveTransfer (JSON path) ───────────────────────────────────────────

    @Test
    void receiveTransfer_json_routesInboundToMessageRouter() throws Exception {
        Pacs002Message acsc = Pacs002Message.accepted("E2E-RTP-001", "TXN-RTP-001");
        when(messageRouter.routeInbound(any(), any())).thenReturn(ResponseEntity.ok(acsc));

        ResponseEntity<?> actual = gateway.receiveTransfer(
                toJson(buildMessage("E2E-RTP-001", "TXN-RTP-001", "250.00")),
                MediaType.APPLICATION_JSON_VALUE);

        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(messageRouter).routeInbound(any(), eq(io.openfednow.gateway.Rail.RTP));
    }

    @Test
    void receiveTransfer_json_returnsMessageRouterResponseUnmodified() throws Exception {
        Pacs002Message rjct = Pacs002Message.rejected(
                "E2E-RTP-002", "TXN-RTP-002", "AM04", "Insufficient funds");
        when(messageRouter.routeInbound(any(), any())).thenReturn(ResponseEntity.ok(rjct));

        ResponseEntity<?> actual = gateway.receiveTransfer(
                toJson(buildMessage("E2E-RTP-002", "TXN-RTP-002", "500.00")),
                MediaType.APPLICATION_JSON_VALUE);

        Pacs002Message body = (Pacs002Message) actual.getBody();
        assertThat(body.getTransactionStatus()).isEqualTo(RJCT);
        assertThat(body.getRejectReasonCode()).isEqualTo("AM04");
    }

    // ── receiveTransfer (XML path) ────────────────────────────────────────────

    @Test
    void receiveTransfer_xml_invokesTchCertificateValidation() {
        Pacs002Message acsc = Pacs002Message.accepted("E2E-RTP-XML-001", "TXN-RTP-XML-001");
        when(messageRouter.routeInbound(any(), any())).thenReturn(ResponseEntity.ok(acsc));

        gateway.receiveTransfer(buildPacs008Xml("E2E-RTP-XML-001"), MediaType.APPLICATION_XML_VALUE);

        verify(certificateManager).validateTchClientCertificate();
    }

    @Test
    void receiveTransfer_xml_returnsXmlContentType() {
        Pacs002Message acsc = Pacs002Message.accepted("E2E-XML-002", "TXN-XML-002");
        when(messageRouter.routeInbound(any(), any())).thenReturn(ResponseEntity.ok(acsc));

        ResponseEntity<?> response = gateway.receiveTransfer(
                buildPacs008Xml("E2E-XML-002"), MediaType.APPLICATION_XML_VALUE);

        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_XML);
        assertThat((String) response.getBody()).contains("pacs.002.001.10");
        assertThat((String) response.getBody()).contains("<TxSts>ACSC</TxSts>");
    }

    @Test
    void receiveTransfer_json_invokesTchCertificateValidation() throws Exception {
        when(messageRouter.routeInbound(any(), any())).thenReturn(
                ResponseEntity.ok(Pacs002Message.accepted("E2E-CERT-001", "TXN-CERT-001")));

        gateway.receiveTransfer(
                toJson(buildMessage("E2E-CERT-001", "TXN-CERT-001", "100.00")),
                MediaType.APPLICATION_JSON_VALUE);

        // TCH cert validation is called for all inbound — JSON sandbox path included
        verify(certificateManager).validateTchClientCertificate();
    }

    // ── sendTransfer ──────────────────────────────────────────────────────────

    @Test
    void sendTransfer_delegatesToRtpClient() {
        Pacs008Message outbound = buildMessage("E2E-SEND-001", "TXN-SEND-001", "300.00");
        Pacs002Message acsc = Pacs002Message.accepted("E2E-SEND-001", "TXN-SEND-001");
        when(rtpClient.submitCreditTransfer(any())).thenReturn(acsc);

        ResponseEntity<Pacs002Message> response = gateway.sendTransfer(outbound);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getTransactionStatus()).isEqualTo(ACSC);
        verify(rtpClient).submitCreditTransfer(any());
    }

    @Test
    void sendTransfer_invokesTchCertificateValidation() {
        when(rtpClient.submitCreditTransfer(any()))
                .thenReturn(Pacs002Message.accepted("E2E-CERT-OUT-001", "TXN-CERT-OUT-001"));

        gateway.sendTransfer(buildMessage("E2E-CERT-OUT-001", "TXN-CERT-OUT-001", "200.00"));

        verify(certificateManager).validateTchClientCertificate();
    }

    @Test
    void sendTransfer_propagatesRjctFromRtpClient() {
        Pacs002Message rjct = Pacs002Message.rejected(
                "E2E-RJCT-001", "TXN-RJCT-001", "AC06", "Account blocked");
        when(rtpClient.submitCreditTransfer(any())).thenReturn(rjct);

        ResponseEntity<Pacs002Message> response =
                gateway.sendTransfer(buildMessage("E2E-RJCT-001", "TXN-RJCT-001", "100.00"));

        assertThat(response.getBody().getTransactionStatus()).isEqualTo(RJCT);
        assertThat(response.getBody().getRejectReasonCode()).isEqualTo("AC06");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String toJson(Pacs008Message message) throws Exception {
        return mapper.writeValueAsString(message);
    }

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
                .debtorAccountNumber("ACC-RTP-99999")
                .creditorAccountNumber("ACC-RTP-12345")
                .debtorName("Alice Smith")
                .creditorName("Bob Jones")
                .build();
    }

    /** Builds a minimal canonical RTP pacs.008 XML envelope for testing the XML inbound path. */
    private String buildPacs008Xml(String endToEndId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">
                  <FIToFICstmrCdtTrf>
                    <GrpHdr>
                      <MsgId>MSG-%s</MsgId>
                      <CreDtTm>2024-01-15T10:30:00Z</CreDtTm>
                      <NbOfTxs>1</NbOfTxs>
                    </GrpHdr>
                    <CdtTrfTxInf>
                      <PmtId>
                        <EndToEndId>%s</EndToEndId>
                        <TxId>TXN-XML-001</TxId>
                      </PmtId>
                      <IntrBkSttlmAmt Ccy="USD">100.00</IntrBkSttlmAmt>
                      <DbtrAgt><FinInstnId><ClrSysMmbId><MmbId>021000021</MmbId></ClrSysMmbId></FinInstnId></DbtrAgt>
                      <CdtrAgt><FinInstnId><ClrSysMmbId><MmbId>026009593</MmbId></ClrSysMmbId></FinInstnId></CdtrAgt>
                      <Dbtr><Nm>Alice Smith</Nm></Dbtr>
                      <DbtrAcct><Id><Othr><Id>ACC-001</Id></Othr></Id></DbtrAcct>
                      <Cdtr><Nm>Bob Jones</Nm></Cdtr>
                      <CdtrAcct><Id><Othr><Id>ACC-002</Id></Othr></Id></CdtrAcct>
                    </CdtTrfTxInf>
                  </FIToFICstmrCdtTrf>
                </Document>""".formatted(endToEndId, endToEndId);
    }
}
