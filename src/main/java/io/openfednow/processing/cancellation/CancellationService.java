package io.openfednow.processing.cancellation;

import io.openfednow.iso20022.Camt029Message;
import io.openfednow.iso20022.Camt056Message;
import io.openfednow.processing.saga.PaymentSaga;
import io.openfednow.processing.saga.SagaOrchestrator;
import io.openfednow.processing.saga.SagaSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Routes an inbound camt.056 cancellation request to the right outcome based on the
 * lifecycle state of the saga that owns the original payment.
 *
 * <p>Two-step process:
 * <ol>
 *   <li>Look up the saga by the camt.056's {@code originalTransactionId}.</li>
 *   <li>Map the saga's current state to one of the three camt.029 outcomes:
 *     <ul>
 *       <li>{@code CNCL} — payment cancellable; reverse the Shadow Ledger credit (if applied) and mark the saga {@code FAILED}.</li>
 *       <li>{@code RJCR} — payment not cancellable, with one of two reason codes:
 *         <ul>
 *           <li>{@code ARDT} — settlement was confirmed pre-cancellation; the originator must use {@link io.openfednow.iso20022.Pacs004Message} to return funds.</li>
 *           <li>{@code NOOR} — no original transaction exists with the given ID.</li>
 *         </ul>
 *       </li>
 *       <li>{@code PDCR} — outcome cannot be determined yet ({@code CORE_SUBMITTED} or {@code COMPENSATING}); a follow-up resolution would be required.</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>The decision matrix is documented in
 * <a href="../../../../../../docs/adr/0007-camt056-cancellation-lifecycle.md">ADR-0007</a>.
 *
 * <p>Scope: this service handles <em>inbound</em> cancellation requests — another
 * institution asks us to cancel a payment that was sent to us. Outbound cancellation
 * (we initiate a camt.056 to recall our own payment) is not yet implemented.
 */
@Component
public class CancellationService {

    private static final Logger log = LoggerFactory.getLogger(CancellationService.class);

    /** ISO 20022 rejection reason: cancellation arrived after the payment was already settled. */
    public static final String RJCR_ALREADY_SETTLED = "ARDT";

    /** ISO 20022 rejection reason: no record of the original payment. */
    public static final String RJCR_NO_ORIGINAL = "NOOR";

    private final SagaOrchestrator sagaOrchestrator;

    public CancellationService(SagaOrchestrator sagaOrchestrator) {
        this.sagaOrchestrator = sagaOrchestrator;
    }

    /**
     * Processes an inbound camt.056 and returns the camt.029 to be sent back.
     *
     * <p>Side effects occur only when the request resolves to {@code CNCL} — the saga
     * is moved to {@code FAILED} and any Shadow Ledger credit is reversed. {@code RJCR}
     * and {@code PDCR} are pure read-side decisions that leave the saga unchanged.
     */
    public Camt029Message handleCancellationRequest(Camt056Message request) {
        log.info("Cancellation request received caseId={} originalTransactionId={} reason={}",
                request.getCaseId(), request.getOriginalTransactionId(),
                request.getCancellationReasonCode());

        Optional<SagaSnapshot> located =
                sagaOrchestrator.findByTransactionId(request.getOriginalTransactionId());

        if (located.isEmpty()) {
            log.warn("Cancellation rejected — no saga found for transactionId={}",
                    request.getOriginalTransactionId());
            return Camt029Message.rejected(request, RJCR_NO_ORIGINAL,
                    "No transaction recorded for the supplied originalTransactionId");
        }

        SagaSnapshot snapshot = located.get();
        PaymentSaga.SagaState state = snapshot.state();
        log.info("Cancellation lookup matched sagaId={} state={}", snapshot.sagaId(), state);

        return switch (state) {
            case INITIATED, FUNDS_RESERVED ->
                    cancel(request, snapshot);
            case CORE_SUBMITTED, COMPENSATING ->
                    pending(request, state);
            case FEDNOW_CONFIRMED, COMPLETED ->
                    alreadySettled(request, state);
            case FAILED ->
                    noOriginal(request, state);
        };
    }

    private Camt029Message cancel(Camt056Message request, SagaSnapshot snapshot) {
        log.info("Cancelling sagaId={} reason={}",
                snapshot.sagaId(), request.getCancellationReasonCode());
        sagaOrchestrator.cancelInboundSaga(snapshot.sagaId(), request.getCancellationReasonCode());
        return Camt029Message.cancelled(request);
    }

    private Camt029Message pending(Camt056Message request, PaymentSaga.SagaState state) {
        log.warn("Cancellation pending — saga in state {}; follow-up resolution required", state);
        return Camt029Message.pending(request);
    }

    private Camt029Message alreadySettled(Camt056Message request, PaymentSaga.SagaState state) {
        log.warn("Cancellation rejected — settlement already confirmed in state {}; originator must use pacs.004",
                state);
        return Camt029Message.rejected(request, RJCR_ALREADY_SETTLED,
                "Payment already settled; use pacs.004 return instead");
    }

    private Camt029Message noOriginal(Camt056Message request, PaymentSaga.SagaState state) {
        log.warn("Cancellation rejected — original saga is in terminal state {}", state);
        return Camt029Message.rejected(request, RJCR_NO_ORIGINAL,
                "Original transaction is no longer live (state=" + state + ")");
    }
}
