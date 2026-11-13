package io.openfednow.shadowledger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.openfednow.bridgemode.BridgeAggregateCapPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Gauges over the open aggregate windows — issue #68, PR 4.
 */
class BridgeAggregateWindowMetricsTest {

    private final ShadowLedger ledger = mock(ShadowLedger.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    /** Cap $500, expected window 6h. */
    private final BridgeAggregateCapPolicy enabled = new BridgeAggregateCapPolicy("500.00", 6, true);

    @Test
    void capDisabledReadsZeroAndNeverTouchesTheLedger() {
        BridgeAggregateCapPolicy disabled = new BridgeAggregateCapPolicy("", 6, true);
        BridgeAggregateWindowMetrics metrics =
                new BridgeAggregateWindowMetrics(ledger, disabled, meters, null);

        metrics.sampleNow();

        assertThat(gauge(BridgeAggregateWindowMetrics.OPEN_WINDOWS_METRIC)).isZero();
        assertThat(gauge(BridgeAggregateWindowMetrics.NEAR_CAP_METRIC)).isZero();
        assertThat(gauge(BridgeAggregateWindowMetrics.OVERDUE_METRIC)).isZero();
        verifyNoInteractions(ledger);
    }

    @Test
    void windowsAreCountedByStateIncludingTheNearCapBoundary() {
        BridgeAggregateWindowMetrics metrics =
                new BridgeAggregateWindowMetrics(ledger, enabled, meters, null);
        Instant fresh = Instant.now();
        Instant stale = Instant.now().minusSeconds(7 * 3600); // older than 6h
        when(ledger.openAggregateAccountIds()).thenReturn(List.of("A", "B", "C", "D"));
        when(ledger.getAggregateView("A")) // exactly 80% of $500 → near-cap
                .thenReturn(new BridgeAggregateView("A", new BigDecimal("400.00"), fresh, 1));
        when(ledger.getAggregateView("B")) // 79.998% → not near-cap
                .thenReturn(new BridgeAggregateView("B", new BigDecimal("399.99"), fresh, 1));
        when(ledger.getAggregateView("C")) // stale → overdue (the stuck-window signal)
                .thenReturn(new BridgeAggregateView("C", new BigDecimal("100.00"), stale, 1));
        when(ledger.getAggregateView("D")) // closed between the SCAN and the read
                .thenReturn(null);

        metrics.sampleNow();

        assertThat(gauge(BridgeAggregateWindowMetrics.OPEN_WINDOWS_METRIC)).isEqualTo(3);
        assertThat(gauge(BridgeAggregateWindowMetrics.NEAR_CAP_METRIC)).isEqualTo(1);
        assertThat(gauge(BridgeAggregateWindowMetrics.OVERDUE_METRIC)).isEqualTo(1);
    }

    @Test
    void failedSampleKeepsTheLastValues() {
        BridgeAggregateWindowMetrics metrics =
                new BridgeAggregateWindowMetrics(ledger, enabled, meters, null);
        when(ledger.openAggregateAccountIds()).thenReturn(List.of("A"));
        when(ledger.getAggregateView("A"))
                .thenReturn(new BridgeAggregateView("A", new BigDecimal("450.00"), Instant.now(), 1));
        metrics.sampleNow();
        assertThat(gauge(BridgeAggregateWindowMetrics.OPEN_WINDOWS_METRIC)).isEqualTo(1);

        when(ledger.openAggregateAccountIds()).thenThrow(new IllegalStateException("redis down"));
        metrics.sampleNow();

        assertThat(gauge(BridgeAggregateWindowMetrics.OPEN_WINDOWS_METRIC)).isEqualTo(1);
        assertThat(gauge(BridgeAggregateWindowMetrics.NEAR_CAP_METRIC)).isEqualTo(1);
    }

    @Test
    void emptyLedgerReadsZero() {
        BridgeAggregateWindowMetrics metrics =
                new BridgeAggregateWindowMetrics(ledger, enabled, meters, null);
        when(ledger.openAggregateAccountIds()).thenReturn(List.of());

        metrics.sampleNow();

        assertThat(gauge(BridgeAggregateWindowMetrics.OPEN_WINDOWS_METRIC)).isZero();
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }
}
