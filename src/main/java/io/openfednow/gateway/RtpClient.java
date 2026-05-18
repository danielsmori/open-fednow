package io.openfednow.gateway;

import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs008Message;

/**
 * Outbound client for submitting ISO 20022 pacs.008 credit transfers to The Clearing
 * House RTP® network and receiving pacs.002 status reports in return.
 *
 * <p>{@link HttpRtpClient} provides HTTP/XML transport to a configured TCH endpoint
 * and is activated when {@code RTP_ENDPOINT} is set. In integration tests, a WireMock
 * server stands in for the TCH endpoint. Live RTP connectivity additionally requires
 * TCH institutional participation, TCH PKI certificates, and private-network transport;
 * those are institution-provided credentials, not framework components.
 *
 * <p>When {@code RTP_ENDPOINT} is not set, {@link SandboxRtpClient} is active by
 * default and returns synthetic in-memory responses suitable for local development
 * and testing — the same sandbox pattern used by {@link SandboxFedNowClient}.
 *
 * @see HttpRtpClient
 * @see SandboxRtpClient
 * @see FedNowClient
 */
public interface RtpClient {

    /**
     * Submits an outbound credit transfer to the RTP® network and returns the
     * payment status report.
     *
     * <p>The message is serialized to a canonical ISO 20022 pacs.008.001.08 XML
     * envelope before transmission. The TCH response is a pacs.002 XML envelope
     * parsed into a {@link Pacs002Message}. On network error or timeout a
     * synthetic RJCT response with reason code {@code NARR} is returned rather
     * than propagating an exception.
     *
     * @param message the ISO 20022 pacs.008.001.08 credit transfer to submit
     * @return pacs.002 status report from the RTP® network; never {@code null}
     */
    Pacs002Message submitCreditTransfer(Pacs008Message message);
}
