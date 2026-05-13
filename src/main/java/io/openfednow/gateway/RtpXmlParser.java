package io.openfednow.gateway;

import io.openfednow.iso20022.Pacs008Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Layer 1 — RTP ISO 20022 XML Envelope Parser (reference mode)
 *
 * <p>Parses a canonical ISO 20022 pacs.008.001.08 XML envelope as used by The
 * Clearing House's RTP® network into the internal {@link Pacs008Message} domain
 * object. The resulting message is identical to one produced by the FedNow JSON
 * path — from this point Layers 2–4 are completely rail-agnostic.
 *
 * <p><strong>Reference mode:</strong> This parser handles the message structure
 * of the canonical ISO 20022 XML format. Live RTP connectivity additionally
 * requires TCH certificate validation and a private-network transport, neither
 * of which is implemented here (see {@link RtpGateway} and ADR-0005).
 *
 * <h2>Expected XML structure</h2>
 * <pre>{@code
 * <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">
 *   <FIToFICstmrCdtTrf>
 *     <GrpHdr>
 *       <MsgId>MSG-001</MsgId>
 *       <CreDtTm>2024-01-15T10:30:00Z</CreDtTm>
 *       <NbOfTxs>1</NbOfTxs>
 *     </GrpHdr>
 *     <CdtTrfTxInf>
 *       <PmtId>
 *         <EndToEndId>E2E-001</EndToEndId>
 *         <TxId>TXN-001</TxId>
 *       </PmtId>
 *       <IntrBkSttlmAmt Ccy="USD">250.00</IntrBkSttlmAmt>
 *       <DbtrAgt><FinInstnId><ClrSysMmbId><MmbId>021000021</MmbId></ClrSysMmbId></FinInstnId></DbtrAgt>
 *       <CdtrAgt><FinInstnId><ClrSysMmbId><MmbId>021000089</MmbId></ClrSysMmbId></FinInstnId></CdtrAgt>
 *       <Dbtr><Nm>Alice Smith</Nm></Dbtr>
 *       <DbtrAcct><Id><Othr><Id>123456789</Id></Othr></Id></DbtrAcct>
 *       <Cdtr><Nm>Bob Jones</Nm></Cdtr>
 *       <CdtrAcct><Id><Othr><Id>987654321</Id></Othr></Id></CdtrAcct>
 *       <RmtInf><Ustrd>Invoice #12345</Ustrd></RmtInf>
 *     </CdtTrfTxInf>
 *   </FIToFICstmrCdtTrf>
 * </Document>
 * }</pre>
 *
 * @see RtpGateway
 * @see <a href="../../../../../../../../docs/adr/0005-dual-rail-architecture-fednow-rtp.md">ADR-0005</a>
 */
@Component
public class RtpXmlParser {

    private static final Logger log = LoggerFactory.getLogger(RtpXmlParser.class);

    /**
     * Parses a canonical ISO 20022 pacs.008 XML string into a {@link Pacs008Message}.
     *
     * @param xml the RTP ISO 20022 XML envelope (UTF-8 string)
     * @return the parsed domain message, ready for routing through Layers 2–4
     * @throws RtpXmlParseException if the XML is malformed or missing required fields
     */
    public Pacs008Message parse(String xml) {
        Document doc = parseXml(xml);

        Element grpHdr = requiredElement(doc, "GrpHdr");
        Element txInf  = requiredElement(doc, "CdtTrfTxInf");

        String messageId = text(grpHdr, "MsgId");
        String creationDateTimeRaw = text(grpHdr, "CreDtTm");
        int numberOfTransactions = Integer.parseInt(text(grpHdr, "NbOfTxs"));

        Element pmtId = requiredChildElement(txInf, "PmtId");
        String endToEndId   = text(pmtId, "EndToEndId");
        String transactionId = text(pmtId, "TxId");

        Element amountEl = requiredChildElement(txInf, "IntrBkSttlmAmt");
        BigDecimal amount = new BigDecimal(amountEl.getTextContent().trim());
        String currency = amountEl.getAttribute("Ccy");

        String debtorRouting  = mmbId(txInf, "DbtrAgt");
        String creditorRouting = mmbId(txInf, "CdtrAgt");
        String debtorName     = optionalText(txInf, "Dbtr", "Nm");
        String debtorAccount  = accountId(txInf, "DbtrAcct");
        String creditorName   = optionalText(txInf, "Cdtr", "Nm");
        String creditorAccount = accountId(txInf, "CdtrAcct");
        String remittanceInfo = optionalTextDirect(txInf, "RmtInf", "Ustrd");

        OffsetDateTime creationDateTime;
        try {
            creationDateTime = OffsetDateTime.parse(creationDateTimeRaw);
        } catch (Exception e) {
            log.warn("RTP XML: could not parse CreDtTm '{}', using now()", creationDateTimeRaw);
            creationDateTime = OffsetDateTime.now();
        }

        log.debug("RTP XML parsed: messageId={} endToEndId={} amount={} {}",
                messageId, endToEndId, amount, currency);

        return Pacs008Message.builder()
                .messageId(messageId)
                .creationDateTime(creationDateTime)
                .numberOfTransactions(numberOfTransactions)
                .endToEndId(endToEndId)
                .transactionId(transactionId)
                .interbankSettlementAmount(amount)
                .interbankSettlementCurrency(currency)
                .debtorAgentRoutingNumber(debtorRouting)
                .creditorAgentRoutingNumber(creditorRouting)
                .debtorName(debtorName)
                .debtorAccountNumber(debtorAccount)
                .creditorName(creditorName)
                .creditorAccountNumber(creditorAccount)
                .remittanceInformation(remittanceInfo)
                .build();
    }

