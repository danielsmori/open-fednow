package io.openfednow.reliability;

import io.openfednow.iso20022.Pacs008Message;

/** No implicit live or sandbox send when no independent simulator is configured. */
public class UnavailableRailPort implements ReliabilityRailPort {
    @Override
    public RailObservation submit(Pacs008Message message, String attemptId) {
        throw new IllegalStateException("Synthetic rail simulator unavailable");
    }

    @Override
    public RailObservation inquire(PaymentView payment) {
        throw new IllegalStateException("Synthetic rail simulator unavailable");
    }
}
