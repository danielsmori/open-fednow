package io.openfednow.gateway;

import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.OffsetDateTime;

/**
 * Layer 1 — RTP ISO 20022 XML Serializer and pacs.002 Parser
 *
 * <p>Handles the canonical ISO 20022 XML envelope format used by The Clearing
 * House's RTP® network for outbound messages and for parsing TCH responses.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>{@link #serializePacs008} — converts a {@link Pacs008Message} domain object
 *       to a canonical ISO 20022 pacs.008.001.08 XML string for submission to TCH</li>
 *   <li>{@link #serializePacs002} — converts a {@link Pacs002Message} domain object
 *       to a canonical ISO 20022 pacs.002.001.10 XML string for returning a payment
 *       status report to the inbound TCH caller</li>
 *   <li>{@link #parsePacs002} — parses a pacs.002.001.10 XML envelope received
 *       from TCH as the acknowledgment of our outbound pacs.008 submission</li>
 * </ul>
 *
 * <p>This class is the outbound counterpart of {@link RtpXmlParser}, which handles
 * inbound pacs.008 parsing. Together they complete the canonical ISO 20022 XML
 * round-trip required for RTP® network operation.
 *
 * <h2>Expected pacs.002 XML structure</h2>
 * <pre>{@code
 * <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
 *   <FIToFIPmtStsRpt>
 *     <GrpHdr>
 *       <MsgId>STATUS-001</MsgId>
 *       <CreDtTm>2024-01-15T10:30:00Z</CreDtTm>
 *     </GrpHdr>
 *     <TxInfAndSts>
 *       <OrgnlEndToEndId>E2E-001</OrgnlEndToEndId>
 *       <OrgnlTxId>TXN-001</OrgnlTxId>
 *       <TxSts>ACSC</TxSts>
 *       <!-- RJCT responses also include StsRsnInf/Rsn/Cd -->
 *     </TxInfAndSts>
 *   </FIToFIPmtStsRpt>
 * </Document>
 * }</pre>
 *
 * @see RtpXmlParser
 * @see <a href="../../../../../../../../docs/rtp-compatibility.md">rtp-compatibility.md</a>
 */
@Component
public class RtpXmlSerializer {

    private static final Logger log = LoggerFactory.getLogger(RtpXmlSerializer.class);

    private static final String NS_PACS008 =
            "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08";
    private static final String NS_PACS002 =
            "urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10";

    // ── pacs.008 serialization ────────────────────────────────────────────────

    /**
     * Serializes a {@link Pacs008Message} to a canonical ISO 20022 pacs.008.001.08
     * XML envelope for submission to the TCH RTP® network.
     *
     * <p>The produced XML uses the same structure that {@link RtpXmlParser} parses,
     * making roundtrip testing straightforward.
     *
     * @param msg the domain message to serialize
     * @return UTF-8 encoded ISO 20022 pacs.008 XML string
     */
    public String serializePacs008(Pacs008Message msg) {
        String amount = msg.getInterbankSettlementAmount()
                .stripTrailingZeros().toPlainString();
        String creDtTm = msg.getCreationDateTime().toString();

        String remittance = msg.getRemittanceInformation() != null
                ? "<RmtInf><Ustrd>" + escape(msg.getRemittanceInformation()) + "</Ustrd></RmtInf>"
                : "";
        String debtorName = msg.getDebtorName() != null
                ? "<Dbtr><Nm>" + escape(msg.getDebtorName()) + "</Nm></Dbtr>"
                : "";
        String creditorName = msg.getCreditorName() != null
                ? "<Cdtr><Nm>" + escape(msg.getCreditorName()) + "</Nm></Cdtr>"
                : "";

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="%s">
                  <FIToFICstmrCdtTrf>
                    <GrpHdr>
                      <MsgId>%s</MsgId>
                      <CreDtTm>%s</CreDtTm>
                      <NbOfTxs>%d</NbOfTxs>
                    </GrpHdr>
                    <CdtTrfTxInf>
                      <PmtId>
                        <EndToEndId>%s</EndToEndId>
                        <TxId>%s</TxId>
                      </PmtId>
                      <IntrBkSttlmAmt Ccy="%s">%s</IntrBkSttlmAmt>
                      <DbtrAgt><FinInstnId><ClrSysMmbId><MmbId>%s</MmbId></ClrSysMmbId></FinInstnId></DbtrAgt>
                      <CdtrAgt><FinInstnId><ClrSysMmbId><MmbId>%s</MmbId></ClrSysMmbId></FinInstnId></CdtrAgt>
                      %s
                      <DbtrAcct><Id><Othr><Id>%s</Id></Othr></Id></DbtrAcct>
                      %s
                      <CdtrAcct><Id><Othr><Id>%s</Id></Othr></Id></CdtrAcct>
                      %s
                    </CdtTrfTxInf>
                  </FIToFICstmrCdtTrf>
                </Document>""".formatted(
                NS_PACS008,
                escape(msg.getMessageId()),
                creDtTm,
                msg.getNumberOfTransactions(),
                escape(msg.getEndToEndId()),
                escape(msg.getTransactionId()),
                escape(msg.getInterbankSettlementCurrency()),
                amount,
                escape(msg.getDebtorAgentRoutingNumber()),
                escape(msg.getCreditorAgentRoutingNumber()),
                debtorName,
                escape(msg.getDebtorAccountNumber()),
                creditorName,
                escape(msg.getCreditorAccountNumber()),
                remittance
        );
    }

    // ── pacs.002 serialization ────────────────────────────────────────────────

    /**
     * Serializes a {@link Pacs002Message} to a canonical ISO 20022 pacs.002.001.10
     * XML envelope for returning a payment status report to the RTP® network.
     *
     * <p>This is used when the RTP gateway returns a status response to an inbound
     * TCH pacs.008 credit transfer. FedNow uses JSON for the equivalent response;
     * the RTP rail requires the canonical ISO 20022 XML format.
     *
     * @param msg the domain status report to serialize
     * @return UTF-8 encoded ISO 20022 pacs.002 XML string
     */
    public String serializePacs002(Pacs002Message msg) {
        String msgId = msg.getMessageId() != null ? escape(msg.getMessageId()) : "STATUS-" + System.currentTimeMillis();
        String creDtTm = msg.getCreationDateTime() != null
                ? msg.getCreationDateTime().toString()
                : OffsetDateTime.now().toString();
        String status = msg.getTransactionStatus() != null
                ? msg.getTransactionStatus().name()
                : "RJCT";

        String statusReasonBlock = "";
        if (Pacs002Message.TransactionStatus.RJCT.name().equals(status)
                && msg.getRejectReasonCode() != null) {
            String addtlInf = msg.getRejectReasonDescription() != null
                    ? "<AddtlInf>" + escape(msg.getRejectReasonDescription()) + "</AddtlInf>"
                    : "";
            statusReasonBlock = """
                      <StsRsnInf>
                        <Rsn><Cd>%s</Cd></Rsn>
                        %s
                      </StsRsnInf>""".formatted(
                    escape(msg.getRejectReasonCode()), addtlInf);
        }

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="%s">
                  <FIToFIPmtStsRpt>
                    <GrpHdr>
                      <MsgId>%s</MsgId>
                      <CreDtTm>%s</CreDtTm>
                    </GrpHdr>
                    <TxInfAndSts>
                      <OrgnlEndToEndId>%s</OrgnlEndToEndId>
                      <OrgnlTxId>%s</OrgnlTxId>
                      <TxSts>%s</TxSts>
                      %s
                    </TxInfAndSts>
                  </FIToFIPmtStsRpt>
                </Document>""".formatted(
                NS_PACS002,
                msgId,
                creDtTm,
                msg.getOriginalEndToEndId() != null ? escape(msg.getOriginalEndToEndId()) : "",
                msg.getOriginalTransactionId() != null ? escape(msg.getOriginalTransactionId()) : "",
                status,
                statusReasonBlock
        );
    }

