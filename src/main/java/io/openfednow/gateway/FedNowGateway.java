package io.openfednow.gateway;

import io.openfednow.iso20022.Camt029Message;
import io.openfednow.iso20022.Camt056Message;
import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs004Message;
import io.openfednow.iso20022.Pacs008Message;
import io.openfednow.processing.cancellation.CancellationService;
import io.openfednow.reliability.PaymentView;
import io.openfednow.reliability.ReliablePaymentService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;

/**
 * Layer 1 — API Gateway &amp; Security
 *
 * <p>Reference gateway for synthetic FedNow-shaped messages. Live access and
 * protocol mapping are not established. It handles:
 * <ul>
 *   <li>Optional certificate-validation hook (skipped without configured trust material)</li>
 *   <li>Validation of selected ISO 20022-shaped model fields</li>
 *   <li>Rate limiting on inbound and outbound paths</li>
 *   <li>Fraud pre-screening before messages are forwarded to Layer 2</li>
 *   <li>Routing of pacs.008 credit transfers and pacs.002 status reports</li>
 * </ul>
 *
 * <p>These local checks do not establish a complete live ISO 20022 profile.
 */
@RestController
@RequestMapping("/fednow")
@Tag(
    name = "FedNow Gateway",
    description = """
        Synthetic ISO 20022-shaped routing between local fixtures and core adapters. \
        Handles pacs.008 credit transfers (inbound and outbound) and returns pacs.002 \
        payment status reports. This controller does not establish live rail access."""
)
public class FedNowGateway {

    private final MessageRouter messageRouter;
    private final CertificateManager certificateManager;
    private final CancellationService cancellationService;
    private final FedNowClient fedNowClient;
    private final ReliablePaymentService reliablePayments;
    private final Environment environment;

    @Value("${openfednow.reliability.legacy-entry-bridge-enabled:false}")
    private boolean reliabilityBridgeEnabled;

    @Value("${openfednow.legacy-outbound-sandbox-enabled:false}")
    private boolean legacyOutboundSandboxEnabled;

    @Value("${openfednow.legacy-return-sandbox-enabled:false}")
    private boolean legacyReturnSandboxEnabled;

    @Autowired
    public FedNowGateway(MessageRouter messageRouter,
                         CertificateManager certificateManager,
                         CancellationService cancellationService,
                         FedNowClient fedNowClient,
                         ReliablePaymentService reliablePayments,
                         Environment environment) {
        this.messageRouter = messageRouter;
        this.certificateManager = certificateManager;
        this.cancellationService = cancellationService;
        this.fedNowClient = fedNowClient;
        this.reliablePayments = reliablePayments;
        this.environment = environment;
    }

    /** Keeps standalone legacy controller tests compatible; Spring uses the injected constructor. */
    FedNowGateway(MessageRouter messageRouter, CertificateManager certificateManager,
                  CancellationService cancellationService, FedNowClient fedNowClient) {
        this(messageRouter, certificateManager, cancellationService, fedNowClient, null, null);
    }

