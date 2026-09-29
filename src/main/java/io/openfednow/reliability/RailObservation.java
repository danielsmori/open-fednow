package io.openfednow.reliability;

/** Synthetic rail observation. Missing or ambiguous evidence never releases funds. */
public record RailObservation(String transactionId, String businessKey,
                              String messageId, String status, String source,
                              String reference) {
    public boolean finalPaymentStatus() {
        return "SETTLED".equals(status) || "REJECTED".equals(status);
    }
}
