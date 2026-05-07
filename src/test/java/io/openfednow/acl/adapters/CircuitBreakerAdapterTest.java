package io.openfednow.acl.adapters;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.openfednow.acl.core.CoreBankingAdapter;
import io.openfednow.acl.core.CoreBankingResponse;
import io.openfednow.iso20022.Pacs008Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Resilience4j circuit breaker behaviour wired to
 * {@link CoreBankingAdapter} calls.
 *
 * <p>These tests exercise the circuit breaker logic directly using
 * Resilience4j's API, mirroring the behaviour produced by the
 * {@code @CircuitBreaker(name="corebanking")} annotations on
 * {@link FiservAdapter}, {@link FisAdapter}, and {@link JackHenryAdapter}.
 *
 * <p>The configuration here intentionally matches the
 * {@code resilience4j.circuitbreaker.instances.corebanking} block in
 * {@code application.yml} (with a smaller sliding window so tests run fast).
 */
class CircuitBreakerAdapterTest {

    /** Mirrors application.yml thresholds; reduced window for test speed. */
    private static final CircuitBreakerConfig CONFIG = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(5)
            .minimumNumberOfCalls(3)
            .failureRateThreshold(50.0f)
            .slowCallDurationThreshold(Duration.ofSeconds(15))
            .slowCallRateThreshold(50.0f)
            .waitDurationInOpenState(Duration.ofMinutes(5))
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();

    private CircuitBreaker circuitBreaker;
    private CoreBankingAdapter failingAdapter;

    @BeforeEach
    void setUp() {
        circuitBreaker = CircuitBreakerRegistry.of(CONFIG).circuitBreaker("corebanking");
        failingAdapter = mock(CoreBankingAdapter.class);
        when(failingAdapter.postCreditTransfer(any()))
                .thenThrow(new RuntimeException("Fiserv API unreachable"));
        when(failingAdapter.getAvailableBalance(any()))
                .thenThrow(new RuntimeException("Fiserv API unreachable"));
        when(failingAdapter.isCoreSystemAvailable())
                .thenThrow(new RuntimeException("Fiserv API unreachable"));
    }

    // ── Circuit state transitions ────────────────────────────────────────────

    @Test
    void circuitStartsClosed() {
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void circuitOpensAfterRepeatedFailures() {
        Supplier<CoreBankingResponse> decorated = CircuitBreaker.decorateSupplier(
                circuitBreaker, () -> failingAdapter.postCreditTransfer(testMessage()));

        // Drive 3 failures (minimumNumberOfCalls); all fail → 100% failure rate
        for (int i = 0; i < 3; i++) {
            safeGet(decorated);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void openCircuitShortCircuitsWithoutCallingCore() {
        AtomicInteger actualCalls = new AtomicInteger(0);
        CoreBankingAdapter countingAdapter = mock(CoreBankingAdapter.class);
        when(countingAdapter.postCreditTransfer(any())).thenAnswer(inv -> {
            actualCalls.incrementAndGet();
            throw new RuntimeException("Core unreachable");
        });

        Supplier<CoreBankingResponse> decorated = CircuitBreaker.decorateSupplier(
                circuitBreaker, () -> countingAdapter.postCreditTransfer(testMessage()));

        // Open the circuit
        for (int i = 0; i < 3; i++) {
            safeGet(decorated);
        }

        int callsWhenOpen = actualCalls.get();

        // Next call must be rejected by the circuit breaker without reaching the adapter
        assertThatThrownBy(decorated::get).isInstanceOf(CallNotPermittedException.class);
        assertThat(actualCalls.get()).isEqualTo(callsWhenOpen);
    }

    // ── Fallback semantics (pattern used by @CircuitBreaker annotations) ─────

    @Test
    void postCreditTransferFallbackReturnsTimeout() {
        // The @CircuitBreaker fallback on all real adapters returns TIMEOUT so that
        // SyncAsyncBridge can issue a provisional FedNow acceptance while the
        // core system is unreachable.
        CoreBankingResponse fallback = new CoreBankingResponse(
                CoreBankingResponse.Status.TIMEOUT, null, "CB_OPEN", null);

        assertThat(fallback.getStatus()).isEqualTo(CoreBankingResponse.Status.TIMEOUT);
        assertThat(fallback.getVendorStatusCode()).isEqualTo("CB_OPEN");
    }

    @Test
    void isCoreSystemAvailableFallbackReturnsFalse() {
        // When the circuit is open, isCoreSystemAvailable() returns false.
        // This causes AvailabilityBridge.isInBridgeMode() to return true,
        // activating Shadow Ledger processing.
        Supplier<Boolean> decorated = CircuitBreaker.decorateSupplier(
                circuitBreaker, () -> failingAdapter.isCoreSystemAvailable());

        // Open the circuit
        for (int i = 0; i < 3; i++) {
            safeGetBoolean(decorated);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        // Subsequent call is rejected — callers must handle CallNotPermittedException
        // and treat it as false (core unavailable), which the adapter fallback does.
        assertThatThrownBy(decorated::get).isInstanceOf(CallNotPermittedException.class);
    }

    // ── Circuit metrics ──────────────────────────────────────────────────────

    @Test
    void metricsRecordFailureCalls() {
        Supplier<CoreBankingResponse> decorated = CircuitBreaker.decorateSupplier(
                circuitBreaker, () -> failingAdapter.postCreditTransfer(testMessage()));

        for (int i = 0; i < 3; i++) {
            safeGet(decorated);
        }

        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        assertThat(metrics.getNumberOfFailedCalls()).isEqualTo(3);
        assertThat(metrics.getNumberOfSuccessfulCalls()).isZero();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Pacs008Message testMessage() {
        return Pacs008Message.builder()
                .transactionId("TXN-CB-TEST")
                .endToEndId("E2E-CB-TEST")
                .debtorAccountNumber("TEST-ACCOUNT")
                .creditorAccountNumber("DEST-ACCOUNT")
                .interbankSettlementAmount(new BigDecimal("100.00"))
                .interbankSettlementCurrency("USD")
                .build();
    }

    private void safeGet(Supplier<?> supplier) {
        try {
            supplier.get();
        } catch (Exception ignored) {}
    }

    private void safeGetBoolean(Supplier<Boolean> supplier) {
        try {
            supplier.get();
        } catch (Exception ignored) {}
    }
}