    /**
     * Receives a synthetic inbound FI-to-FI credit transfer (pacs.008-shaped).
     * Validates the message, runs configured pre-screening, and routes to the
     * reference core-adapter path.
     *
     * @param message the ISO 20022 pacs.008.001.08 credit transfer message
     * @return pacs.002 payment status report confirming acceptance or rejection
     */
    @PostMapping("/receive")
    @Operation(
        summary = "Receive inbound credit transfer",
        description = """
            Accepts a pacs.008-shaped JSON fixture, applies configured certificate \
            hook and screening checks, and routes to a core-shaped adapter. Returns a \
            local pacs.002-shaped status. This route has no verified live FedNow \
            message profile, status authority or response-time guarantee."""
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Synthetic result — inspect transactionStatus; it is not live settlement evidence",
            content = @Content(mediaType = "application/json",
                               schema = @Schema(implementation = Pacs002Message.class))),
        @ApiResponse(responseCode = "400",
            description = "Malformed or schema-invalid ISO 20022 pacs.008 message"),
        @ApiResponse(responseCode = "500",
            description = "Internal processing error")
    })
    public ResponseEntity<Pacs002Message> receiveTransfer(@Valid @RequestBody Pacs008Message message) {
        certificateManager.validateClientCertificate();
        return messageRouter.routeInbound(message, Rail.FEDNOW);
    }

    /**
     * Initiates a synthetic outbound evaluation payment only when an explicit
     * non-production bridge or legacy sandbox flag is enabled.
     *
     * @param message the ISO 20022 pacs.008.001.08 credit transfer message
     * @return a reliability PaymentView on the bridge, or a legacy sandbox pacs.002-shaped response
     */
    @PostMapping("/send")
    @Operation(
        summary = "Submit outbound credit transfer",
        description = """
            Disabled by default. In an explicitly enabled non-production reliability \
            bridge, delegates to the synthetic SQL service and returns PaymentView \
            (200 final, 202 pending). A separate legacy sandbox flag permits a \
            pacs.002-shaped demonstration only with SandboxFedNowClient. Neither \
            path establishes a live FedNow outcome."""
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Final synthetic PaymentView on the reliability bridge, or legacy sandbox status",
            content = @Content(mediaType = "application/json",
                               schema = @Schema(oneOf = {PaymentView.class, Pacs002Message.class}))),
        @ApiResponse(responseCode = "202",
            description = "Synthetic reliability operation remains pending or under investigation",
            content = @Content(mediaType = "application/json",
                               schema = @Schema(implementation = PaymentView.class))),
        @ApiResponse(responseCode = "400",
            description = "Malformed or schema-invalid ISO 20022 pacs.008 message"),
        @ApiResponse(responseCode = "503",
            description = "Outbound route disabled in this profile or legacy sandbox outcome unavailable"),
        @ApiResponse(responseCode = "500",
            description = "Internal processing error")
    })
    public ResponseEntity<?> sendTransfer(@Valid @RequestBody Pacs008Message message) {
        // Controlled synthetic bridge: the original entry point delegates to
        // the same SQL ownership, reservation, intent and inquiry service.
        // Never enable this demonstration in the production profile.
        if (reliabilityBridgeEnabled) {
            if (reliablePayments == null || environment == null ||
                    java.util.Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
                return ResponseEntity.status(503).build();
            }
            PaymentView view = reliablePayments.submit(message);
            boolean pending = !"SETTLED".equals(view.state()) && !"REJECTED".equals(view.state());
            return ResponseEntity.status(pending ? 202 : 200).body(view);
        }
        // The legacy route has no atomic cross-store claim or authoritative
        // core reservation. Keep it available only as an explicit sandbox demo.
        if (!legacyOutboundSandboxEnabled || !(fedNowClient instanceof SandboxFedNowClient)) {
            return ResponseEntity.status(503).build();
        }
        return messageRouter.routeOutbound(message);
    }

    /**
     * Receives a synthetic inbound camt.056 cancellation request and returns a
     * local camt.029-shaped resolution.
     *
     * <p>The framework's response is always synchronous: the camt.029 returned in
     * the HTTP response body is a reference fixture. Decision logic
     * lives in {@link CancellationService} — see ADR-0007 for the full state-to-
     * response matrix.
     */
    @PostMapping("/cancellation")
    @Operation(
        summary = "Receive inbound cancellation request (camt.056)",
        description = """
            Accepts a synthetic inbound camt.056-shaped cancellation for a payment previously \
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
     * Demonstrates an outbound pacs.004-shaped return only in the sandbox.
     *
     * <p>Used by operations tooling and by the saga-compensation path when a
     * provisionally-accepted payment has a later local core rejection. This
     * path is disabled by default and has no durable return outcome lifecycle.
     *
     * @param message the ISO 20022 pacs.004.001.09 payment return
     * @return a synthetic pacs.002-shaped result when the sandbox flag is enabled
     */
    @PostMapping("/return")
    @Operation(
        summary = "Submit outbound payment return (pacs.004)",
        description = """
            Disabled by default. The synthetic sandbox can demonstrate a pacs.004-shaped \
            response, but no durable return operation or rail-side deduplication is established."""
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Synthetic sandbox response; not a verified FedNow return outcome",
            content = @Content(mediaType = "application/json",
                               schema = @Schema(implementation = Pacs002Message.class))),
        @ApiResponse(responseCode = "400",
            description = "Malformed or schema-invalid ISO 20022 pacs.004 message"),
        @ApiResponse(responseCode = "503",
            description = "Return route disabled outside the explicit sandbox configuration"),
        @ApiResponse(responseCode = "500",
            description = "Internal processing error")
    })
    public ResponseEntity<Pacs002Message> submitReturn(@Valid @RequestBody Pacs004Message message) {
        if (!legacyReturnSandboxEnabled || !(fedNowClient instanceof SandboxFedNowClient)) {
            return ResponseEntity.status(503).build();
        }
        return ResponseEntity.ok(fedNowClient.submitReturn(message));
    }

    /**
     * Local gateway health check; does not test FedNow connectivity or certificates.
     */
    @GetMapping("/health")
    @Operation(
        summary = "Gateway health check",
        description = "Returns local gateway availability, not live rail connectivity. " +
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
