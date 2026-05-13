package io.openfednow.gateway;

import io.openfednow.iso20022.Pacs008Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RtpXmlParser} — no Spring context required.
 *
 * <p>Verifies that the canonical ISO 20022 pacs.008 XML format used by the RTP® network
 * parses into the same {@link Pacs008Message} domain object produced by the FedNow JSON
 * path, confirming the rail-agnostic claim made in ADR-0005.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>Full pacs.008 XML with all fields → correct field mapping</li>
 *   <li>Amount and currency extracted correctly</li>
 *   <li>Routing numbers extracted from nested FinInstnId/ClrSysMmbId/MmbId</li>
 *   <li>Account numbers extracted from Othr/Id</li>
 *   <li>Remittance information is optional</li>
 *   <li>Malformed XML throws {@link RtpXmlParser.RtpXmlParseException}</li>
 *   <li>Missing required fields throw {@link RtpXmlParser.RtpXmlParseException}</li>
 *   <li>XXE attack payload is rejected</li>
 * </ul>
 */
class RtpXmlParserTest {

    private RtpXmlParser parser;

    @BeforeEach
    void setUp() {
        parser = new RtpXmlParser();
    }

    // ── Happy path: full message ──────────────────────────────────────────────

    @Test
    void parse_fullMessage_extractsMessageId() {
        Pacs008Message msg = parser.parse(fullXml());
        assertThat(msg.getMessageId()).isEqualTo("MSG-RTP-001");
    }

    @Test
    void parse_fullMessage_extractsEndToEndId() {
        Pacs008Message msg = parser.parse(fullXml());
        assertThat(msg.getEndToEndId()).isEqualTo("E2E-RTP-001");
    }

    @Test
    void parse_fullMessage_extractsTransactionId() {
        Pacs008Message msg = parser.parse(fullXml());
        assertThat(msg.getTransactionId()).isEqualTo("TXN-RTP-001");
    }

