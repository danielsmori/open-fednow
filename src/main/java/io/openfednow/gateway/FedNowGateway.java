package io.openfednow.gateway;

import io.openfednow.iso20022.Camt029Message;
import io.openfednow.iso20022.Camt056Message;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs004Message;
import io.openfednow.iso20022.Pacs008Message;
import io.openfednow.processing.cancellation.CancellationService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Layer 1 — API Gateway &amp; Security
 *
 * <p>Primary entry point for all communication with the Federal Reserve's
 * FedNow Service. Handles:
 * <ul>
 *   <li>TLS mutual authentication using Federal Reserve PKI certificates</li>
 *   <li>ISO 20022 message parsing and validation</li>
 *   <li>Rate limiting on inbound and outbound paths</li>
 *   <li>Fraud pre-screening before messages are forwarded to Layer 2</li>
 *   <li>Routing of pacs.008 credit transfers and pacs.002 status reports</li>
 * </ul>
 *
 * <p>All messages must pass validation against the ISO 20022 schema before
 * being forwarded to the Anti-Corruption Layer.
 */
@RestController
@RequestMapping("/fednow")
@Tag(
    name = "FedNow Gateway",
    description = """
        ISO 20022 message routing between the FedNow Service and the core banking system. \
        Handles pacs.008 credit transfers (inbound and outbound) and returns pacs.002 \
        payment status reports. All endpoints require mutual TLS using Federal Reserve \
        PKI certificates in production."""
)
public class FedNowGateway {

    private final MessageRouter messageRouter;
    private final CertificateManager certificateManager;
    private final CancellationService cancellationService;
    private final FedNowClient fedNowClient;

    public FedNowGateway(MessageRouter messageRouter,
                         CertificateManager certificateManager,
                         CancellationService cancellationService,
                         FedNowClient fedNowClient) {
        this.messageRouter = messageRouter;
        this.certificateManager = certificateManager;
        this.cancellationService = cancellationService;
        this.fedNowClient = fedNowClient;
    }

