package io.openfednow.processing.saga;

import io.openfednow.gateway.Rail;

import java.time.Instant;

/**
 * Full read-only projection of a row in {@code saga_state}.
 *
 * <p>{@link PaymentSaga} is the in-memory state machine used by the
 * processing pipeline — it only carries the identifiers and the current state.
 * Operational tooling (admin endpoints, dashboards, audit queries) needs the
 * surrounding metadata: timestamps, reason code, failure description.
 * That richer view lives here.
 *
 * @param sagaId               opaque saga identifier assigned at initiation
 * @param transactionId        ISO 20022 TransactionId from the originating pacs.008
 * @param endToEndId           ISO 20022 EndToEndId (deduplication key)
 * @param state                current saga state
 * @param sourceRail           rail (FedNow or RTP) the inbound pacs.008 arrived on
 * @param returnReasonCode     ISO 20022 reason code set when compensation was triggered
 *                             (e.g., {@code AM04}, {@code AC04}, {@code NARR}); null otherwise
 * @param failureDescription   human-readable failure reason; null for non-failed sagas
 * @param createdAt            when the saga was initiated
 * @param updatedAt            when the most recent state transition was persisted
 */
public record SagaSnapshot(
        String sagaId,
        String transactionId,
        String endToEndId,
        PaymentSaga.SagaState state,
        Rail sourceRail,
        String returnReasonCode,
        String failureDescription,
        Instant createdAt,
        Instant updatedAt
) {
    /** Age of the saga in seconds since initiation. Convenience for monitoring. */
    public long ageSeconds() {
        return java.time.Duration.between(createdAt, Instant.now()).getSeconds();
    }
}
