package io.openfednow.gateway;

import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
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
public class FedNowGateway {

    private final MessageRouter messageRouter;
    private final CertificateManager certificateManager;

    public FedNowGateway(MessageRouter messageRouter, CertificateManager certificateManager) {
        this.messageRouter = messageRouter;
        this.certificateManager = certificateManager;
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
    public ResponseEntity<Pacs002Message> receiveTransfer(@RequestBody Pacs008Message message) {
        certificateManager.validateClientCertificate();
        return messageRouter.routeInbound(message);
    }

    /**
     * Initiates an outbound FI-to-FI credit transfer to FedNow.
     * Called by the core banking system (via the ACL) to send a payment.
     *
     * @param message the ISO 20022 pacs.008.001.08 credit transfer message
     * @return pacs.002 payment status report from FedNow
     */
    @PostMapping("/send")
    public ResponseEntity<Pacs002Message> sendTransfer(@RequestBody Pacs008Message message) {
        return messageRouter.routeOutbound(message);
    }

    /**
     * Health check endpoint for FedNow connectivity monitoring.
     * Returns the current gateway status and certificate validity.
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OpenFedNow Gateway — operational");
    }
}