    /**
     * Receives an inbound FI-to-FI credit transfer (pacs.008) from FedNow.
     * Validates the message, runs fraud pre-screening, and routes to the
     * Anti-Corruption Layer for core banking processing.
     *
     * @param message the ISO 20022 pacs.008.001.08 credit transfer message
     * @return pacs.002 payment status report confirming acceptance or rejection
     */
    @PostMapping("/receive")
    @Operation(
        summary = "Receive inbound credit transfer",
        description = """
            Accepts an inbound pacs.008.001.08 FI-to-FI credit transfer from the FedNow \
            Service. Validates the message, verifies the Federal Reserve PKI client \
            certificate, applies fraud pre-screening, and routes to the Anti-Corruption \
            Layer for core banking processing. A pacs.002 status report is returned \
            within FedNow's 20-second response window."""
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Message processed — inspect transactionStatus for ACSC (settled), " +
                          "ACSP (settlement in process), or RJCT (rejected with reason code)",
            content = @Content(mediaType = "application/json",
                               schema = @Schema(implementation = Pacs002Message.class))),
        @ApiResponse(responseCode = "400",
            description = "Malformed or schema-invalid ISO 20022 pacs.008 message"),
        @ApiResponse(responseCode = "401",
            description = "Client certificate absent or not issued by the Federal Reserve PKI"),
        @ApiResponse(responseCode = "500",
            description = "Internal processing error")
    })
    public ResponseEntity<Pacs002Message> receiveTransfer(@Valid @RequestBody Pacs008Message message) {
        certificateManager.validateClientCertificate();
        return messageRouter.routeInbound(message, Rail.FEDNOW);
    }

    /**
     * Initiates an outbound FI-to-FI credit transfer to FedNow.
     * Called by the core banking system (via the ACL) to send a payment.
     *
     * @param message the ISO 20022 pacs.008.001.08 credit transfer message
     * @return pacs.002 payment status report from FedNow
     */
    @PostMapping("/send")
    @Operation(
        summary = "Submit outbound credit transfer",
        description = """
            Submits an outbound pacs.008.001.08 credit transfer to the FedNow Service. \
            Returns the pacs.002 status report from FedNow, or a synthetic RJCT response \
            with reason code NARR if FedNow is unreachable within the configured \
            response-timeout-seconds (default 18 s)."""
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "FedNow response received — inspect transactionStatus for ACSC, ACSP, or RJCT",
            content = @Content(mediaType = "application/json",
                               schema = @Schema(implementation = Pacs002Message.class))),
        @ApiResponse(responseCode = "400",
            description = "Malformed or schema-invalid ISO 20022 pacs.008 message"),
        @ApiResponse(responseCode = "500",
            description = "Internal processing error")
    })
    public ResponseEntity<Pacs002Message> sendTransfer(@Valid @RequestBody Pacs008Message message) {
        return messageRouter.routeOutbound(message);
    }

    /**
     * Receives an inbound camt.056 payment cancellation request from FedNow and
     * returns a camt.029 resolution describing the outcome.
     *
     * <p>The framework's response is always synchronous: the camt.029 returned in
     * the HTTP response body is the same message FedNow expects. Decision logic
     * lives in {@link CancellationService} — see ADR-0007 for the full state-to-
     * response matrix.
     */
    @PostMapping("/cancellation")
    @Operation(
        summary = "Receive inbound cancellation request (camt.056)",
        description = """
            Accepts an inbound camt.056 cancellation request for a payment we previously \
            received. Returns the corresponding camt.029 resolution: \
            CNCL if the payment is still cancellable and was successfully reversed; \
            RJCR/ARDT if the payment already settled and must be returned via pacs.004; \
            RJCR/NOOR if no matching transaction is on record; \
            PDCR if the cancellation outcome cannot yet be determined (core call in flight)."""
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Cancellation outcome — inspect resolutionStatus for CNCL, RJCR, or PDCR",
            content = @Content(mediaType = "application/json",
                               schema = @Schema(implementation = Camt029Message.class))),
        @ApiResponse(responseCode = "400",
            description = "Malformed or schema-invalid camt.056 message")
    })
    public ResponseEntity<Camt029Message> receiveCancellation(@Valid @RequestBody Camt056Message request) {
        certificateManager.validateClientCertificate();
        return ResponseEntity.ok(cancellationService.handleCancellationRequest(request));
    }

    /**
     * Submits an outbound payment return (pacs.004) to FedNow.
     *
     * <p>Used by operations tooling and by the saga-compensation path when a
     * provisionally-accepted payment must be returned to the sender's institution
     * — typically after the core banking system rejects a transaction that was
     * already ACSP-accepted during a maintenance window. The FedNow response
     * (pacs.002) is returned to the caller so they can confirm acceptance of the
     * return itself.
     *
     * @param message the ISO 20022 pacs.004.001.09 payment return
     * @return pacs.002 status report from FedNow acknowledging the return
     */
    @PostMapping("/return")
    @Operation(
        summary = "Submit outbound payment return (pacs.004)",
        description = """
            Submits an outbound pacs.004.001.09 payment return to the FedNow Service. \
            Returns the pacs.002 status report from FedNow. Idempotency is enforced at \
            the FedNow side via the return's returnId. The same retry and JWS-signing \
            behavior as /fednow/send applies."""
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "FedNow response received — inspect transactionStatus for ACSC, ACSP, or RJCT",
            content = @Content(mediaType = "application/json",
                               schema = @Schema(implementation = Pacs002Message.class))),
        @ApiResponse(responseCode = "400",
            description = "Malformed or schema-invalid ISO 20022 pacs.004 message"),
        @ApiResponse(responseCode = "500",
            description = "Internal processing error")
    })
    public ResponseEntity<Pacs002Message> submitReturn(@Valid @RequestBody Pacs004Message message) {
        return ResponseEntity.ok(fedNowClient.submitReturn(message));
    }

    /**
     * Health check endpoint for FedNow connectivity monitoring.
     * Returns the current gateway status and certificate validity.
     */
    @GetMapping("/health")
    @Operation(
        summary = "Gateway health check",
        description = "Returns the operational status of the OpenFedNow Gateway. " +
                      "For detailed per-layer health (Redis, RabbitMQ, PostgreSQL, core adapter), " +
                      "see the Spring Actuator endpoint at /actuator/health."
    )
    @ApiResponse(responseCode = "200", description = "Gateway is operational",
        content = @Content(mediaType = "text/plain",
                           schema = @Schema(type = "string", example = "OpenFedNow Gateway — operational")))
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OpenFedNow Gateway — operational");
    }
}
