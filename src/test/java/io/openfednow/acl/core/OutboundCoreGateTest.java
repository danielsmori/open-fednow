package io.openfednow.acl.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundCoreGateTest {
    private final ReliableOutboundCorePort.Identity identity =
            new ReliableOutboundCorePort.Identity("test-tenant", "synthetic-account", "operation-1");
    private final Instant now = Instant.parse("2026-11-05T12:00:00Z");

    @Test
    void unsupportedPortBlocksEveryMutationAndProvidesNoBalance() {
        var port = new UnsupportedOutboundCorePort();
        var mutation = new ReliableOutboundCorePort.Mutation(identity, new BigDecimal("10.00"), "USD", null);
        assertThat(port.observeBalance(identity)).isEmpty();
        assertThat(port.reserve(mutation).status()).isEqualTo(ReliableOutboundCorePort.Status.UNSUPPORTED);
        assertThat(port.postReservedDebit(mutation).status()).isEqualTo(ReliableOutboundCorePort.Status.UNSUPPORTED);
        assertThat(port.lookupReservation(identity).status()).isEqualTo(ReliableOutboundCorePort.Status.UNSUPPORTED);
        assertThat(port.lookupPosting(identity).status()).isEqualTo(ReliableOutboundCorePort.Status.UNSUPPORTED);
        assertThat(port.releaseReservation(identity, "hold-1").status()).isEqualTo(ReliableOutboundCorePort.Status.UNSUPPORTED);
        assertThat(port.reversePosting(identity, "post-1").status()).isEqualTo(ReliableOutboundCorePort.Status.UNSUPPORTED);
    }

    @Test
    void unknownOrExpiredHoldCannotAdvanceEvenWithEvidence() {
        var gate = fullyAssessed();
        var unknown = new ReliableOutboundCorePort.Outcome(ReliableOutboundCorePort.Status.UNKNOWN,
                identity, null, null, "response lost");
        var expired = confirmed(now);
        assertThat(gate.permitsBoundedNonProductionExperiment(unknown, now)).isFalse();
        assertThat(gate.permitsBoundedNonProductionExperiment(expired, now)).isFalse();
        assertThat(gate.permitsProductionSend()).isFalse();
    }

    @Test
    void missingInstitutionChannelEvidenceBlocksSyntheticRailExperiment() {
        var proof = new EnumMap<OutboundCoreGate.Capability, OutboundCoreGate.Evidence>(OutboundCoreGate.Capability.class);
        for (var capability : OutboundCoreGate.Capability.values())
            proof.put(capability, new OutboundCoreGate.Evidence(
                    OutboundCoreGate.Level.PROVIDER_TEST_CORE_TESTED, "test observation", now));
        var gate = new OutboundCoreGate("Premier", "shared sandbox", proof);
        assertThat(gate.permitsBoundedNonProductionExperiment(confirmed(now.plusSeconds(60)), now)).isFalse();
    }

    @Test
    void completeConstructedEvidenceOnlyAllowsBoundedExperiment() {
        var gate = fullyAssessed();
        assertThat(gate.permitsBoundedNonProductionExperiment(confirmed(now.plusSeconds(60)), now)).isTrue();
        assertThat(gate.permitsProductionSend()).isFalse();
    }

    @Test
    void confirmedMutationRequiresProviderIdentityAndPositiveAmount() {
        assertThatThrownBy(() -> new ReliableOutboundCorePort.Outcome(
                ReliableOutboundCorePort.Status.CONFIRMED, identity, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReliableOutboundCorePort.Mutation(
                identity, BigDecimal.ZERO, "USD", null)).isInstanceOf(IllegalArgumentException.class);
    }

    private ReliableOutboundCorePort.Outcome confirmed(Instant expiry) {
        return new ReliableOutboundCorePort.Outcome(ReliableOutboundCorePort.Status.CONFIRMED,
                identity, "provider-hold-1", expiry, null);
    }

    private OutboundCoreGate fullyAssessed() {
        var proof = new EnumMap<OutboundCoreGate.Capability, OutboundCoreGate.Evidence>(OutboundCoreGate.Capability.class);
        for (var capability : OutboundCoreGate.Capability.values())
            proof.put(capability, new OutboundCoreGate.Evidence(
                    capability == OutboundCoreGate.Capability.OTHER_CHANNEL_COORDINATION
                            ? OutboundCoreGate.Level.INSTITUTION_VALIDATED
                            : OutboundCoreGate.Level.PROVIDER_TEST_CORE_TESTED,
                    "independent evidence", now));
        return new OutboundCoreGate("Premier", "dedicated test core", proof);
    }
}
