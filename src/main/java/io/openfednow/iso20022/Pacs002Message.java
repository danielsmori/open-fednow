package io.openfednow.iso20022;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.OffsetDateTime;

/**
 * ISO 20022 pacs.002.001.10 — Payment Status Report
 *
 * <p>Returned by OpenFedNow to FedNow in response to a pacs.008 credit transfer.
 * Indicates whether the payment was accepted (ACSC) or rejected (RJCT),
 * and provides the reason code in case of rejection.
 *
 * <p>FedNow requires this response within 20 seconds of the pacs.008 submission.
 * The SyncAsyncBridge ensures this deadline is met even when core banking
 * processing takes longer.
 *
 * @see <a href="https://www.iso20022.org/catalogue-messages/iso-20022-messages-archive?search=pacs.002">
 *      ISO 20022 pacs.002 specification</a>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Pacs002Message {

    /**
     * ISO 20022 transaction status codes used in FedNow.
     */
    public enum TransactionStatus {
        /** AcceptedSettlementCompleted — payment fully accepted and settled. */
        ACSC,
        /** Rejected — payment rejected; see rejectReasonCode for details. */
        RJCT,
        /** AcceptedSettlementInProcess — accepted, settlement pending. */
        ACSP
    }

    /** Message identification for this status report. */
    private String messageId;

    /** Creation date and time of this status report. */
    private OffsetDateTime creationDateTime;

    /** Original end-to-end ID from the pacs.008 this report responds to. */
    private String originalEndToEndId;

    /** Original transaction ID from the pacs.008 this report responds to. */
    private String originalTransactionId;

    /** Transaction status (ACSC, RJCT, or ACSP). */
    private TransactionStatus transactionStatus;

    /**
     * ISO 20022 reason code for rejections (e.g., "AC01" for invalid account,
     * "AM04" for insufficient funds, "NARR" for narrative reason).
     * Null if transactionStatus is ACSC.
     */
    private String rejectReasonCode;

    /** Human-readable description of the rejection reason. */
    private String rejectReasonDescription;

    /** Convenience factory for an acceptance response. */
    public static Pacs002Message accepted(String originalEndToEndId, String originalTransactionId) {
        return Pacs002Message.builder()
                .originalEndToEndId(originalEndToEndId)
                .originalTransactionId(originalTransactionId)
                .transactionStatus(TransactionStatus.ACSC)
                .creationDateTime(OffsetDateTime.now())
                .build();
    }

    /** Convenience factory for a rejection response. */
    public static Pacs002Message rejected(String originalEndToEndId, String originalTransactionId,
                                           String reasonCode, String reasonDescription) {
        return Pacs002Message.builder()
                .originalEndToEndId(originalEndToEndId)
                .originalTransactionId(originalTransactionId)
                .transactionStatus(TransactionStatus.RJCT)
                .rejectReasonCode(reasonCode)
                .rejectReasonDescription(reasonDescription)
                .creationDateTime(OffsetDateTime.now())
                .build();
    }
}