    // ── pacs.002 parsing ──────────────────────────────────────────────────────

    /**
     * Parses a canonical ISO 20022 pacs.002.001.10 XML envelope received from the
     * TCH RTP® network as an acknowledgment of our outbound pacs.008 submission.
     *
     * <p>XXE protection is applied via disabled DOCTYPE declarations and external
     * entity processing, the same defensive configuration used in {@link RtpXmlParser}.
     *
     * @param xml the pacs.002 XML string from TCH
     * @return parsed {@link Pacs002Message}
     * @throws IllegalStateException if the XML is malformed or missing required fields
     */
    public Pacs002Message parsePacs002(String xml) {
        Document doc = parseXml(xml);

        String messageId   = firstText(doc, "MsgId");
        String creDtTmRaw  = firstText(doc, "CreDtTm");
        String originalE2E = firstText(doc, "OrgnlEndToEndId");
        String originalTxId = firstText(doc, "OrgnlTxId");
        String txSts        = firstText(doc, "TxSts");

        // Rejection fields (present only for RJCT)
        String reasonCode = firstText(doc, "Cd");
        String reasonDesc = firstText(doc, "AddtlInf");

        Pacs002Message.TransactionStatus status;
        try {
            status = Pacs002Message.TransactionStatus.valueOf(txSts != null ? txSts.trim() : "RJCT");
        } catch (IllegalArgumentException e) {
            log.warn("RTP pacs.002: unknown TxSts '{}', defaulting to RJCT", txSts);
            status = Pacs002Message.TransactionStatus.RJCT;
        }

        OffsetDateTime creationDateTime;
        try {
            creationDateTime = creDtTmRaw != null ? OffsetDateTime.parse(creDtTmRaw) : OffsetDateTime.now();
        } catch (Exception e) {
            log.warn("RTP pacs.002: could not parse CreDtTm '{}', using now()", creDtTmRaw);
            creationDateTime = OffsetDateTime.now();
        }

        log.debug("RTP pacs.002 parsed: originalTxId={} status={}", originalTxId, status);

        return Pacs002Message.builder()
                .messageId(messageId)
                .creationDateTime(creationDateTime)
                .originalEndToEndId(originalE2E)
                .originalTransactionId(originalTxId)
                .transactionStatus(status)
                .rejectReasonCode(reasonCode)
                .rejectReasonDescription(reasonDesc)
                .build();
    }

    // ── XML helpers ───────────────────────────────────────────────────────────

    private Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse RTP pacs.002 XML: " + e.getMessage(), e);
        }
    }

    private String firstText(Document doc, String localName) {
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) return null;
        return nodes.item(0).getTextContent().trim();
    }

    /** Escapes XML special characters in generated field values. */
    static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;");
    }
}
