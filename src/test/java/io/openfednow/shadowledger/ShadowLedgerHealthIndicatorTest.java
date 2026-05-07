package io.openfednow.shadowledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShadowLedgerHealthIndicatorTest {

    private AvailabilityBridge bridge;
    private ShadowLedgerHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        bridge = mock(AvailabilityBridge.class);
        indicator = new ShadowLedgerHealthIndicator(bridge);
    }

    @Test
    void up_whenNotInBridgeMode() {
        when(bridge.isInBridgeMode()).thenReturn(false);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void modeIsOnline_whenNotInBridgeMode() {
        when(bridge.isInBridgeMode()).thenReturn(false);

        Health health = indicator.health();

        assertThat(health.getDetails()).containsEntry("mode", "ONLINE");
        assertThat(health.getDetails()).containsEntry("bridgeMode", false);
    }

    @Test
    void descriptionMentionsMirroring_whenOnline() {
        when(bridge.isInBridgeMode()).thenReturn(false);

        Health health = indicator.health();

        assertThat(health.getDetails().get("description").toString())
                .contains("mirroring");
    }

    @Test
    void upEvenWhenInBridgeMode() {
        // Bridge mode is the designed fallback, not a failure — Shadow Ledger
        // is functioning correctly and the institution remains available to FedNow.
        when(bridge.isInBridgeMode()).thenReturn(true);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void modeIsBridge_whenInBridgeMode() {
        when(bridge.isInBridgeMode()).thenReturn(true);

        Health health = indicator.health();

        assertThat(health.getDetails()).containsEntry("mode", "BRIDGE");
        assertThat(health.getDetails()).containsEntry("bridgeMode", true);
    }

    @Test
    void descriptionMentionsQueueing_whenInBridgeMode() {
        when(bridge.isInBridgeMode()).thenReturn(true);

        Health health = indicator.health();

        assertThat(health.getDetails().get("description").toString())
                .contains("queued");
    }
}
