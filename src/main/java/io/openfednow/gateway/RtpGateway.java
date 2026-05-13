package io.openfednow.gateway;

import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Layer 1 — API Gateway &amp; Security (RTP rail) — reference mode.
 *
 * <p><strong>Reference mode.</strong> Inbound RTP XML parsing and shared pipeline routing
 * are implemented. The following remain pending institutional onboarding with
 * The Clearing House (TCH):
 * <ul>
 *   <li>TCH certificate validation — requires TCH participation credentials
 *       (external access dependency; see rtp-compatibility.md)</li>
 *   <li>Live TCH dedicated network transport — RTP uses a private network, not public TLS</li>
 *   <li>RTP outbound XML serialization and response envelope handling</li>
 *   <li>Live RTP certification and settlement validation</li>
 * </ul>
 *
 * <p>The Clearing House's RTP® network and the Federal Reserve's FedNow Service both
 * use ISO 20022 as their message standard. Layers 2–4 of this framework are intentionally
 * rail-agnostic: they operate on parsed {@link Pacs008Message} objects and have no
 * knowledge of which rail delivered the message.
 *
 * <p>Inbound path (reference mode): ISO 20022 XML is parsed by {@link RtpXmlParser} into
 * a {@link Pacs008Message}, then routed through the same {@link MessageRouter} that handles
 * FedNow messages — no changes to Layers 2–4 are required for RTP support.
 *
 * <p>See
 * <a href="../../../../../../../../docs/adr/0005-dual-rail-architecture-fednow-rtp.md">ADR-0005</a>
 * and
 * <a href="../../../../../../../../docs/rtp-compatibility.md">rtp-compatibility.md</a>
 * for the design rationale and full status.
 */
@RestController
@RequestMapping("/rtp")
@Tag(
    name = "RTP Gateway (Reference Mode)",
    description = """
        Reference-mode RTP® gateway. Inbound XML parsing and shared Layers 2–4 pipeline \
        routing are implemented. Live TCH connectivity, certificate validation, outbound \
        RTP XML serialization, and RTP network certification remain subject to institutional \
        onboarding with The Clearing House. \
        See docs/rtp-compatibility.md and ADR-0005."""
)
public class RtpGateway {

    private final MessageRouter messageRouter;
    private final CertificateManager certificateManager;
    private final RtpXmlParser rtpXmlParser;

    public RtpGateway(MessageRouter messageRouter, CertificateManager certificateManager,
                      RtpXmlParser rtpXmlParser) {
        this.messageRouter = messageRouter;
        this.certificateManager = certificateManager;
        this.rtpXmlParser = rtpXmlParser;
    }

