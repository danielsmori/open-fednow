package io.openfednow.gateway;

import io.openfednow.iso20022.Camt029Message;
import io.openfednow.iso20022.Camt056Message;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import io.openfednow.processing.cancellation.CancellationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Layer 1 — API Gateway &amp; Security (RTP® rail)
 *
 * <p>Handles the full inbound and outbound payment lifecycle for The Clearing
 * House RTP® network. Layer 1 is the only component that varies between rails;
 * Layers 2–4 are rail-agnostic and operate on parsed ISO 20022 domain objects
 * regardless of whether a message arrived via FedNow or RTP.
 *
 * <h2>Inbound path ({@code /rtp/receive})</h2>
 * <p>Accepts an ISO 20022 pacs.008.001.08 credit transfer in either XML
 * ({@code application/xml}, canonical RTP envelope) or JSON
 * ({@code application/json}, for sandbox/simulator testing). After parsing,
 * the message is routed through the shared {@link MessageRouter} that also
 * handles FedNow messages. When the inbound format was XML, the pacs.002
 * status report is serialized back to XML via {@link RtpXmlSerializer}.
 *
 * <h2>Outbound path ({@code /rtp/send})</h2>
 * <p>Accepts an outbound pacs.008 to initiate a credit transfer via the RTP®
 * network. The message is serialized to XML by {@link RtpXmlSerializer} and
 * submitted to the TCH endpoint via {@link RtpClient}. In local development,
 * {@link SandboxRtpClient} returns synthetic responses; when {@code RTP_ENDPOINT}
 * is set, {@link HttpRtpClient} transmits to a configured TCH endpoint.
 *
 * <h2>Certificate validation</h2>
 * <p>Inbound TCH certificate validation is handled by
 * {@link CertificateManager#validateTchClientCertificate()}. In dev/sandbox mode
 * (no {@code TCH_TRUSTSTORE_PATH} configured), validation is skipped. In
 * production, TCH PKI certificates and private-network transport are obtained
 * through TCH institutional onboarding — the same class of credential dependency
 * as Federal Reserve PKI for the FedNow rail.
 *
 * @see FedNowGateway
 * @see RtpXmlParser
 * @see RtpXmlSerializer
 * @see <a href="../../../../../../../../docs/rtp-compatibility.md">rtp-compatibility.md</a>
 * @see <a href="../../../../../../../../docs/adr/0005-dual-rail-architecture-fednow-rtp.md">ADR-0005</a>
 */
@RestController
@RequestMapping("/rtp")
@Tag(
    name = "RTP Gateway",
    description = """
        RTP® gateway — full Layer 1 implementation symmetric with the FedNow gateway. \
        Inbound: accepts application/xml (canonical ISO 20022 envelope via RtpXmlParser) \
        or application/json (sandbox/simulator); routes through the shared Layers 2–4 pipeline; \
        returns pacs.002 in the same format as the request. \
        Outbound: serializes pacs.008 to XML and submits via RtpClient \
        (SandboxRtpClient by default; HttpRtpClient when RTP_ENDPOINT is set). \
        TCH PKI certificates and private-network transport require TCH institutional onboarding. \
        See docs/rtp-compatibility.md and ADR-0005."""
)
public class RtpGateway {

    private final MessageRouter messageRouter;
    private final CertificateManager certificateManager;
    private final RtpXmlParser rtpXmlParser;
    private final RtpXmlSerializer rtpXmlSerializer;
    private final RtpClient rtpClient;
    private final CancellationService cancellationService;

    public RtpGateway(MessageRouter messageRouter, CertificateManager certificateManager,
                      RtpXmlParser rtpXmlParser, RtpXmlSerializer rtpXmlSerializer,
                      RtpClient rtpClient, CancellationService cancellationService) {
        this.messageRouter = messageRouter;
        this.certificateManager = certificateManager;
        this.rtpXmlParser = rtpXmlParser;
        this.rtpXmlSerializer = rtpXmlSerializer;
        this.rtpClient = rtpClient;
        this.cancellationService = cancellationService;
    }

    /**
     * Accepts an inbound RTP pacs.008 credit transfer and returns a pacs.002 status report.
     *
     * <p>Accepts:
     * <ul>
     *   <li>{@code application/xml} — canonical ISO 20022 XML envelope (RTP production format);
     *       response is also returned as XML ({@code application/xml})</li>
     *   <li>{@code application/json} — JSON pacs.008 (for sandbox / compatibility testing);
     *       response is returned as JSON</li>
     * </ul>
     *
     * <p>After parsing, the {@link Pacs008Message} is routed through the same
     * {@link MessageRouter} used by the FedNow gateway — Layers 2–4 are rail-agnostic.
     * TCH certificate validation is applied via
     * {@link CertificateManager#validateTchClientCertificate()} (no-op in sandbox mode).
     */
    @PostMapping(value = "/receive",
                 consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.APPLICATION_JSON_VALUE})
    @Operation(
        summary = "Receive inbound credit transfer (RTP)",
        description = """
            Accepts an inbound pacs.008.001.08 FI-to-FI credit transfer. \
            Accepts application/xml (canonical ISO 20022 XML envelope, parsed by RtpXmlParser \
            and responded to with pacs.002 XML) or application/json (sandbox/compatibility). \
            TCH PKI certificate validation is applied; in sandbox mode (no TCH_TRUSTSTORE_PATH) \
            this is a no-op, matching the FedNow gateway's behavior when FED_TRUSTSTORE_PATH is absent."""
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Message processed — inspect transactionStatus for ACSC, ACSP, or RJCT",
            content = @Content(mediaType = "application/json",
                               schema = @Schema(implementation = Pacs002Message.class))),
        @ApiResponse(responseCode = "400",
            description = "Malformed or schema-invalid ISO 20022 pacs.008 XML/JSON"),
        @ApiResponse(responseCode = "401",
            description = "Client certificate absent or not issued by TCH PKI")
    })
    public ResponseEntity<?> receiveTransfer(
            @RequestBody String rawBody,
            @RequestHeader(value = "Content-Type", defaultValue = "application/json") String contentType) {

        certificateManager.validateTchClientCertificate();

        Pacs008Message message;
        boolean isXml = contentType.contains(MediaType.APPLICATION_XML_VALUE);

        if (isXml) {
            message = rtpXmlParser.parse(rawBody);
        } else {
            try {
                message = new com.fasterxml.jackson.databind.ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                        .readValue(rawBody, Pacs008Message.class);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return ResponseEntity.badRequest().build();
            }
        }

        ResponseEntity<Pacs002Message> routerResponse = messageRouter.routeInbound(message, Rail.RTP);

        // When the inbound message was XML (production RTP format), return the
        // pacs.002 status report as canonical ISO 20022 XML. For sandbox/JSON
        // requests, return JSON — the same format used by the FedNow gateway.
        if (isXml && routerResponse.getBody() != null) {
            String responseXml = rtpXmlSerializer.serializePacs002(routerResponse.getBody());
            return ResponseEntity.status(routerResponse.getStatusCode())
                    .contentType(MediaType.APPLICATION_XML)
                    .body(responseXml);
        }

        return routerResponse;
    }

    /**
     * Submits an outbound credit transfer to the RTP® network.
     *
     * <p>Serializes the pacs.008 to canonical ISO 20022 XML via {@link RtpXmlSerializer}
     * and submits it to the TCH endpoint via {@link RtpClient}. In local development,
     * {@link SandboxRtpClient} returns deterministic synthetic responses. When
     * {@code RTP_ENDPOINT} is configured, {@link HttpRtpClient} is used to transmit
     * to a live or simulator TCH endpoint.
     */
    @PostMapping("/send")
    @Operation(
        summary = "Submit outbound credit transfer (RTP)",
        description = """
            Submits an outbound pacs.008.001.08 to the RTP® network via RtpClient. \
            SandboxRtpClient returns synthetic responses in local development; \
            HttpRtpClient (activated by RTP_ENDPOINT) transmits to a configured TCH endpoint \
            using canonical ISO 20022 XML serialization. \
            Live TCH connectivity requires institutional participation, \
            TCH PKI certificates, and private-network transport."""
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Payment status report from RTP® network",
            content = @Content(mediaType = "application/json",
                               schema = @Schema(implementation = Pacs002Message.class))),
        @ApiResponse(responseCode = "400",
            description = "Malformed or schema-invalid ISO 20022 pacs.008 message")
    })
    public ResponseEntity<Pacs002Message> sendTransfer(@RequestBody Pacs008Message message) {
        certificateManager.validateTchClientCertificate();
        Pacs002Message result = rtpClient.submitCreditTransfer(message);
        return ResponseEntity.ok(result);
    }

    /**
     * Receives an inbound camt.056 cancellation request via the RTP rail and
     * returns a camt.029 resolution.
     *
     * <p>Rail-agnostic — the same {@link CancellationService} handles RTP and
     * FedNow cancellations because the saga lookup and lifecycle decisions are
     * identical. See ADR-0007 for the decision matrix.
     */
    @PostMapping("/cancellation")
    @Operation(
        summary = "Receive inbound cancellation request (camt.056) — RTP rail",
        description = """
            Accepts an inbound camt.056 cancellation request received via the RTP \
            network and returns the corresponding camt.029 resolution. \
            Decision logic and outcomes match the FedNow rail — see ADR-0007."""
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Cancellation outcome",
            content = @Content(mediaType = "application/json",
                               schema = @Schema(implementation = Camt029Message.class))),
        @ApiResponse(responseCode = "401",
            description = "Client certificate absent or not issued by TCH PKI")
    })
    public ResponseEntity<Camt029Message> receiveCancellation(@RequestBody Camt056Message request) {
        certificateManager.validateTchClientCertificate();
        return ResponseEntity.ok(cancellationService.handleCancellationRequest(request));
    }

    /**
     * Health check for the RTP gateway.
     */
    @GetMapping("/health")
    @Operation(
        summary = "RTP gateway health check",
        description = """
            Returns the current operational status of the RTP gateway. \
            Both inbound XML parsing (RtpXmlParser) and outbound XML serialization \
            (RtpXmlSerializer) are active. Live TCH connectivity is activated by \
            setting RTP_ENDPOINT; in its absence, SandboxRtpClient is active. \
            TCH PKI certificate validation requires TCH_TRUSTSTORE_PATH."""
    )
    @ApiResponse(responseCode = "200", description = "RTP gateway is reachable",
        content = @Content(mediaType = "text/plain",
                           schema = @Schema(type = "string",
                               example = "OpenFedNow RTP Gateway — XML parsing and serialization active; set RTP_ENDPOINT for live TCH connectivity")))
    public ResponseEntity<String> health() {
        boolean liveMode = rtpClient instanceof HttpRtpClient;
        String connectivity = liveMode
                ? "live TCH connectivity active (RTP_ENDPOINT configured)"
                : "sandbox mode active (set RTP_ENDPOINT for live TCH transport)";
        return ResponseEntity.ok(
                "OpenFedNow RTP Gateway — XML parsing and serialization active; " + connectivity);
    }
}
