package io.openfednow.acl.core;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Assessment of a particular core/environment, not a blanket vendor claim. */
public record OutboundCoreGate(String product, String environment,
                               Map<Capability, Evidence> evidence) {
    public enum Capability {
        ATOMIC_RESERVATION, RESERVATION_LOOKUP, RESERVATION_EXPIRY,
        HOLD_CONSUMING_POST, POSTING_LOOKUP, OTHER_CHANNEL_COORDINATION
    }

    public enum Level {
        UNSUPPORTED, SPECIFICATION_SUPPORTED, LOCALLY_TESTED,
        PROVIDER_MOCK_TESTED, PROVIDER_TEST_CORE_TESTED, INSTITUTION_VALIDATED
    }

    public record Evidence(Level level, String source, Instant observedAt) {
        public Evidence {
            Objects.requireNonNull(level);
            if (level != Level.UNSUPPORTED && (source == null || source.isBlank()))
                throw new IllegalArgumentException("supported evidence needs a source");
        }
    }

    public OutboundCoreGate {
        Objects.requireNonNull(product);
        Objects.requireNonNull(environment);
        evidence = Map.copyOf(evidence);
    }

    /** Local core plus simulated rail only; production sends are never authorized here. */
    public boolean permitsBoundedNonProductionExperiment(ReliableOutboundCorePort.Outcome hold,
                                                          Instant now) {
        if (hold.status() != ReliableOutboundCorePort.Status.CONFIRMED
                || hold.expiresAt() == null || !hold.expiresAt().isAfter(now)) return false;
        for (Capability capability : Capability.values()) {
            Evidence proof = evidence.get(capability);
            Level minimum = capability == Capability.OTHER_CHANNEL_COORDINATION
                    ? Level.INSTITUTION_VALIDATED : Level.PROVIDER_TEST_CORE_TESTED;
            if (proof == null || proof.level().ordinal() < minimum.ordinal()) return false;
        }
        return true;
    }

    public boolean permitsProductionSend() { return false; }
}
