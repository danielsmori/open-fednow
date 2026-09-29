package io.openfednow.gateway;

import io.openfednow.iso20022.Pacs002Message;
import io.openfednow.iso20022.Pacs004Message;
import io.openfednow.iso20022.Pacs008Message;

/**
 * Outbound client for submitting ISO 20022 pacs.008 credit transfers to the
 * a synthetic FedNow-shaped endpoint and receiving pacs.002-shaped reports.
 *
 * <p>{@link HttpFedNowClient} provides HTTP transport to a configured endpoint
 * and is activated when {@code FEDNOW_ENDPOINT} is set. In integration tests,
 * a WireMock server stands in for the FedNow simulator endpoint — no special
 * Spring profile is required. Live FedNow connectivity additionally requires
 * institution access, restricted message/transport specifications, status
 * authority validation, certification, and operational controls. The JSON
 * endpoint here is not documented as a live FedNow interface.
 *
 * <p>When {@code FEDNOW_ENDPOINT} is not set, {@link SandboxFedNowClient}
 * is active by default and returns synthetic in-memory responses suitable
 * for local development and testing.
 */
public interface FedNowClient {

    /**
     * Submits an outbound credit transfer to FedNow and returns the payment
     * status report.
     *
     * <p>This local call has a configurable timeout. It is not the FedNow
     * payment timeout clock defined by the service operating procedures.
     *
     * @param message the ISO 20022 pacs.008.001.08 credit transfer to submit
     * @return an authoritative pacs.002 status report
     * @throws SubmissionOutcomeUnknownException when transport fails or no valid
     *         status arrives; callers must retain funds and investigate
     */
    Pacs002Message submitCreditTransfer(Pacs008Message message);

    /**
     * Submits an outbound payment return (pacs.004) to FedNow.
     *
     * <p>Returns are the saga-compensation path for payments that were
     * provisionally accepted during a maintenance window but subsequently
     * rejected by the core, and the ops-initiated remediation path when a
     * settled payment must be undone (fraud, customer error, etc.). The
     * institution builds a {@link Pacs004Message} referencing the original
     * pacs.008 and submits it here; FedNow acknowledges with a pacs.002.
     *
     * <p>The current synthetic return path retries, but does not establish
     * rail-side deduplication via {@code returnId}; uncertain return outcomes
     * require separate investigation before any live mapping.
     *
     * @param message the ISO 20022 pacs.004.001.09 return to submit
     * @return pacs.002 status report from FedNow; never {@code null}. On
     *         network error or timeout a synthetic RJCT response with reason
     *         code {@code NARR} is returned rather than propagating an exception.
     */
    Pacs002Message submitReturn(Pacs004Message message);
}
