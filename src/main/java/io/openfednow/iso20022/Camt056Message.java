package io.openfednow.iso20022;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * ISO 20022 camt.056.001.08 — FI-to-FI Payment Cancellation Request
 *
 * <p>Sent by OpenFedNow to the FedNow Service to request that a previously
 * submitted pacs.008 credit transfer be cancelled before settlement completes.
 * FedNow will respond with a {@link Camt029Message} indicating whether the
 * cancellation was accepted (CNCL), rejected (RJCR), or is pending (PDCR).
 *
 * <p>Cancellation is only possible while the payment is in process. Once
 * FedNow has sent a pacs.002 ACSC (AcceptedSettlementCompleted), a
 * {@link Pacs004Message} payment return must be used instead.
 *
 * <h2>Common Cancellation Reason Codes</h2>
 * <table>
 *   <tr><th>Code</th><th>Meaning</th></tr>
 *   <tr><td>{@code DUPL}</td><td>Duplicate payment — this transfer was already submitted</td></tr>
 *   <tr><td>{@code FRAUD}</td><td>Fraudulent origin — payment was initiated fraudulently</td></tr>
 *   <tr><td>{@code CUST}</td><td>Requested by customer — debtor has requested cancellation</td></tr>
 *   <tr><td>{@code UPAY}</td><td>Undue payment — funds were sent in error</td></tr>
 *   <tr><td>{@code NARR}</td><td>Narrative reason — see {@link #cancellationReasonDescription}</td></tr>
 * </table>
 *
 * @see Pacs008Message
 * @see Camt029Message
 * @see Pacs004Message
 * @see <a href="https://www.iso20022.org/catalogue-messages/iso-20022-messages-archive?search=camt.056">
 *      ISO 20022 camt.056 specification</a>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "ISO 20022 camt.056.001.08 — FI-to-FI Payment Cancellation Request. " +
        "Sent to FedNow to request cancellation of an in-flight pacs.008 credit transfer. " +
        "FedNow responds with a camt.029 Resolution of Investigation.")
public class Camt056Message {

    @Schema(description = "Unique identifier for this cancellation request message.",
            example = "CNCL-20240115-001", maxLength = 35)
    private String messageId;

    @Schema(description = "ISO 8601 creation timestamp of this cancellation request.")
    private OffsetDateTime creationDateTime;

    @Schema(description = "Case identification assigned by the requesting institution. " +
            "Carried unchanged into the camt.029 response so the case can be correlated.",
            example = "CASE-20240115-001", maxLength = 35)
    private String caseId;

    @Schema(description = "MessageId of the original pacs.008 being cancelled.",
            example = "MSG-20240115-001", maxLength = 35)
    private String originalMessageId;

    @Schema(description = "EndToEndId from the original pacs.008.",
            example = "E2E-20240115-001", maxLength = 35)
    private String originalEndToEndId;

    @Schema(description = "TransactionId from the original pacs.008.",
            example = "TXN-20240115-001", maxLength = 35)
    private String originalTransactionId;

    @Schema(description = "Interbank settlement amount from the original pacs.008, " +
            "included for verification by FedNow.", example = "1000.00")
    private BigDecimal originalInterbankSettlementAmount;

    @Schema(description = "Currency of the original settlement amount. FedNow only supports USD.",
            example = "USD")
    private String originalInterbankSettlementCurrency;

    @Schema(description = "ISO 20022 reason code for the cancellation request " +
            "(DUPL, FRAUD, CUST, UPAY, or NARR).",
            example = "DUPL", maxLength = 4)
    private String cancellationReasonCode;

    @Schema(description = "Human-readable explanation of the cancellation reason. " +
            "Required when cancellationReasonCode is NARR.",
            example = "Duplicate of payment E2E-20240114-099 submitted yesterday")
    private String cancellationReasonDescription;

    /**
     * Constructs a camt.056 cancellation request for a previously submitted pacs.008.
     *
     * <p>Copies all identifying fields from the original transfer (message ID,
     * EndToEndId, TransactionId, amount) so FedNow can unambiguously identify
     * which payment is to be cancelled.
     *
     * @param original                     the pacs.008 message to be cancelled
     * @param cancellationReasonCode       ISO 20022 reason code (e.g., "DUPL", "FRAUD", "CUST")
     * @param cancellationReasonDescription human-readable explanation; required for NARR
     * @return a fully populated camt.056 ready for submission to FedNow
     */
    public static Camt056Message forPaymentCancellation(
            Pacs008Message original,
            String cancellationReasonCode,
            String cancellationReasonDescription) {

        return Camt056Message.builder()
                .messageId("CNCL-" + UUID.randomUUID())
                .creationDateTime(OffsetDateTime.now())
                .caseId("CASE-" + original.getTransactionId())
                .originalMessageId(original.getMessageId())
                .originalEndToEndId(original.getEndToEndId())
                .originalTransactionId(original.getTransactionId())
                .originalInterbankSettlementAmount(original.getInterbankSettlementAmount())
                .originalInterbankSettlementCurrency(original.getInterbankSettlementCurrency())
                .cancellationReasonCode(cancellationReasonCode)
                .cancellationReasonDescription(cancellationReasonDescription)
                .build();
    }
}
