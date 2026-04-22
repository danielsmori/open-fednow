package io.openfednow.processing.saga;

/**
 * Layer 3 — Payment Saga
 *
 * <p>Defines the steps and compensating transactions for a single
 * FedNow credit transfer processed through OpenFedNow.
 *
 * <p>The Saga pattern manages distributed transactions across multiple
 * systems (FedNow, Shadow Ledger, Core Banking) where a single ACID
 * transaction is not possible. Each step in the saga has a corresponding
 * compensating action that reverses it if a later step fails.
 *
 * <p>Payment saga steps:
 * <ol>
 *   <li>Reserve funds in Shadow Ledger (compensate: release reservation)</li>
 *   <li>Submit to core banking system (compensate: request reversal)</li>
 *   <li>Confirm to FedNow (compensate: send return payment pacs.004)</li>
 *   <li>Reconcile Shadow Ledger with core confirmation (no compensation needed)</li>
 * </ol>
 */
public class PaymentSaga {

    public enum SagaState {
        INITIATED,
        FUNDS_RESERVED,
        CORE_SUBMITTED,
        FEDNOW_CONFIRMED,
        COMPLETED,
        COMPENSATING,
        FAILED
    }

    private final String sagaId;
    private final String transactionId;
    private SagaState state;

    public PaymentSaga(String sagaId, String transactionId) {
        this.sagaId = sagaId;
        this.transactionId = transactionId;
        this.state = SagaState.INITIATED;
    }

    /**
     * Advances the saga to the next state after successful step completion.
     *
     * @param nextState the state to transition to
     */
    public void advance(SagaState nextState) {
        // TODO: implement state machine with persistence
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Triggers the compensation sequence, rolling back completed steps
     * in reverse order.
     *
     * @param failedState the state at which the failure occurred
     * @param reason ISO 20022 reason code for the failure
     */
    public void compensate(SagaState failedState, String reason) {
        // TODO: implement compensation chain
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public String getSagaId() { return sagaId; }
    public String getTransactionId() { return transactionId; }
    public SagaState getState() { return state; }
}
