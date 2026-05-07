package io.openfednow.acl.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoreBankingHealthIndicatorTest {

    private CoreBankingAdapter adapter;
    private CoreBankingHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        adapter = mock(CoreBankingAdapter.class);
        when(adapter.getVendorName()).thenReturn("Fiserv-DNA");
        indicator = new CoreBankingHealthIndicator(adapter);
    }

    @Test
    void up_whenCoreIsAvailable() {
        when(adapter.isCoreSystemAvailable()).thenReturn(true);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void includesVendorName_whenUp() {
        when(adapter.isCoreSystemAvailable()).thenReturn(true);

        Health health = indicator.health();

        assertThat(health.getDetails()).containsEntry("vendor", "Fiserv-DNA");
    }

    @Test
    void includesStatusReachable_whenUp() {
        when(adapter.isCoreSystemAvailable()).thenReturn(true);

        Health health = indicator.health();

        assertThat(health.getDetails()).containsEntry("status", "reachable");
    }

    @Test
    void outOfService_whenCoreIsUnavailable() {
        when(adapter.isCoreSystemAvailable()).thenReturn(false);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    @Test
    void includesVendorName_whenOutOfService() {
        when(adapter.isCoreSystemAvailable()).thenReturn(false);

        Health health = indicator.health();

        assertThat(health.getDetails()).containsEntry("vendor", "Fiserv-DNA");
    }

    @Test
    void includesStatusUnreachable_whenOutOfService() {
        when(adapter.isCoreSystemAvailable()).thenReturn(false);

        Health health = indicator.health();

        assertThat(health.getDetails()).containsEntry("status", "unreachable");
    }

    @Test
    void includesBridgeModeNote_whenOutOfService() {
        when(adapter.isCoreSystemAvailable()).thenReturn(false);

        Health health = indicator.health();

        assertThat(health.getDetails()).containsKey("note");
        assertThat(health.getDetails().get("note").toString()).contains("Shadow Ledger");
    }
}
