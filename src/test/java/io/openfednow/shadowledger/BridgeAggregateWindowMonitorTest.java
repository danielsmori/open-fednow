package io.openfednow.shadowledger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.openfednow.bridgemode.BridgeAggregateCapPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Aggregate-window gauges — issue #68, PR 4. Cap $500, expected window 6h,
 * near-cap threshold 80%.
 */
class BridgeAggregateWindowMonitorTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    private final ShadowLedger ledger = mock(ShadowLedger.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void gaugesReflectTheOpenWindows() {
        when(ledger.openAggregateAccountIds()).thenReturn(List.of("ACC-NEAR", "ACC-OLD", "ACC-GONE"));
        // 90% of the cap, opened an hour ago
        when(ledger.getAggregateView("ACC-NEAR")).thenReturn(view("ACC-NEAR", "450.00", Duration.ofHours(1)));
        // 20% of the cap, opened 7 hours ago: overdue against the 6h expectation
        when(ledger.getAggregateView("ACC-OLD")).thenReturn(view("ACC-OLD", "100.00", Duration.ofHours(7)));
        // closed between the listing and the read
        when(ledger.getAggregateView("ACC-GONE")).thenReturn(null);
        BridgeAggregateWindowMonitor monitor = monitor(enabledPolicy());

        monitor.scan();

        assertThat(gauge(BridgeAggregateWindowMonitor.WINDOWS_OPEN_METRIC)).isEqualTo(2);
        assertThat(gauge(BridgeAggregateWindowMonitor.WINDOWS_NEAR_CAP_METRIC)).isEqualTo(1);
        assertThat(gauge(BridgeAggregateWindowMonitor.WINDOWS_OVERDUE_METRIC)).isEqualTo(1);
        assertThat(gauge(BridgeAggregateWindowMonitor.OLDEST_WINDOW_AGE_METRIC))
                .isEqualTo(Duration.ofHours(7).getSeconds());
    }

    @Test
    void nearCapThresholdIsInclusive() {
        when(ledger.openAggregateAccountIds()).thenReturn(List.of("ACC-80", "ACC-79"));
        when(ledger.getAggregateView("ACC-80")).thenReturn(view("ACC-80", "400.00", Duration.ZERO));
        when(ledger.getAggregateView("ACC-79")).thenReturn(view("ACC-79", "399.99", Duration.ZERO));
        BridgeAggregateWindowMonitor monitor = monitor(enabledPolicy());

        monitor.scan();

        assertThat(gauge(BridgeAggregateWindowMonitor.WINDOWS_NEAR_CAP_METRIC)).isEqualTo(1);
    }

    @Test
    void gaugesDropBackOnceWindowsClose() {
        when(ledger.openAggregateAccountIds())
                .thenReturn(List.of("ACC-OLD"))
                .thenReturn(List.of());
        when(ledger.getAggregateView("ACC-OLD")).thenReturn(view("ACC-OLD", "450.00", Duration.ofHours(7)));
        BridgeAggregateWindowMonitor monitor = monitor(enabledPolicy());

        monitor.scan();
        monitor.scan();

        assertThat(gauge(BridgeAggregateWindowMonitor.WINDOWS_OPEN_METRIC)).isZero();
        assertThat(gauge(BridgeAggregateWindowMonitor.WINDOWS_NEAR_CAP_METRIC)).isZero();
        assertThat(gauge(BridgeAggregateWindowMonitor.WINDOWS_OVERDUE_METRIC)).isZero();
        assertThat(gauge(BridgeAggregateWindowMonitor.OLDEST_WINDOW_AGE_METRIC)).isZero();
    }

    @Test
    void disabledCapNeverScansAndReportsZero() {
        BridgeAggregateWindowMonitor monitor = monitor(new BridgeAggregateCapPolicy("", 6, true));

        monitor.start();
        monitor.scan();
        monitor.stop();

        verify(ledger, never()).openAggregateAccountIds();
        assertThat(gauge(BridgeAggregateWindowMonitor.WINDOWS_OPEN_METRIC)).isZero();
    }

    @Test
    void failedScanIsSwallowedOnTheMonitorThread() throws Exception {
        when(ledger.openAggregateAccountIds()).thenThrow(new IllegalStateException("redis down"));
        BridgeAggregateWindowMonitor monitor = monitor(enabledPolicy());

        // the scheduled entry point must not throw (that would cancel the schedule)
        java.lang.reflect.Method scanSafely = BridgeAggregateWindowMonitor.class.getDeclaredMethod("scanSafely");
        scanSafely.setAccessible(true);
        assertThatCode(() -> scanSafely.invoke(monitor)).doesNotThrowAnyException();
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new BridgeAggregateWindowMonitor(
                ledger, enabledPolicy(), meters, 0, 60, clock()));
        assertThatIllegalArgumentException().isThrownBy(() -> new BridgeAggregateWindowMonitor(
                ledger, enabledPolicy(), meters, 101, 60, clock()));
        assertThatIllegalArgumentException().isThrownBy(() -> new BridgeAggregateWindowMonitor(
                ledger, enabledPolicy(), meters, 80, 0, clock()));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private BridgeAggregateWindowMonitor monitor(BridgeAggregateCapPolicy policy) {
        return new BridgeAggregateWindowMonitor(ledger, policy, meters, 80, 60, clock());
    }

    private static BridgeAggregateCapPolicy enabledPolicy() {
        return new BridgeAggregateCapPolicy("500.00", 6, true);
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static BridgeAggregateView view(String accountId, String total, Duration age) {
        return new BridgeAggregateView(accountId, new BigDecimal(total), NOW.minus(age), 1);
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }
}
