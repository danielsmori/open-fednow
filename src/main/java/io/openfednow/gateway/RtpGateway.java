package io.openfednow.gateway;

import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
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
 * Layer 1 — API Gateway &amp; Security (RTP rail)
 *
 * <p><strong>Stub — not implemented.</strong> This class documents the architectural intent
 * for RTP® network connectivity. See
 * <a href="../../../../../../../../docs/adr/0005-dual-rail-architecture-fednow-rtp.md">ADR-0005</a>
 * and
 * <a href="../../../../../../../../docs/rtp-compatibility.md">rtp-compatibility.md</a>
 * for the design rationale.
 *
 * <p>The Clearing House's RTP® network and the Federal Reserve's FedNow Service both
 * use ISO 20022 as their message standard. Layers 2–4 of this framework are intentionally
 * rail-agnostic: they operate on parsed {@link Pacs008Message} objects and have no
 * knowledge of which rail delivered the message.
 *
 * <p>This gateway would handle RTP-specific Layer 1 responsibilities:
 * <ul>
 *   <li>TLS mutual authentication using TCH (The Clearing House) certificates</li>
 *   <li>ISO 20022 XML envelope parsing (RTP uses canonical XML; FedNow uses JSON)</li>
 *   <li>Rate limiting on inbound and outbound paths per TCH participation rules</li>
 *   <li>Fraud pre-screening before forwarding to Layer 2</li>
 * </ul>
 *
 * <p>Once parsed, the resulting {@link Pacs008Message} is routed through the same
 * {@link MessageRouter} that handles FedNow messages — no changes to Layers 2–4 required.
 *
 * <p>What is not implemented:
 * <ul>
 *   <li>TCH certificate validation — requires TCH participation credentials</li>
 *   <li>RTP XML envelope parsing — the canonical ISO 20022 XML format differs from
 *       FedNow's JSON envelope</li>
 *   <li>TCH dedicated network connection — RTP uses a private network, not public TLS</li>
 *   <li>{@link MessageRouter} source-rail tracking — the router must record inbound
 *       source to dispatch {@link Pacs002Message} responses back to the correct rail</li>
 * </ul>
 */
@RestController
@RequestMapping("/rtp")
@Tag(
    name = "RTP Gateway (Stub)",
    description = """
        Stub — not implemented. Documents the architectural intent for RTP® network \
        connectivity via The Clearing House (TCH). Both FedNow and RTP use ISO 20022 \
        message types (pacs.008, pacs.002); only the transport envelope and certificate \
        authority differ. Layers 2–4 are rail-agnostic and require no changes for RTP \
        support. See docs/rtp-compatibility.md and ADR-0005 for details."""
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
     * Receives an inbound FI-to-FI credit transfer (pacs.008) from the RTP network.
     *
     * <p><strong>Stub — not implemented.</strong> A complete implementation would:
     * <ol>
     *   <li>Validate the TCH client certificate via {@link CertificateManager} (TCH PKI,
     *       not Federal Reserve PKI)</li>
     *   <li>Parse the RTP ISO 20022 XML envelope into a {@link Pacs008Message} (RTP uses
     *       canonical XML; FedNow uses a JSON wrapper)</li>
     *   <li>Route to {@link MessageRouter#routeInbound(Pacs008Message)} — identical to
     *       the FedNow path from this point forward</li>
     *   <li>Return the {@link Pacs002Message} response in RTP's canonical XML envelope</li>
     * </ol>
     *
     * @param message the ISO 20022 pacs.008.001.08 credit transfer message
     * @return pacs.002 payment status report confirming acceptance or rejection
     */
    /**
     * Accepts an inbound RTP pacs.008 message as either:
     * <ul>
     *   <li>{@code application/xml} — canonical ISO 20022 XML envelope (RTP production format),
     *       parsed by {@link RtpXmlParser} into the internal domain model</li>
     *   <li>{@code application/json} — JSON pacs.008 (for compatibility testing / sandbox use)</li>
     * </ul>
     *
     * <p>After parsing, the {@link Pacs008Message} is routed through the same
     * {@link MessageRouter} used by the FedNow gateway — Layers 2–4 are rail-agnostic.
     *
     * <p><strong>Reference mode:</strong> TCH certificate validation is not yet implemented
     * (requires TCH participation credentials). The XML parser handles the message structure
     * but does not validate against the full ISO 20022 XSD schema.
     */
    @PostMapping(value = "/receive",
                 consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.APPLICATION_JSON_VALUE})
    @Operation(
        summary = "Receive inbound credit transfer (RTP) — reference mode",
        description = """
            Accepts an inbound pacs.008.001.08 FI-to-FI credit transfer from the RTP® network. \
            Accepts application/xml (canonical ISO 20022 envelope, parsed by RtpXmlParser) or \
            application/json (for sandbox/compatibility testing). \
            TCH PKI certificate validation is not yet implemented — requires TCH participation \
            credentials. Layers 2–4 are rail-agnostic: no changes downstream of this gateway \
            are required for RTP support."""
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
        // TODO: validate TCH client certificate
        //   certificateManager.validateTchClientCertificate();

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
     * Initiates an outbound FI-to-FI credit transfer to the RTP network.
     *
     * <p><strong>Stub — not implemented.</strong> A complete implementation would
     * format the {@link Pacs008Message} into the RTP canonical XML envelope and
     * submit it to the TCH network connection.
     *
     * @param message the ISO 20022 pacs.008.001.08 credit transfer message
     * @return pacs.002 payment status report from RTP
     */
    @PostMapping("/send")
    @Operation(
        summary = "Submit outbound credit transfer (RTP) — stub",
        description = """
            Stub — not implemented. Would submit a pacs.008.001.08 credit transfer to the \
            RTP® network via The Clearing House's dedicated network connection. The inbound \
            routing path (Layers 2–4) is fully rail-agnostic; only the outbound serialization \
            to RTP XML and the TCH network transport are RTP-specific."""
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "RTP network response received — inspect transactionStatus",
            content = @Content(mediaType = "application/json",
                               schema = @Schema(implementation = Pacs002Message.class))),
        @ApiResponse(responseCode = "400",
            description = "Malformed or schema-invalid ISO 20022 pacs.008 message"),
        @ApiResponse(responseCode = "501",
            description = "Not implemented — RTP connectivity is a documented stub")
    })
    public ResponseEntity<Pacs002Message> sendTransfer(@RequestBody Pacs008Message message) {
        // TODO: serialize Pacs008Message to RTP canonical XML envelope
        // TODO: submit to TCH dedicated network connection
        return messageRouter.routeOutbound(message);
    }

    /**
     * Health check endpoint for RTP connectivity monitoring.
     */
    @GetMapping("/health")
    @Operation(
        summary = "RTP gateway health check (stub)",
        description = """
            Returns the stub status of the RTP gateway. In a full implementation, this \
            would also report TCH network connectivity and certificate validity. \
            See /fednow/health for the operational FedNow gateway status."""
    )
    @ApiResponse(responseCode = "200", description = "RTP gateway stub is reachable",
        content = @Content(mediaType = "text/plain",
                           schema = @Schema(type = "string",
                               example = "OpenFedNow RTP Gateway — stub (not connected to TCH network)")))
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OpenFedNow RTP Gateway — stub (not connected to TCH network)");
    }
}
