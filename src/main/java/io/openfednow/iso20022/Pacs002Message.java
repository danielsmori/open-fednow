package io.openfednow.iso20022;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
@Schema(description = "ISO 20022 pacs.002.001.10 — Payment Status Report. " +
        "Returned in response to a pacs.008 credit transfer to indicate whether the payment was accepted or rejected.")
public class Pacs002Message {

    /**
     * ISO 20022 transaction status codes used in FedNow.
     */
    @Schema(description = "ISO 20022 transaction status code. " +
            "ACSC = AcceptedSettlementCompleted (payment fully accepted and settled). " +
            "RJCT = Rejected (see rejectReasonCode for the ISO 20022 reason). " +
            "ACSP = AcceptedSettlementInProcess (accepted, settlement pending — provisional response).")
    public enum TransactionStatus {
        /** AcceptedSettlementCompleted — payment fully accepted and settled. */
        ACSC,
        /** Rejected — payment rejected; see rejectReasonCode for details. */
        RJCT,
        /** AcceptedSettlementInProcess — accepted, settlement pending. */
        ACSP
    }

    @Schema(description = "Message identifier for this status report.", example = "STATUS-20240115-001", maxLength = 35)
    private String messageId;

    @Schema(description = "ISO 8601 creation timestamp of this status report.")
    private OffsetDateTime creationDateTime;

    @Schema(description = "EndToEndId from the originating pacs.008.", example = "E2E-20240115-001", maxLength = 35)
    private String originalEndToEndId;

    @Schema(description = "TransactionId from the originating pacs.008.", example = "TXN-20240115-001", maxLength = 35)
    private String originalTransactionId;

    @Schema(description = "Transaction status: ACSC (settled), RJCT (rejected), or ACSP (settlement in process).")
    private TransactionStatus transactionStatus;

    @Schema(description = "ISO 20022 reason code for rejections (e.g. AC01 = invalid account, " +
            "AM04 = insufficient funds, AC04 = closed account, NARR = narrative reason). " +
            "Null when transactionStatus is ACSC or ACSP.",
            example = "AM04", maxLength = 4)
    private String rejectReasonCode;

    @Schema(description = "Human-readable description of the rejection reason. Null when transactionStatus is ACSC or ACSP.",
            example = "Insufficient funds in debtor account")
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
