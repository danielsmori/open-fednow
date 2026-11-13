package io.openfednow.bridgemode;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Unit tests for {@link BridgeAggregateCapPolicy} — issue #68.
 *
 * <p>The policy must refuse to start with a nonsensical configuration (a risk
 * control that silently misparses its ceiling is worse than none), and must
 * treat "unset" as explicitly disabled rather than defaulting to a number.
 */
class BridgeAggregateCapPolicyTest {

    @Test
    void unsetCapDisablesThePolicy() {
        BridgeAggregateCapPolicy policy = new BridgeAggregateCapPolicy("", 6, true);
        assertThat(policy.isEnabled()).isFalse();
        assertThat(policy.getMaxAggregateSendAmount()).isNull();
        assertThat(policy.getExpectedWindowHours()).isEqualTo(6);
    }

    @Test
    void blankCapDisablesThePolicy() {
        BridgeAggregateCapPolicy policy = new BridgeAggregateCapPolicy("   ", 6, false);
        assertThat(policy.isEnabled()).isFalse();
    }

    @Test
    void configuredCapParsesToAmountAndCents() {
        BridgeAggregateCapPolicy policy = new BridgeAggregateCapPolicy("2000.00", 6, true);
        assertThat(policy.isEnabled()).isTrue();
        assertThat(policy.getMaxAggregateSendAmount()).isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(policy.getMaxAggregateSendCents()).isEqualTo(200_000L);
    }

    @Test
    void capWithSubCentPrecisionRoundsHalfUpLikeTheLedger() {
        // ShadowLedger stores integer cents with HALF_UP; the policy must agree
        // with the store it is enforced against.
        BridgeAggregateCapPolicy policy = new BridgeAggregateCapPolicy("0.005", 6, true);
        assertThat(policy.getMaxAggregateSendCents()).isEqualTo(1L);
    }

    @Test
    void capThatRoundsToZeroCentsIsRejected() {
        // 0.004 is positive but would enforce a 0-cent ceiling: every send AM04
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BridgeAggregateCapPolicy("0.004", 6, true))
                .withMessageContaining("zero cents");
    }

    @Test
    void capTooLargeToEnforceIsRejectedAtStartupNotPerSend() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BridgeAggregateCapPolicy("10000000000000000000", 6, true))
                .withMessageContaining("too large");
    }

    @Test
    void zeroCapIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BridgeAggregateCapPolicy("0", 6, true))
                .withMessageContaining("max-aggregate-send-amount");
    }

    @Test
    void negativeCapIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BridgeAggregateCapPolicy("-100", 6, true))
                .withMessageContaining("max-aggregate-send-amount");
    }

    @Test
    void unparseableCapIsRejectedNotIgnored() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BridgeAggregateCapPolicy("2,000", 6, true))
                .withMessageContaining("not a valid amount");
    }

    @Test
    void nonPositiveWindowHoursAreRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BridgeAggregateCapPolicy("2000", 0, true))
                .withMessageContaining("expected-window-hours");
    }

    @Test
    void centsAccessorRefusesWhenDisabled() {
        BridgeAggregateCapPolicy policy = new BridgeAggregateCapPolicy("", 6, true);
        assertThatIllegalStateException()
                .isThrownBy(policy::getMaxAggregateSendCents);
    }
}
