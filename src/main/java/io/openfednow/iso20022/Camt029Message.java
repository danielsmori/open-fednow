package io.openfednow.iso20022;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * ISO 20022 camt.029.001.09 — Resolution of Investigation
 *
 * <p>Received from the FedNow Service in response to a {@link Camt056Message}
 * payment cancellation request. Indicates whether the cancellation was
 * accepted, rejected, or is still pending.
 *
 * <p>The {@link #caseId} and original payment references are carried through
 * from the camt.056 so the response can be correlated with the request.
 *
 * <h2>Resolution Status</h2>
 * <table>
 *   <tr><th>Status</th><th>Meaning</th><th>Next Action</th></tr>
 *   <tr><td>{@code CNCL}</td><td>Payment cancelled successfully</td>
 *       <td>Saga can be marked completed; no funds will settle</td></tr>
 *   <tr><td>{@code RJCR}</td><td>Cancellation rejected — see {@link #rejectionReasonCode}</td>
 *       <td>If payment settled, initiate {@link Pacs004Message} return instead</td></tr>
 *   <tr><td>{@code PDCR}</td><td>Pending — investigation ongoing, final resolution not yet reached</td>
 *       <td>Wait for a follow-up camt.029; do not initiate a return yet</td></tr>
 * </table>
 *
 * <h2>Common Rejection Reason Codes (RJCR)</h2>
 * <table>
 *   <tr><th>Code</th><th>Meaning</th></tr>
 *   <tr><td>{@code ARDT}</td><td>Already returned/transferred — settlement completed before request arrived</td></tr>
 *   <tr><td>{@code NOAS}</td><td>No answer from next agent — downstream institution did not respond</td></tr>
 *   <tr><td>{@code NOOR}</td><td>No original transaction received — FedNow has no record of the payment</td></tr>
 *   <tr><td>{@code LEGL}</td><td>Legal decision — cancellation blocked by regulatory or legal hold</td></tr>
 *   <tr><td>{@code NARR}</td><td>Narrative reason — see {@link #rejectionReasonDescription}</td></tr>
 * </table>
 *
 * @see Camt056Message
 * @see Pacs004Message
 * @see <a href="https://www.iso20022.org/catalogue-messages/iso-20022-messages-archive?search=camt.029">
 *      ISO 20022 camt.029 specification</a>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "ISO 20022 camt.029.001.09 — Resolution of Investigation. " +
        "Received from FedNow in response to a camt.056 cancellation request. " +
        "Indicates whether the cancellation was accepted (CNCL), rejected (RJCR), or is pending (PDCR).")
public class Camt029Message {

    /**
     * Outcome of the payment cancellation investigation.
     */
    @Schema(description = "Outcome of the cancellation investigation. " +
            "CNCL = cancelled successfully. " +
            "RJCR = cancellation rejected (see rejectionReasonCode). " +
            "PDCR = pending, investigation ongoing.")
    public enum ResolutionStatus {
        /** Cancelled — the payment was successfully cancelled before settlement. */
        CNCL,
        /** Rejected — the cancellation request was not accepted; see rejectionReasonCode. */
        RJCR,
        /** Pending — the investigation is ongoing; a follow-up camt.029 will follow. */
        PDCR
    }

    @Schema(description = "Unique identifier for this resolution message.",
            example = "RES-20240115-001", maxLength = 35)
    private String messageId;

    @Schema(description = "ISO 8601 creation timestamp of this resolution message.")
    private OffsetDateTime creationDateTime;

    @Schema(description = "Case identification from the original camt.056 request. " +
            "Used to correlate this resolution with the cancellation request that triggered it.",
            example = "CASE-20240115-001", maxLength = 35)
    private String caseId;

    @Schema(description = "MessageId of the camt.056 cancellation request this message resolves.",
            example = "CNCL-20240115-001", maxLength = 35)
    private String originalCancellationMessageId;

    @Schema(description = "EndToEndId from the original pacs.008 payment.",
            example = "E2E-20240115-001", maxLength = 35)
    private String originalEndToEndId;

    @Schema(description = "TransactionId from the original pacs.008 payment.",
            example = "TXN-20240115-001", maxLength = 35)
    private String originalTransactionId;

    @Schema(description = "Outcome of the cancellation investigation: CNCL, RJCR, or PDCR.")
    private ResolutionStatus resolutionStatus;

    @Schema(description = "ISO 20022 reason code when resolutionStatus is RJCR " +
            "(e.g. ARDT = already settled, NOAS = no answer from agent, LEGL = legal hold). " +
            "Null when resolutionStatus is CNCL or PDCR.",
            example = "ARDT", maxLength = 4)
    private String rejectionReasonCode;

    @Schema(description = "Human-readable explanation of the rejection reason. " +
            "Null when resolutionStatus is CNCL or PDCR.",
            example = "Payment settled before cancellation request was processed")
    private String rejectionReasonDescription;

    /**
     * Constructs a camt.029 confirming that the cancellation request was accepted
     * and the payment has been cancelled.
     *
     * @param cancellationRequest the camt.056 that is being resolved
     * @return a camt.029 with {@link ResolutionStatus#CNCL}
     */
    public static Camt029Message cancelled(Camt056Message cancellationRequest) {
        return Camt029Message.builder()
                .messageId("RES-" + UUID.randomUUID())
                .creationDateTime(OffsetDateTime.now())
                .caseId(cancellationRequest.getCaseId())
                .originalCancellationMessageId(cancellationRequest.getMessageId())
                .originalEndToEndId(cancellationRequest.getOriginalEndToEndId())
                .originalTransactionId(cancellationRequest.getOriginalTransactionId())
                .resolutionStatus(ResolutionStatus.CNCL)
                .build();
    }

    /**
     * Constructs a camt.029 indicating that the cancellation request was rejected.
     *
     * @param cancellationRequest      the camt.056 that is being resolved
     * @param rejectionReasonCode      ISO 20022 reason code (e.g., "ARDT", "NOAS", "LEGL", "NARR")
     * @param rejectionReasonDescription human-readable explanation; required for NARR
     * @return a camt.029 with {@link ResolutionStatus#RJCR}
     */
    public static Camt029Message rejected(
            Camt056Message cancellationRequest,
            String rejectionReasonCode,
            String rejectionReasonDescription) {

        return Camt029Message.builder()
                .messageId("RES-" + UUID.randomUUID())
                .creationDateTime(OffsetDateTime.now())
                .caseId(cancellationRequest.getCaseId())
                .originalCancellationMessageId(cancellationRequest.getMessageId())
                .originalEndToEndId(cancellationRequest.getOriginalEndToEndId())
                .originalTransactionId(cancellationRequest.getOriginalTransactionId())
                .resolutionStatus(ResolutionStatus.RJCR)
                .rejectionReasonCode(rejectionReasonCode)
                .rejectionReasonDescription(rejectionReasonDescription)
                .build();
    }

    /**
     * Constructs a camt.029 indicating the investigation is still pending.
     * A follow-up camt.029 with a final status (CNCL or RJCR) will be sent later.
     *
     * @param cancellationRequest the camt.056 that is being acknowledged
     * @return a camt.029 with {@link ResolutionStatus#PDCR}
     */
    public static Camt029Message pending(Camt056Message cancellationRequest) {
        return Camt029Message.builder()
                .messageId("RES-" + UUID.randomUUID())
                .creationDateTime(OffsetDateTime.now())
                .caseId(cancellationRequest.getCaseId())
                .originalCancellationMessageId(cancellationRequest.getMessageId())
                .originalEndToEndId(cancellationRequest.getOriginalEndToEndId())
                .originalTransactionId(cancellationRequest.getOriginalTransactionId())
                .resolutionStatus(ResolutionStatus.PDCR)
                .build();
    }
}
