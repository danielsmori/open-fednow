package io.openfednow.gateway;

import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;

/**
 * Outbound client for submitting ISO 20022 pacs.008 credit transfers to the
 * Federal Reserve FedNow Service and receiving pacs.002 status reports in return.
 *
 * <p>In production this is backed by {@link HttpFedNowClient}, which makes a
 * real HTTPS call to the configured {@code openfednow.gateway.fednow-endpoint}.
 * In integration tests a WireMock server stands in for FedNow, and
 * {@code HttpFedNowClient} is pointed at the WireMock base URL instead — no
 * special test double or Spring profile required.
 */
public interface FedNowClient {

    /**
     * Submits an outbound credit transfer to FedNow and returns the payment
     * status report.
     *
     * <p>This call must complete within the FedNow 20-second response window.
     * The implementation enforces a configurable timeout (default: 18 seconds)
     * to leave headroom for upstream processing.
     *
     * @param message the ISO 20022 pacs.008.001.08 credit transfer to submit
     * @return pacs.002 status report from FedNow; never {@code null}. On
     *         network error or timeout a synthetic RJCT response with reason
     *         code {@code NARR} is returned rather than propagating an exception.
     */
    Pacs002Message submitCreditTransfer(Pacs008Message message);
}
