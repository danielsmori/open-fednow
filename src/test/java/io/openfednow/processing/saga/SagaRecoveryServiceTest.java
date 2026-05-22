package io.openfednow.processing.saga;

import io.openfednow.gateway.Rail;
import io.openfednow.processing.saga.PaymentSaga.SagaState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SagaRecoveryService} dispatch logic — issue #36.
 *
 * <p>Mocks {@link SagaOrchestrator} and {@link JdbcTemplate} so these tests run on
 * every {@code mvn test} without needing Docker / Testcontainers. The corresponding
 * integration test ({@link SagaRecoveryServiceIntegrationTest}) exercises the full
 * Spring + Redis + Postgres wiring.
 */
class SagaRecoveryServiceTest {

    private SagaOrchestrator orchestrator;
    private JdbcTemplate jdbc;
    private SagaRecoveryService recovery;

    @BeforeEach
    void setUp() {
        orchestrator = mock(SagaOrchestrator.class);
        jdbc = mock(JdbcTemplate.class);
        recovery = new SagaRecoveryService(orchestrator, jdbc);
    }

    @Test
    void emptyInflightListResultsInNoOp() {
        when(jdbc.queryForList(any(String.class), eq(String.class))).thenReturn(List.of());

        int recovered = recovery.recoverInflightSagas();

        assertThat(recovered).isZero();
        verifyNoInteractions(orchestrator);
    }

    @Test
    void initiatedSagaIsCompensatedWithRecoveryReason() {
        stubInflight("SAGA-A");
        stubResume("SAGA-A", SagaState.INITIATED);

        recovery.recoverInflightSagas();

        verify(orchestrator).compensate("SAGA-A", SagaRecoveryService.RECOVERY_REASON);
    }

    @Test
    void fundsReservedSagaIsCompensated() {
        stubInflight("SAGA-B");
        stubResume("SAGA-B", SagaState.FUNDS_RESERVED);

        recovery.recoverInflightSagas();

        verify(orchestrator).compensate("SAGA-B", SagaRecoveryService.RECOVERY_REASON);
    }

    @Test
    void coreSubmittedSagaIsCompensated() {
        stubInflight("SAGA-C");
        stubResume("SAGA-C", SagaState.CORE_SUBMITTED);

        recovery.recoverInflightSagas();

        verify(orchestrator).compensate("SAGA-C", SagaRecoveryService.RECOVERY_REASON);
    }

    @Test
    void fednowConfirmedSagaIsAdvancedToCompleted() {
        stubInflight("SAGA-D");
        PaymentSaga saga = stubResume("SAGA-D", SagaState.FEDNOW_CONFIRMED);

        recovery.recoverInflightSagas();

        ArgumentCaptor<SagaState> targetState = ArgumentCaptor.forClass(SagaState.class);
        verify(orchestrator).advance(eq(saga), targetState.capture());
        assertThat(targetState.getValue()).isEqualTo(SagaState.COMPLETED);
        verify(orchestrator, never()).compensate(any(), any());
    }

    @Test
    void compensatingSagaIsAdvancedToFailed() {
        stubInflight("SAGA-E");
        PaymentSaga saga = stubResume("SAGA-E", SagaState.COMPENSATING);

        recovery.recoverInflightSagas();

        ArgumentCaptor<SagaState> targetState = ArgumentCaptor.forClass(SagaState.class);
        verify(orchestrator).advance(eq(saga), targetState.capture());
        assertThat(targetState.getValue()).isEqualTo(SagaState.FAILED);
        verify(orchestrator, never()).compensate(any(), any());
    }

    @Test
    void mixedBatchDispatchesEachSagaToItsCorrectHandler() {
        stubInflight("SAGA-INIT", "SAGA-CONF");
        stubResume("SAGA-INIT", SagaState.INITIATED);
        PaymentSaga confirmed = stubResume("SAGA-CONF", SagaState.FEDNOW_CONFIRMED);

        int recovered = recovery.recoverInflightSagas();

        assertThat(recovered).isEqualTo(2);
        verify(orchestrator).compensate("SAGA-INIT", SagaRecoveryService.RECOVERY_REASON);
        verify(orchestrator).advance(confirmed, SagaState.COMPLETED);
    }

    @Test
    void failureOnOneSagaDoesNotBlockOthers() {
        stubInflight("SAGA-FAIL", "SAGA-OK");
        stubResume("SAGA-FAIL", SagaState.INITIATED);
        stubResume("SAGA-OK", SagaState.INITIATED);

        // Make compensation of SAGA-FAIL throw; SAGA-OK should still be processed
        org.mockito.Mockito.doThrow(new RuntimeException("simulated"))
                .when(orchestrator).compensate("SAGA-FAIL", SagaRecoveryService.RECOVERY_REASON);

        int recovered = recovery.recoverInflightSagas();

        // Only SAGA-OK was recovered successfully
        assertThat(recovered).isEqualTo(1);
        verify(orchestrator, times(1)).compensate("SAGA-OK", SagaRecoveryService.RECOVERY_REASON);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubInflight(String... sagaIds) {
        when(jdbc.queryForList(any(String.class), eq(String.class)))
                .thenReturn(List.of(sagaIds));
    }

    private PaymentSaga stubResume(String sagaId, SagaState state) {
        PaymentSaga saga = new PaymentSaga(sagaId, "TXN-" + sagaId, Rail.FEDNOW, state);
        when(orchestrator.resume(sagaId)).thenReturn(saga);
        return saga;
    }
}
