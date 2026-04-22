package io.openfednow.processing.saga;

import io.openfednow.iso20022.Pacs008Message;
import org.springframework.stereotype.Component;

/**
 * Layer 3 — Saga Orchestrator
 *
 * <p>Manages the lifecycle of PaymentSaga instances across all in-flight
 * FedNow transactions. Coordinates the sequence of steps, handles timeouts,
 * and triggers compensation when failures occur.
 *
 * <p>The orchestrator is the central coordinator for distributed transaction
 * safety in OpenFedNow. It ensures that every payment either completes
 * fully across all systems or is safely reversed — there are no partial
 * or inconsistent states.
 */
@Component
public class SagaOrchestrator {

    /**
     * Initiates a new payment saga for an inbound pacs.008 credit transfer.
     * Creates a durable saga record and begins the first step (funds reservation
     * in the Shadow Ledger).
     *
     * @param message the validated pacs.008 to process
     * @return the initiated PaymentSaga instance
     */
    public PaymentSaga initiate(Pacs008Message message) {
        // TODO: create saga, persist initial state, begin first step
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Resumes a saga that was interrupted (e.g., by a system restart).
     * Used during startup to recover in-flight transactions from durable storage.
     *
     * @param sagaId the identifier of the saga to resume
     * @return the resumed PaymentSaga
     */
    public PaymentSaga resume(String sagaId) {
        // TODO: load saga state from persistence and resume from last known state
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Triggers the compensation sequence for a saga that has failed.
     * Called when any step returns an unrecoverable error.
     *
     * @param sagaId the identifier of the failed saga
     * @param reason ISO 20022 reason code for the failure
     */
    public void compensate(String sagaId, String reason) {
        // TODO: load saga, reverse completed steps in order, update state
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
