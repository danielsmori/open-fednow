package io.openfednow.reliability;

import java.time.Instant;

/** Externally visible synthetic payment state; no ISO status is fabricated. */
public record PaymentView(
        String operationId, String institutionId, String businessKey,
        String transactionId, String messageId, String accountId,
        long amountMinor, String state, String serviceStatus,
        String postingStatus, String statusSource, String statusReference,
        String attemptId, long version, Instant createdAt, Instant updatedAt,
        Instant lastInquiryAt, Instant nextInquiryAt, int inquiryCount, String investigationReason) {
}