    /**
     * Accepts an inbound RTP pacs.008 message in reference mode.
     *
     * <p>Accepts:
     * <ul>
     *   <li>{@code application/xml} — canonical ISO 20022 XML envelope (RTP production format),
     *       parsed by {@link RtpXmlParser} into the internal domain model</li>
     *   <li>{@code application/json} — JSON pacs.008 (for sandbox / compatibility testing)</li>
     * </ul>
     *
     * <p>After parsing, the {@link Pacs008Message} is routed through the same
     * {@link MessageRouter} used by the FedNow gateway — Layers 2–4 are rail-agnostic.
     *
     * <p><strong>Reference mode:</strong> TCH certificate validation is not yet implemented.
     * This is an external access dependency — TCH digital certificates require institutional
     * onboarding, not a technical unknown. The validation architecture is defined in
     * rtp-compatibility.md and follows the same pattern as Federal Reserve PKI in
     * {@link FedNowGateway}.
     */
    @PostMapping(value = "/receive",
                 consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.APPLICATION_JSON_VALUE})
    @Operation(
        summary = "Receive inbound credit transfer (RTP) — reference mode",
        description = """
            Accepts an inbound pacs.008.001.08 FI-to-FI credit transfer. \
            Accepts application/xml (canonical ISO 20022 envelope, parsed by RtpXmlParser) or \
            application/json (for sandbox/compatibility testing). \
            Routing through Layers 2–4 is fully implemented and rail-agnostic. \
            TCH PKI certificate validation requires TCH participation credentials \
            (external access dependency — see rtp-compatibility.md)."""
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
    public ResponseEntity<Pacs002Message> receiveTransfer(
            @RequestBody String rawBody,
            @RequestHeader(value = "Content-Type", defaultValue = "application/json") String contentType) {
        // TCH certificate validation: external access dependency, not a technical unknown.
        // TCH digital certificates and network access keys are issued exclusively through
        // The Clearing House institutional onboarding process — the same class of credential
        // dependency as Federal Reserve PKI for live FedNow deployment (the sandbox is used
        // for FedNow testing for the same reason). The validation logic follows
        // CertificateManager's existing Federal Reserve PKI pattern; the architecture is
        // defined in docs/rtp-compatibility.md and ADR-0005.
        // certificateManager.validateTchClientCertificate(request);

        Pacs008Message message;
        if (contentType.contains(MediaType.APPLICATION_XML_VALUE)) {
            // RTP production path: parse canonical ISO 20022 XML envelope
            message = rtpXmlParser.parse(rawBody);
        } else {
            // Sandbox/compatibility path: JSON pacs.008 (same model as FedNow)
            try {
                message = new com.fasterxml.jackson.databind.ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                        .readValue(rawBody, Pacs008Message.class);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return ResponseEntity.badRequest().build();
            }
        }

        // From this point the processing is identical to FedNowGateway — rail-agnostic:
        return messageRouter.routeInbound(message);
    }

    /**
     * RTP outbound credit transfer — pending RTP-specific Layer 1 implementation.
     *
     * <p>Returns HTTP 501. RTP outbound XML serialization to the canonical ISO 20022 envelope
     * and TCH dedicated network transport are not yet implemented. The shared outbound
     * processing pipeline in Layers 2–4 is fully implemented and rail-agnostic; only the
     * RTP-specific serialization and TCH transport at Layer 1 remain pending.
     */
    @PostMapping("/send")
    @Operation(
        summary = "Submit outbound credit transfer (RTP) — pending",
        description = """
            Not yet implemented. Returns HTTP 501. \
            RTP outbound XML serialization to the canonical ISO 20022 envelope and TCH \
            dedicated network transport remain pending institutional onboarding with \
            The Clearing House. \
            The shared outbound processing pipeline in Layers 2–4 is fully implemented \
            and rail-agnostic."""
    )
    @ApiResponses({
        @ApiResponse(responseCode = "501",
            description = "Not yet implemented — RTP outbound serialization and TCH transport pending"),
        @ApiResponse(responseCode = "400",
            description = "Malformed or schema-invalid ISO 20022 pacs.008 message")
    })
    public ResponseEntity<Pacs002Message> sendTransfer(@RequestBody Pacs008Message message) {
        // RTP outbound serialization to canonical XML envelope and TCH network transport
        // are not yet implemented. Returns 501 until complete.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    /**
     * Health check for the RTP gateway.
     */
    @GetMapping("/health")
    @Operation(
        summary = "RTP gateway health check",
        description = """
            Returns the current status of the RTP gateway. \
            Inbound XML parsing is enabled; TCH network connectivity and certificate \
            validation are not configured. \
            See /fednow/health for the operational FedNow gateway status."""
    )
    @ApiResponse(responseCode = "200", description = "RTP gateway is reachable",
        content = @Content(mediaType = "text/plain",
                           schema = @Schema(type = "string",
                               example = "OpenFedNow RTP Gateway — reference mode: XML parsing enabled; TCH connectivity not configured")))
    public ResponseEntity<String> health() {
        return ResponseEntity.ok(
                "OpenFedNow RTP Gateway — reference mode: XML parsing enabled; TCH connectivity not configured");
    }
}
