package io.openfednow.processing.fraud;

import io.openfednow.iso20022.Pacs008Message;

/**
 * Extensibility seam for fraud pre-screening of credit transfers.
 *
 * <p>The default implementation ({@link DefaultFraudScreeningService}) applies a
 * configurable rule set — amount cap, debtor velocity, account denylist —
 * suitable for reference deployments and local development. Institutions with
 * mature fraud programs are expected to provide their own implementation that
 * calls into a hosted scoring service, ML model, or rules engine. The contract
 * is intentionally narrow: one synchronous method, one structured result.
 *
 * <p>Direction is implicit in the call site:
 * {@link io.openfednow.gateway.MessageRouter#routeInbound MessageRouter.routeInbound}
 * invokes the port before any inbound side effects (Shadow Ledger credit, saga
 * initiation); {@link io.openfednow.gateway.MessageRouter#routeOutbound routeOutbound}
 * invokes it before any outbound side effects (sufficient-funds check, saga
 * initiation, debit reservation). Implementations can branch on direction by
 * inspecting fields on the {@link Pacs008Message} (creditor vs. debtor accounts).
 *
 * <p>Implementations must be fast — the framework calls this synchronously on
 * the request thread inside FedNow's 20-second SLA window. Network-bound
 * external scoring should be wrapped in a circuit breaker.
 *
 * @see ScreeningResult
 * @see DefaultFraudScreeningService
 */
public interface FraudScreeningPort {

    /**
     * Evaluates a credit transfer against the institution's fraud policy.
     *
     * <p>Implementations must not mutate state on a {@code PASS} or
     * {@code REVIEW} result. For {@code BLOCK} they may record audit
     * information but must not invoke any payment-side effects — that's the
     * router's responsibility based on the returned decision.
     *
     * @param message the pacs.008 credit transfer to evaluate
     * @return a {@link ScreeningResult} carrying the decision and reason
     */
    ScreeningResult screen(Pacs008Message message);
}