    // ── XML helpers ───────────────────────────────────────────────────────────

    private Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Disable external entity processing to prevent XXE attacks
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new RtpXmlParseException("Failed to parse RTP XML envelope", e);
        }
    }

    /** Returns the first matching element by local name anywhere in the document. */
    private Element requiredElement(Document doc, String localName) {
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) {
            throw new RtpXmlParseException("Required element <" + localName + "> not found in RTP XML");
        }
        return (Element) nodes.item(0);
    }

    /** Returns the first direct child element with the given local name. */
    private Element requiredChildElement(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el
                    && localName.equals(el.getLocalName())) {
                return el;
            }
        }
        throw new RtpXmlParseException(
                "Required child element <" + localName + "> not found under <" + parent.getLocalName() + ">");
    }

    /** Returns trimmed text content of the first child element with the given local name. */
    private String text(Element parent, String childLocalName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", childLocalName);
        if (nodes.getLength() == 0) {
            throw new RtpXmlParseException("Required field <" + childLocalName + "> missing");
        }
        return nodes.item(0).getTextContent().trim();
    }

    /** Returns trimmed text content of a nested element, or null if absent. */
    private String optionalText(Element parent, String childLocalName, String grandchildLocalName) {
        NodeList outer = parent.getElementsByTagNameNS("*", childLocalName);
        if (outer.getLength() == 0) return null;
        NodeList inner = ((Element) outer.item(0)).getElementsByTagNameNS("*", grandchildLocalName);
        if (inner.getLength() == 0) return null;
        return inner.item(0).getTextContent().trim();
    }

    private String optionalTextDirect(Element parent, String childLocalName, String grandchildLocalName) {
        return optionalText(parent, childLocalName, grandchildLocalName);
    }

    /** Extracts the ABA routing number from DbtrAgt/CdtrAgt → FinInstnId → ClrSysMmbId → MmbId. */
    private String mmbId(Element txInf, String agentElement) {
        NodeList agents = txInf.getElementsByTagNameNS("*", agentElement);
        if (agents.getLength() == 0) return null;
        NodeList mmbIds = ((Element) agents.item(0)).getElementsByTagNameNS("*", "MmbId");
        if (mmbIds.getLength() == 0) return null;
        return mmbIds.item(0).getTextContent().trim();
    }

    /** Extracts the account number from DbtrAcct/CdtrAcct → Id → Othr → Id. */
    private String accountId(Element txInf, String accountElement) {
        NodeList accounts = txInf.getElementsByTagNameNS("*", accountElement);
        if (accounts.getLength() == 0) return null;
        NodeList ids = ((Element) accounts.item(0)).getElementsByTagNameNS("*", "Id");
        for (int i = 0; i < ids.getLength(); i++) {
            NodeList othr = ((Element) ids.item(i)).getElementsByTagNameNS("*", "Othr");
            if (othr.getLength() > 0) {
                NodeList inner = ((Element) othr.item(0)).getElementsByTagNameNS("*", "Id");
                if (inner.getLength() > 0) return inner.item(0).getTextContent().trim();
            }
        }
        return null;
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    /** Thrown when the RTP XML envelope cannot be parsed into a valid {@link Pacs008Message}. */
    public static class RtpXmlParseException extends RuntimeException {
        public RtpXmlParseException(String message) { super(message); }
        public RtpXmlParseException(String message, Throwable cause) { super(message, cause); }
    }
}
