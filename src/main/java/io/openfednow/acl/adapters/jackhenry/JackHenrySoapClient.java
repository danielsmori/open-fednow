package io.openfednow.acl.adapters.jackhenry;

import io.openfednow.acl.core.CoreBankingResponse;
import io.openfednow.iso20022.Pacs008Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

/**
 * SOAP transport layer for the Jack Henry jXchange Service Gateway.
 *
 * <p>Constructs SOAP XML envelopes for the jXchange {@code TrnAdd} (transaction
 * posting), {@code AcctBalInq} (balance inquiry), and {@code Ping} (health check)
 * operations, posts them over HTTPS, and parses the XML responses into
 * {@link CoreBankingResponse} using {@link JackHenryReasonCodeMapper}.
 *
 * <h2>Protocol</h2>
 * <p>jXchange uses SOAP over HTTPS. Requests are sent as {@code text/xml} with a
 * {@code SOAPAction} header identifying the operation. Every request except {@code Ping}
 * must include a {@code jXchangeHdr_CType} element in the SOAP header.
 *
 * <h2>jXchangeHdr_CType required fields</h2>
 * <ul>
 *   <li>{@code AuditUsrId} — audit user identifier (max 24 chars)</li>
 *   <li>{@code AuditWsId} — audit workstation identifier (max 24 chars)</li>
 *   <li>{@code ValidConsmName} — consumer name for security validation (max 15 chars)</li>
 *   <li>{@code ValidConsmProd} — consumer product for security validation (max 25 chars)</li>
 *   <li>{@code InstRtId} — institution 9-digit ABA routing number</li>
 *   <li>{@code jXLogTrackingId} — per-request GUID for distributed tracing</li>
 *   <li>{@code BusCorrelId} — business correlation GUID</li>
 * </ul>
 *
 * <h2>Amount format</h2>
 * <p>jXchange accepts monetary amounts as plain decimal strings (e.g. {@code "1000.50"}),
 * two decimal places. This differs from the Fiserv adapter, which uses fixed-point cent
 * integers ({@code "100050"}).
 *
 * <h2>API endpoint</h2>
 * <ul>
 *   <li>{@code POST /jxchange/2008/ServiceGateway/ServiceGateway.svc} — all operations</li>
 * </ul>
 *
 * @see <a href="https://jackhenry.dev/jxchange-soap/overview/portal-navigation/jxchange-environment/">
 *      Jack Henry jXchange Environment Overview</a>
 */
public class JackHenrySoapClient {

    private static final Logger log = LoggerFactory.getLogger(JackHenrySoapClient.class);

    /** Default SOAP endpoint path for the jXchange Service Gateway. */
    public static final String PATH_SERVICE_GATEWAY =
            "/jxchange/2008/ServiceGateway/ServiceGateway.svc";

    private static final String NS_SOAP = "http://www.w3.org/2003/05/soap-envelope";
    private static final String NS_JX   = "http://jackhenry.com/jxchange/2008";

    /** Consumer name registered with Jack Henry for security validation (max 15 chars). */
    private static final String CONSUMER_NAME = "OPENFEDNOW";
    /** Consumer product registered with Jack Henry for security validation (max 25 chars). */
    private static final String CONSUMER_PROD = "FEDNOW-ADAPTER";

    private final RestClient restClient;
    private final JackHenryTokenManager tokenManager;
    private final String institutionRoutingId;

    /**
     * @param restClient           configured with the jXchange Service Gateway base URL
     * @param tokenManager         manages OAuth 2.0 bearer tokens
     * @param institutionRoutingId 9-digit ABA routing number used as {@code InstRtId} in headers
     */
    public JackHenrySoapClient(RestClient restClient, JackHenryTokenManager tokenManager,
                                String institutionRoutingId) {
        this.restClient = restClient;
        this.tokenManager = tokenManager;
        this.institutionRoutingId = institutionRoutingId;
    }

    // ── Public operations ─────────────────────────────────────────────────────

    /**
     * Posts a credit transfer to the Jack Henry core banking system via {@code TrnAdd}.
     *
     * <p>The SOAP request credits the {@code CdtrAcct} (creditor account) with the
     * interbank settlement amount from the pacs.008. The pacs.008 {@code EndToEndId}
     * is carried in the {@code RefId} field for reconciliation.
     *
     * <p>Business rejections (insufficient funds, frozen account, etc.) arrive as SOAP
     * faults with an {@code ErrRec/ErrCode} in the response body and are returned as
     * {@link CoreBankingResponse.Status#REJECTED}. Network-level failures are caught
     * and returned as {@link CoreBankingResponse.Status#TIMEOUT} so the circuit breaker
     * can accumulate failures without unchecked exceptions propagating.
     *
     * @param message validated pacs.008 credit transfer
     * @return normalized {@link CoreBankingResponse}
     */
    public CoreBankingResponse postTransaction(Pacs008Message message) {
        String trackingId = UUID.randomUUID().toString();
        String correlId   = UUID.randomUUID().toString();
        String envelope   = buildTrnAddEnvelope(message, trackingId, correlId);
        // Pre-fetch the token before building the RestClient chain to prevent
        // nested HTTP calls that can trigger stale keep-alive connection reuse.
        String token      = tokenManager.getAccessToken();

        try {
            String responseXml = restClient.post()
                    .uri(PATH_SERVICE_GATEWAY)
                    .contentType(MediaType.TEXT_XML)
                    .header("Authorization", "Bearer " + token)
                    .header("SOAPAction", "TrnAdd")
                    .body(envelope)
                    .retrieve()
                    .body(String.class);

            return parseTrnAddResponse(responseXml);

        } catch (RestClientException e) {
            log.warn("jXchange TrnAdd SOAP error for transaction {}: {}",
                    message.getTransactionId(), e.getMessage());
            return new CoreBankingResponse(
                    CoreBankingResponse.Status.TIMEOUT, null, "NETWORK_ERROR", null);
        }
    }