    @Test
    void parse_fullMessage_extractsAmount() {
        Pacs008Message msg = parser.parse(fullXml());
        assertThat(msg.getInterbankSettlementAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    void parse_fullMessage_extractsCurrency() {
        Pacs008Message msg = parser.parse(fullXml());
        assertThat(msg.getInterbankSettlementCurrency()).isEqualTo("USD");
    }

    @Test
    void parse_fullMessage_extractsDebtorRoutingNumber() {
        Pacs008Message msg = parser.parse(fullXml());
        assertThat(msg.getDebtorAgentRoutingNumber()).isEqualTo("021000021");
    }

    @Test
    void parse_fullMessage_extractsCreditorRoutingNumber() {
        Pacs008Message msg = parser.parse(fullXml());
        assertThat(msg.getCreditorAgentRoutingNumber()).isEqualTo("021000089");
    }

    @Test
    void parse_fullMessage_extractsDebtorAccountNumber() {
        Pacs008Message msg = parser.parse(fullXml());
        assertThat(msg.getDebtorAccountNumber()).isEqualTo("111222333");
    }

    @Test
    void parse_fullMessage_extractsCreditorAccountNumber() {
        Pacs008Message msg = parser.parse(fullXml());
        assertThat(msg.getCreditorAccountNumber()).isEqualTo("444555666");
    }

    @Test
    void parse_fullMessage_extractsDebtorName() {
        Pacs008Message msg = parser.parse(fullXml());
        assertThat(msg.getDebtorName()).isEqualTo("Alice Smith");
    }

    @Test
    void parse_fullMessage_extractsCreditorName() {
        Pacs008Message msg = parser.parse(fullXml());
        assertThat(msg.getCreditorName()).isEqualTo("Bob Jones");
    }

    @Test
    void parse_fullMessage_extractsRemittanceInformation() {
        Pacs008Message msg = parser.parse(fullXml());
        assertThat(msg.getRemittanceInformation()).isEqualTo("Invoice #12345");
    }

    @Test
    void parse_fullMessage_extractsNumberOfTransactions() {
        Pacs008Message msg = parser.parse(fullXml());
        assertThat(msg.getNumberOfTransactions()).isEqualTo(1);
    }

    @Test
    void parse_fullMessage_parsesCreationDateTime() {
        Pacs008Message msg = parser.parse(fullXml());
        assertThat(msg.getCreationDateTime()).isNotNull();
    }

    // ── Optional fields ───────────────────────────────────────────────────────

    @Test
    void parse_messageWithoutRemittanceInfo_returnsNullRemittance() {
        Pacs008Message msg = parser.parse(xmlWithoutRemittance());
        assertThat(msg.getRemittanceInformation()).isNull();
    }

    // ── Rail-agnostic convergence ─────────────────────────────────────────────

    @Test
    void parse_producesMessageWithSameFieldsAsJsonPath() {
        // The parsed RTP XML message should be functionally identical to a JSON-constructed
        // Pacs008Message — proving Layers 2-4 need no changes for RTP support.
        Pacs008Message fromXml = parser.parse(fullXml());
        Pacs008Message fromJson = Pacs008Message.builder()
                .messageId("MSG-RTP-001")
                .endToEndId("E2E-RTP-001")
                .transactionId("TXN-RTP-001")
                .interbankSettlementAmount(new BigDecimal("500.00"))
                .interbankSettlementCurrency("USD")
                .debtorAgentRoutingNumber("021000021")
                .creditorAgentRoutingNumber("021000089")
                .debtorAccountNumber("111222333")
                .creditorAccountNumber("444555666")
                .debtorName("Alice Smith")
                .creditorName("Bob Jones")
                .remittanceInformation("Invoice #12345")
                .numberOfTransactions(1)
                .build();

        assertThat(fromXml.getEndToEndId()).isEqualTo(fromJson.getEndToEndId());
        assertThat(fromXml.getTransactionId()).isEqualTo(fromJson.getTransactionId());
        assertThat(fromXml.getInterbankSettlementAmount())
                .isEqualByComparingTo(fromJson.getInterbankSettlementAmount());
        assertThat(fromXml.getCreditorAccountNumber()).isEqualTo(fromJson.getCreditorAccountNumber());
        assertThat(fromXml.getDebtorAgentRoutingNumber()).isEqualTo(fromJson.getDebtorAgentRoutingNumber());
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    void parse_malformedXml_throwsRtpXmlParseException() {
        assertThatThrownBy(() -> parser.parse("<not valid xml<<"))
                .isInstanceOf(RtpXmlParser.RtpXmlParseException.class);
    }

    @Test
    void parse_missingEndToEndId_throwsRtpXmlParseException() {
        String xml = fullXml().replace("<EndToEndId>E2E-RTP-001</EndToEndId>", "");
        assertThatThrownBy(() -> parser.parse(xml))
                .isInstanceOf(RtpXmlParser.RtpXmlParseException.class);
    }

    @Test
    void parse_missingMsgId_throwsRtpXmlParseException() {
        String xml = fullXml().replace("<MsgId>MSG-RTP-001</MsgId>", "");
        assertThatThrownBy(() -> parser.parse(xml))
                .isInstanceOf(RtpXmlParser.RtpXmlParseException.class);
    }

    @Test
    void parse_emptyString_throwsRtpXmlParseException() {
        assertThatThrownBy(() -> parser.parse(""))
                .isInstanceOf(RtpXmlParser.RtpXmlParseException.class);
    }

    @Test
    void parse_xxeAttackPayload_isRejected() {
        String xxeXml = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">
                  <FIToFICstmrCdtTrf>&xxe;</FIToFICstmrCdtTrf>
                </Document>
                """;
        assertThatThrownBy(() -> parser.parse(xxeXml))
                .isInstanceOf(RtpXmlParser.RtpXmlParseException.class);
    }

    // ── XML fixtures ──────────────────────────────────────────────────────────

    private static String fullXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">
                  <FIToFICstmrCdtTrf>
                    <GrpHdr>
                      <MsgId>MSG-RTP-001</MsgId>
                      <CreDtTm>2024-01-15T10:30:00Z</CreDtTm>
                      <NbOfTxs>1</NbOfTxs>
                    </GrpHdr>
                    <CdtTrfTxInf>
                      <PmtId>
                        <EndToEndId>E2E-RTP-001</EndToEndId>
                        <TxId>TXN-RTP-001</TxId>
                      </PmtId>
                      <IntrBkSttlmAmt Ccy="USD">500.00</IntrBkSttlmAmt>
                      <DbtrAgt>
                        <FinInstnId>
                          <ClrSysMmbId><MmbId>021000021</MmbId></ClrSysMmbId>
                        </FinInstnId>
                      </DbtrAgt>
                      <CdtrAgt>
                        <FinInstnId>
                          <ClrSysMmbId><MmbId>021000089</MmbId></ClrSysMmbId>
                        </FinInstnId>
                      </CdtrAgt>
                      <Dbtr><Nm>Alice Smith</Nm></Dbtr>
                      <DbtrAcct><Id><Othr><Id>111222333</Id></Othr></Id></DbtrAcct>
                      <Cdtr><Nm>Bob Jones</Nm></Cdtr>
                      <CdtrAcct><Id><Othr><Id>444555666</Id></Othr></Id></CdtrAcct>
                      <RmtInf><Ustrd>Invoice #12345</Ustrd></RmtInf>
                    </CdtTrfTxInf>
                  </FIToFICstmrCdtTrf>
                </Document>
                """;
    }

    private static String xmlWithoutRemittance() {
        return fullXml().replaceAll("\\s*<RmtInf>.*?</RmtInf>", "");
    }
}
