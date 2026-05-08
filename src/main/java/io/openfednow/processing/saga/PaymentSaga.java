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
 *
 * <p>State is persisted to {@code saga_state} by {@link SagaOrchestrator};
 * this class is a pure state machine with no infrastructure dependencies.
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
    private String failureReason;

    public PaymentSaga(String sagaId, String transactionId) {
        this.sagaId = sagaId;
        this.transactionId = transactionId;
        this.state = SagaState.INITIATED;
    }

    /** Package-private constructor used by {@link SagaOrchestrator} to restore state from DB. */
    PaymentSaga(String sagaId, String transactionId, SagaState restoredState) {
        this.sagaId = sagaId;
        this.transactionId = transactionId;
        this.state = restoredState;
    }

    /**
     * Advances the saga to the next state after successful step completion.
     *
     * <p>Validates that the requested transition is legal according to the
     * saga state machine. The calling orchestrator is responsible for
     * persisting the new state to the database before acting on it.
     *
     * @param nextState the state to transition to
     * @throws IllegalStateException if the transition is not valid from the current state
     */
    public void advance(SagaState nextState) {
        validateTransition(this.state, nextState);
        this.state = nextState;
    }

    /**
     * Triggers the compensation sequence, rolling back completed steps
     * in reverse order.
     *
     * <p>Sets the saga state to {@code COMPENSATING} and records the failure
     * reason. The orchestrator is responsible for executing the actual
     * compensation steps and persisting the final {@code FAILED} state.
     *
     * @param failedState the state at which the failure occurred
     * @param reason ISO 20022 reason code for the failure
     */
    public void compensate(SagaState failedState, String reason) {
        this.state = SagaState.COMPENSATING;
        this.failureReason = reason;
    }

    // ── Getters ────────────────────────────────────────────────────────────────

    public String getSagaId()       { return sagaId; }
    public String getTransactionId() { return transactionId; }
    public SagaState getState()     { return state; }
    public String getFailureReason() { return failureReason; }

    // ── State machine validation ───────────────────────────────────────────────

    private static void validateTransition(SagaState from, SagaState to) {
        boolean valid = switch (from) {
            case INITIATED        -> to == SagaState.FUNDS_RESERVED   || to == SagaState.COMPENSATING;
            case FUNDS_RESERVED   -> to == SagaState.CORE_SUBMITTED    || to == SagaState.COMPENSATING;
            case CORE_SUBMITTED   -> to == SagaState.FEDNOW_CONFIRMED  || to == SagaState.COMPENSATING;
            case FEDNOW_CONFIRMED -> to == SagaState.COMPLETED         || to == SagaState.COMPENSATING;
            case COMPENSATING     -> to == SagaState.FAILED;
            case COMPLETED, FAILED -> false; // terminal states
        };
        if (!valid) {
            throw new IllegalStateException(
                    "Invalid saga transition: " + from + " → " + to);
        }
    }
}