    /**
     * Queries the available balance for an account via {@code AcctBalInq}.
     *
     * <p>jXchange returns the balance as a plain decimal string (e.g. {@code "5000.00"})
     * in the {@code AvailBal} element of the {@code AcctBalInqRslt}.
     *
     * @param accountId institution's internal account identifier
     * @return available balance in USD
     * @throws IllegalStateException if the response is empty or missing the balance field
     */
    public BigDecimal getBalance(String accountId) {
        String trackingId = UUID.randomUUID().toString();
        String correlId   = UUID.randomUUID().toString();
        String envelope   = buildAcctBalInqEnvelope(accountId, trackingId, correlId);
        String token      = tokenManager.getAccessToken();

        String responseXml = restClient.post()
                .uri(PATH_SERVICE_GATEWAY)
                .contentType(MediaType.TEXT_XML)
                .header("Authorization", "Bearer " + token)
                .header("SOAPAction", "AcctBalInq")
                .body(envelope)
                .retrieve()
                .body(String.class);

        return parseAcctBalInqResponse(responseXml, accountId);
    }

    /**
     * Checks whether the jXchange Service Gateway is reachable via the {@code Ping} operation.
     *
     * <p>{@code Ping} is the one jXchange operation that does not require a
     * {@code jXchangeHdr_CType} SOAP header.
     *
     * @return {@code true} if the Ping returns a valid response; {@code false} on any error
     */
    public boolean isHealthy() {
        try {
            String token    = tokenManager.getAccessToken();
            String envelope = buildPingEnvelope();
            String responseXml = restClient.post()
                    .uri(PATH_SERVICE_GATEWAY)
                    .contentType(MediaType.TEXT_XML)
                    .header("Authorization", "Bearer " + token)
                    .header("SOAPAction", "Ping")
                    .body(envelope)
                    .retrieve()
                    .body(String.class);

            Document doc = parseSoapXml(responseXml);
            NodeList ping = doc.getElementsByTagNameNS("*", "PingRslt");
            return ping.getLength() > 0;

        } catch (Exception e) {
            log.warn("jXchange Ping health check failed: {}", e.getMessage());
            return false;
        }
    }

    // ── SOAP envelope builders ────────────────────────────────────────────────

    String buildTrnAddEnvelope(Pacs008Message msg, String trackingId, String correlId) {
        // jXchange expects amounts as plain decimal strings ("1000.50"), not
        // fixed-point cent integers. Two decimal places required.
        String amount  = msg.getInterbankSettlementAmount()
                .setScale(2, RoundingMode.HALF_UP).toPlainString();
        String trnDate = LocalDate.now().toString(); // yyyy-MM-dd

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <s:Envelope xmlns:s="%s" xmlns:jx="%s">
                  <s:Header>
                    %s
                  </s:Header>
                  <s:Body>
                    <jx:TrnAddRqst>
                      <jx:AcctId>%s</jx:AcctId>
                      <jx:AcctType>DDA</jx:AcctType>
                      <jx:TrnAmt>%s</jx:TrnAmt>
                      <jx:TrnCode>60</jx:TrnCode>
                      <jx:TrnType>CREDIT</jx:TrnType>
                      <jx:TrnDt>%s</jx:TrnDt>
                      <jx:RefId>%s</jx:RefId>
                      <jx:Desc>FedNow Credit Transfer</jx:Desc>
                    </jx:TrnAddRqst>
                  </s:Body>
                </s:Envelope>""".formatted(
                NS_SOAP, NS_JX,
                buildJxchangeHeader(trackingId, correlId),
                escapeXml(msg.getCreditorAccountNumber()),
                amount,
                trnDate,
                escapeXml(msg.getEndToEndId())
        );
    }

    String buildAcctBalInqEnvelope(String accountId, String trackingId, String correlId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <s:Envelope xmlns:s="%s" xmlns:jx="%s">
                  <s:Header>
                    %s
                  </s:Header>
                  <s:Body>
                    <jx:AcctBalInqRqst>
                      <jx:AcctId>%s</jx:AcctId>
                      <jx:AcctType>DDA</jx:AcctType>
                    </jx:AcctBalInqRqst>
                  </s:Body>
                </s:Envelope>""".formatted(
                NS_SOAP, NS_JX,
                buildJxchangeHeader(trackingId, correlId),
                escapeXml(accountId)
        );
    }

