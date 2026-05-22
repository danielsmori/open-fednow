package io.openfednow.processing.saga;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SagaTimeoutMonitor} — issue #37.
 *
 * <p>Uses a real {@link SimpleMeterRegistry} so the counter assertions verify
 * the metric actually wires up correctly. The orchestrator is mocked so this
 * test runs on every {@code mvn test} without Docker.
 */
class SagaTimeoutMonitorTest {

    private SagaOrchestrator orchestrator;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        orchestrator = mock(SagaOrchestrator.class);
        meterRegistry = new SimpleMeterRegistry();
    }

    // ── Configuration validation ─────────────────────────────────────────────

    @Test
    void zeroTimeoutIsRejected() {
        assertThatThrownBy(() -> new SagaTimeoutMonitor(orchestrator, meterRegistry, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout-seconds");
    }

    @Test
    void negativeTimeoutIsRejected() {
        assertThatThrownBy(() -> new SagaTimeoutMonitor(orchestrator, meterRegistry, -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout-seconds");
    }

    @Test
    void timeoutSecondsIsExposed() {
        SagaTimeoutMonitor monitor = new SagaTimeoutMonitor(orchestrator, meterRegistry, 45);
        assertThat(monitor.getTimeoutSeconds()).isEqualTo(45);
    }

    // ── No-op when nothing is stale ──────────────────────────────────────────

    @Test
    void sweepWithNoStaleSagasIsNoOp() {
        when(orchestrator.findTimedOutSagaIds(30)).thenReturn(List.of());

        int compensated = new SagaTimeoutMonitor(orchestrator, meterRegistry, 30)
                .sweepTimedOutSagas();

        assertThat(compensated).isZero();
        verify(orchestrator).findTimedOutSagaIds(30);
        verify(orchestrator, never()).compensate(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        assertThat(meterRegistry.counter(SagaTimeoutMonitor.TIMEOUT_METRIC).count()).isZero();
    }

    // ── Compensation dispatch ────────────────────────────────────────────────

    @Test
    void staleSagasAreCompensatedWithXpirReason() {
        when(orchestrator.findTimedOutSagaIds(30))
                .thenReturn(List.of("SAGA-T1", "SAGA-T2"));

        new SagaTimeoutMonitor(orchestrator, meterRegistry, 30).sweepTimedOutSagas();

        verify(orchestrator).compensate("SAGA-T1", SagaTimeoutMonitor.TIMEOUT_REASON_CODE);
        verify(orchestrator).compensate("SAGA-T2", SagaTimeoutMonitor.TIMEOUT_REASON_CODE);
    }

    @Test
    void counterIncrementedOncePerCompensatedSaga() {
        when(orchestrator.findTimedOutSagaIds(30))
                .thenReturn(List.of("SAGA-T1", "SAGA-T2", "SAGA-T3"));

        new SagaTimeoutMonitor(orchestrator, meterRegistry, 30).sweepTimedOutSagas();

        assertThat(meterRegistry.counter(SagaTimeoutMonitor.TIMEOUT_METRIC).count())
                .isEqualTo(3.0);
    }

    @Test
    void sweepReturnsCountOfCompensatedSagas() {
        when(orchestrator.findTimedOutSagaIds(30))
                .thenReturn(List.of("SAGA-T1", "SAGA-T2"));

        int compensated = new SagaTimeoutMonitor(orchestrator, meterRegistry, 30)
                .sweepTimedOutSagas();

        assertThat(compensated).isEqualTo(2);
    }

    // ── Per-saga failure isolation ───────────────────────────────────────────

    @Test
    void failureOnOneSagaDoesNotBlockOthers() {
        when(orchestrator.findTimedOutSagaIds(30))
                .thenReturn(List.of("SAGA-FAIL", "SAGA-OK"));
        org.mockito.Mockito.doThrow(new RuntimeException("simulated"))
                .when(orchestrator).compensate("SAGA-FAIL", SagaTimeoutMonitor.TIMEOUT_REASON_CODE);

        int compensated = new SagaTimeoutMonitor(orchestrator, meterRegistry, 30)
                .sweepTimedOutSagas();

        // Only SAGA-OK was compensated successfully
        assertThat(compensated).isEqualTo(1);
        verify(orchestrator).compensate("SAGA-OK", SagaTimeoutMonitor.TIMEOUT_REASON_CODE);
        // Counter reflects only successful compensations
        assertThat(meterRegistry.counter(SagaTimeoutMonitor.TIMEOUT_METRIC).count())
                .isEqualTo(1.0);
    }

    // ── Threshold plumbing ───────────────────────────────────────────────────

    @Test
    void configuredTimeoutValueIsPassedThroughToOrchestrator() {
        when(orchestrator.findTimedOutSagaIds(120)).thenReturn(List.of());

        new SagaTimeoutMonitor(orchestrator, meterRegistry, 120).sweepTimedOutSagas();

        verify(orchestrator).findTimedOutSagaIds(120);
    }

    @Test
    void sweepDoesNotCallCompensateWhenOrchestratorReturnsEmpty() {
        when(orchestrator.findTimedOutSagaIds(eq(30))).thenReturn(List.of());

        new SagaTimeoutMonitor(orchestrator, meterRegistry, 30).sweepTimedOutSagas();

        verify(orchestrator, times(1)).findTimedOutSagaIds(30);
        verify(orchestrator, never()).compensate(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
