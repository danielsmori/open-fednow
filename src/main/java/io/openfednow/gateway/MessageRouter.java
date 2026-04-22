package io.openfednow.gateway;

import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Layer 1 — Message Router
 *
 * <p>Routes validated ISO 20022 messages between the FedNow Service and the
 * Anti-Corruption Layer (Layer 2). Applies fraud pre-screening and rate
 * limiting before forwarding.
 *
 * <p>Message flow:
 * <pre>
 *   FedNow → FedNowGateway → MessageRouter → AntiCorruptionLayer → CoreBankingAdapter
 *   FedNow ← FedNowGateway ← MessageRouter ← AntiCorruptionLayer ← CoreBankingAdapter
 * </pre>
 */
@Component
public class MessageRouter {

    /**
     * Routes an inbound pacs.008 credit transfer from FedNow to the
     * Anti-Corruption Layer for processing against the core banking system.
     *
     * <p>Must return a pacs.002 acknowledgment within the FedNow 20-second
     * response window. If the core banking system cannot respond in time,
     * the SyncAsyncBridge in Layer 2 handles the timeout.
     *
     * @param message validated pacs.008.001.08 message from FedNow
     * @return pacs.002 status report (ACSC = accepted, RJCT = rejected)
     */
    public ResponseEntity<Pacs002Message> routeInbound(Pacs008Message message) {
        // TODO: implement fraud pre-screening, rate limiting, and ACL routing
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Routes an outbound pacs.008 credit transfer from the core banking system
     * to the FedNow Service.
     *
     * @param message pacs.008.001.08 message assembled by the ACL
     * @return pacs.002 status report returned by FedNow
     */
    public ResponseEntity<Pacs002Message> routeOutbound(Pacs008Message message) {
        // TODO: implement outbound routing and FedNow submission
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