    String buildPingEnvelope() {
        // Ping does not include a jXchangeHdr_CType header per the jXchange spec.
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <s:Envelope xmlns:s="%s" xmlns:jx="%s">
                  <s:Body>
                    <jx:PingRqst/>
                  </s:Body>
                </s:Envelope>""".formatted(NS_SOAP, NS_JX);
    }

    private String buildJxchangeHeader(String trackingId, String correlId) {
        return """
                <jx:jXchangeHdr_CType>
                      <jx:AuditUsrId>OPENFEDNOW</jx:AuditUsrId>
                      <jx:AuditWsId>OPENFEDNOW-WS</jx:AuditWsId>
                      <jx:ValidConsmName>%s</jx:ValidConsmName>
                      <jx:ValidConsmProd>%s</jx:ValidConsmProd>
                      <jx:InstRtId>%s</jx:InstRtId>
                      <jx:jXLogTrackingId>%s</jx:jXLogTrackingId>
                      <jx:BusCorrelId>%s</jx:BusCorrelId>
                    </jx:jXchangeHdr_CType>""".formatted(
                CONSUMER_NAME, CONSUMER_PROD, institutionRoutingId, trackingId, correlId
        );
    }

    // ── Response parsers ──────────────────────────────────────────────────────

    CoreBankingResponse parseTrnAddResponse(String responseXml) {
        if (responseXml == null || responseXml.isBlank()) {
            return new CoreBankingResponse(
                    CoreBankingResponse.Status.TIMEOUT, null, "EMPTY_RESPONSE", null);
        }

        Document doc = parseSoapXml(responseXml);

        // jXchange returns business rejections as SOAP faults with ErrRec/ErrCode in the detail.
        NodeList faults = doc.getElementsByTagNameNS(NS_SOAP, "Fault");
        if (faults.getLength() > 0) {
            return parseSoapFaultAsRejection(doc);
        }

        // Normal TrnAddRslt — check Status element
        NodeList resultNodes = doc.getElementsByTagNameNS("*", "TrnAddRslt");
        if (resultNodes.getLength() == 0) {
            log.warn("jXchange TrnAdd response missing TrnAddRslt element");
            return new CoreBankingResponse(
                    CoreBankingResponse.Status.TIMEOUT, null, "MALFORMED_RESPONSE", null);
        }

        String trnId  = firstTextByName(doc, "TrnId");
        String status = firstTextByName(doc, "Status");

        if (JackHenryReasonCodeMapper.isPosted(status)) {
            log.debug("jXchange TrnAdd accepted, trnId={}", trnId);
            return new CoreBankingResponse(
                    CoreBankingResponse.Status.ACCEPTED, null, status, trnId);
        }

        if ("PENDING".equals(status)) {
            return new CoreBankingResponse(
                    CoreBankingResponse.Status.PENDING, null, status, trnId);
        }

        log.debug("jXchange TrnAdd non-posted status={}", status);
        return new CoreBankingResponse(
                CoreBankingResponse.Status.REJECTED,
                JackHenryReasonCodeMapper.toIso20022(null),
                status,
                trnId);
    }

    private CoreBankingResponse parseSoapFaultAsRejection(Document doc) {
        String errCode  = firstTextByName(doc, "ErrCode");
        String errDesc  = firstTextByName(doc, "ErrDesc");
        String iso20022 = JackHenryReasonCodeMapper.toIso20022(errCode);

        log.debug("jXchange SOAP fault: errCode={} errDesc={} → iso20022={}",
                errCode, errDesc, iso20022);
        return new CoreBankingResponse(
                CoreBankingResponse.Status.REJECTED, iso20022, errCode, null);
    }

    BigDecimal parseAcctBalInqResponse(String responseXml, String accountId) {
        if (responseXml == null || responseXml.isBlank()) {
            throw new IllegalStateException(
                    "jXchange AcctBalInq response empty for account: " + accountId);
        }

        Document doc = parseSoapXml(responseXml);
        String availBal = firstTextByName(doc, "AvailBal");

        if (availBal == null || availBal.isBlank()) {
            throw new IllegalStateException(
                    "jXchange AcctBalInq response missing AvailBal for account: " + accountId);
        }

        return new BigDecimal(availBal);
    }

    // ── XML helpers ───────────────────────────────────────────────────────────

    private Document parseSoapXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Disable external entity processing to prevent XXE attacks
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse jXchange SOAP response: " + e.getMessage(), e);
        }
    }

    /** Returns trimmed text content of the first element matching the given local name, or null. */
    private String firstTextByName(Document doc, String localName) {
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) return null;
        return nodes.item(0).getTextContent().trim();
    }

    /** Escapes XML special characters in user-supplied field values. */
    static String escapeXml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;");
    }
}
