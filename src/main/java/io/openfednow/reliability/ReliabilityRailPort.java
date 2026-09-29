package io.openfednow.reliability;

import io.openfednow.iso20022.Pacs008Message;

/** Synthetic protocol only. A live adapter requires separate restricted specifications. */
public interface ReliabilityRailPort {
    RailObservation submit(Pacs008Message message, String attemptId);

    RailObservation inquire(PaymentView payment);
}
