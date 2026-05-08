package io.openfednow.gateway;

import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    public RtpGateway(MessageRouter messageRouter, CertificateManager certificateManager) {
        this.messageRouter = messageRouter;
        this.certificateManager = certificateManager;
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
    @PostMapping("/receive")
    @Operation(
        summary = "Receive inbound credit transfer (RTP) — stub",
        description = """
            Stub — not implemented. Accepts an inbound pacs.008.001.08 FI-to-FI credit \
            transfer from the RTP® network. Would validate the TCH PKI client certificate, \
            parse the RTP ISO 20022 XML envelope, and route to the same MessageRouter \
            used by the FedNow gateway. Layers 2–4 are rail-agnostic: no changes downstream \
            of this gateway are required for RTP support."""
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Message processed — inspect transactionStatus for ACSC, ACSP, or RJCT",
            content = @Content(mediaType = "application/json",
                               schema = @Schema(implementation = Pacs002Message.class))),
        @ApiResponse(responseCode = "400",
            description = "Malformed or schema-invalid ISO 20022 pacs.008 message"),
        @ApiResponse(responseCode = "401",
            description = "Client certificate absent or not issued by TCH PKI"),
        @ApiResponse(responseCode = "501",
            description = "Not implemented — RTP connectivity is a documented stub")
    })
    public ResponseEntity<Pacs002Message> receiveTransfer(@RequestBody Pacs008Message message) {
        // TODO: validate TCH client certificate
        //   certificateManager.validateTchClientCertificate();

        // TODO: parse RTP XML envelope into Pacs008Message
        //   (RTP uses canonical ISO 20022 XML; this endpoint currently receives the same
        //    JSON model as FedNow for stub purposes)

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
