package io.openfednow.processing.saga;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the PaymentSaga state machine transition logic implemented
 * in {@link PaymentSaga#advance(PaymentSaga.SagaState)} and
 * {@link PaymentSaga#compensate(PaymentSaga.SagaState, String)}.
 */
class PaymentSagaAdvanceTest {

    // ── Happy path forward progression ───────────────────────────────────────

    @Test
    void happyPathForwardProgression() {
        PaymentSaga saga = new PaymentSaga("SAGA-001", "TXN-001");

        saga.advance(PaymentSaga.SagaState.FUNDS_RESERVED);
        assertThat(saga.getState()).isEqualTo(PaymentSaga.SagaState.FUNDS_RESERVED);

        saga.advance(PaymentSaga.SagaState.CORE_SUBMITTED);
        assertThat(saga.getState()).isEqualTo(PaymentSaga.SagaState.CORE_SUBMITTED);

        saga.advance(PaymentSaga.SagaState.FEDNOW_CONFIRMED);
        assertThat(saga.getState()).isEqualTo(PaymentSaga.SagaState.FEDNOW_CONFIRMED);

        saga.advance(PaymentSaga.SagaState.COMPLETED);
        assertThat(saga.getState()).isEqualTo(PaymentSaga.SagaState.COMPLETED);
    }

    // ── Compensation from each forward state ─────────────────────────────────

    @Test
    void compensationFromInitiated() {
        PaymentSaga saga = new PaymentSaga("SAGA-002", "TXN-002");
        saga.compensate(PaymentSaga.SagaState.INITIATED, "AM04");

        assertThat(saga.getState()).isEqualTo(PaymentSaga.SagaState.COMPENSATING);
        assertThat(saga.getFailureReason()).isEqualTo("AM04");
    }

    @Test
    void compensationFromFundsReserved() {
        PaymentSaga saga = new PaymentSaga("SAGA-003", "TXN-003");
        saga.advance(PaymentSaga.SagaState.FUNDS_RESERVED);
        saga.compensate(PaymentSaga.SagaState.FUNDS_RESERVED, "AC04");

        assertThat(saga.getState()).isEqualTo(PaymentSaga.SagaState.COMPENSATING);
        assertThat(saga.getFailureReason()).isEqualTo("AC04");
    }

    @Test
    void compensatingCanAdvanceToFailed() {
        PaymentSaga saga = new PaymentSaga("SAGA-004", "TXN-004");
        saga.compensate(PaymentSaga.SagaState.INITIATED, "NARR");
        saga.advance(PaymentSaga.SagaState.FAILED);

        assertThat(saga.getState()).isEqualTo(PaymentSaga.SagaState.FAILED);
    }

    // ── Invalid transitions ───────────────────────────────────────────────────

    @Test
    void skipStateIsRejected() {
        PaymentSaga saga = new PaymentSaga("SAGA-005", "TXN-005");
        // Cannot skip from INITIATED directly to CORE_SUBMITTED
        assertThatThrownBy(() -> saga.advance(PaymentSaga.SagaState.CORE_SUBMITTED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INITIATED")
                .hasMessageContaining("CORE_SUBMITTED");
    }

    @Test
    void completedIsTerminalAndCannotAdvance() {
        PaymentSaga saga = new PaymentSaga("SAGA-006", "TXN-006");
        saga.advance(PaymentSaga.SagaState.FUNDS_RESERVED);
        saga.advance(PaymentSaga.SagaState.CORE_SUBMITTED);
        saga.advance(PaymentSaga.SagaState.FEDNOW_CONFIRMED);
        saga.advance(PaymentSaga.SagaState.COMPLETED);

        assertThatThrownBy(() -> saga.advance(PaymentSaga.SagaState.COMPENSATING))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failedIsTerminalAndCannotAdvance() {
        PaymentSaga saga = new PaymentSaga("SAGA-007", "TXN-007");
        saga.compensate(PaymentSaga.SagaState.INITIATED, "NARR");
        saga.advance(PaymentSaga.SagaState.FAILED);

        assertThatThrownBy(() -> saga.advance(PaymentSaga.SagaState.COMPENSATING))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotAdvanceFromInitiatedToCompleted() {
        PaymentSaga saga = new PaymentSaga("SAGA-008", "TXN-008");
        assertThatThrownBy(() -> saga.advance(PaymentSaga.SagaState.COMPLETED))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── State restoration (package-private constructor) ───────────────────────

    @Test
    void restoredSagaHasCorrectState() {
        PaymentSaga saga = new PaymentSaga("SAGA-009", "TXN-009",
                PaymentSaga.SagaState.CORE_SUBMITTED);

        assertThat(saga.getState()).isEqualTo(PaymentSaga.SagaState.CORE_SUBMITTED);
        assertThat(saga.getSagaId()).isEqualTo("SAGA-009");
    }

    @Test
    void restoredSagaCanContinueForward() {
        PaymentSaga saga = new PaymentSaga("SAGA-010", "TXN-010",
                PaymentSaga.SagaState.CORE_SUBMITTED);

        saga.advance(PaymentSaga.SagaState.FEDNOW_CONFIRMED);
        assertThat(saga.getState()).isEqualTo(PaymentSaga.SagaState.FEDNOW_CONFIRMED);
    }
}
