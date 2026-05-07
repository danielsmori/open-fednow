package io.openfednow.iso20022;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * ISO 20022 pacs.004.001.09 — Payment Return
 *
 * <p>Sent by OpenFedNow to FedNow when the saga compensation path requires
 * reversing a previously confirmed credit transfer. This occurs in the
 * following failure scenario:
 *
 * <ol>
 *   <li>pacs.008 credit transfer is received from FedNow</li>
 *   <li>OpenFedNow sends pacs.002 ACSC (acceptance) to FedNow</li>
 *   <li>Core banking system subsequently rejects the transaction</li>
 *   <li>SagaOrchestrator triggers compensation: this pacs.004 is sent to
 *       FedNow to return the funds to the originating institution</li>
 * </ol>
 *
 * <p>In the return message, roles are reversed relative to the original pacs.008:
 * the original <em>creditor</em> agent becomes the <em>returning</em> (debtor)
 * agent, and the original <em>debtor</em> agent becomes the <em>receiving</em>
 * (creditor) agent.
 *
 * <h2>Common Return Reason Codes</h2>
 * <table>
 *   <tr><th>Code</th><th>Meaning</th></tr>
 *   <tr><td>{@code AC04}</td><td>Closed account — account was closed after original transfer</td></tr>
 *   <tr><td>{@code AM04}</td><td>Insufficient funds — core rejected on final posting</td></tr>
 *   <tr><td>{@code FOCR}</td><td>Following cancellation request</td></tr>
 *   <tr><td>{@code DUPL}</td><td>Duplicate payment detected</td></tr>
 *   <tr><td>{@code NARR}</td><td>Narrative reason — see {@link #returnReasonDescription}</td></tr>
 * </table>
 *
 * @see Pacs008Message
 * @see Pacs002Message
 * @see <a href="https://www.iso20022.org/catalogue-messages/iso-20022-messages-archive?search=pacs.004">
 *      ISO 20022 pacs.004 specification</a>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Pacs004Message {

    /** Message identification — unique identifier for this return message. */
    private String messageId;

    /** Creation date and time of this return message. */
    private OffsetDateTime creationDateTime;

    /**
     * Return identification — unique ID assigned by the returning institution
     * for this specific return transaction.
     */
    private String returnId;

    /** Message ID of the original pacs.008 that is being returned. */
    private String originalMessageId;

    /** End-to-end identification carried through from the original pacs.008. */
    private String originalEndToEndId;

    /** Transaction identification from the original pacs.008. */
    private String originalTransactionId;

    /** Amount being returned, equal to the original interbank settlement amount. */
    private BigDecimal returnedAmount;

    /** Currency of the returned amount (FedNow: always "USD"). */
    private String returnedAmountCurrency;

    /**
     * ISO 20022 return reason code explaining why the funds are being returned.
     * Common codes: AC04, AM04, FOCR, DUPL, NARR.
     */
    private String returnReasonCode;

    /**
     * Human-readable description of the return reason.
     * Required when {@link #returnReasonCode} is {@code NARR}.
     */
    private String returnReasonDescription;

    /**
     * ABA routing number of the institution returning the funds.
     * This is the <em>original creditor agent</em> from the pacs.008 — the
     * institution that received the funds and is now sending them back.
     */
    private String returningAgentRoutingNumber;

    /**
     * ABA routing number of the institution receiving the returned funds.
     * This is the <em>original debtor agent</em> from the pacs.008 — the
     * institution that originally sent the funds.
     */
    private String receivingAgentRoutingNumber;

    /**
     * Constructs a pacs.004 return message for the saga compensation path.
     *
     * <p>Derives all original-message references and amounts directly from
     * the pacs.008 that was previously accepted, reversing the debtor/creditor
     * agent roles for the return direction.
     *
     * @param original              the pacs.008 message being reversed
     * @param returnReasonCode      ISO 20022 reason code (e.g., "AM04", "AC04", "NARR")
     * @param returnReasonDescription human-readable explanation; required for NARR
     * @return a fully populated pacs.004 ready for submission to FedNow
     */
    public static Pacs004Message forSagaCompensation(
            Pacs008Message original,
            String returnReasonCode,
            String returnReasonDescription) {

        return Pacs004Message.builder()
                .messageId("RTN-" + UUID.randomUUID())
                .creationDateTime(OffsetDateTime.now())
                .returnId("RTNID-" + original.getTransactionId())
                .originalMessageId(original.getMessageId())
                .originalEndToEndId(original.getEndToEndId())
                .originalTransactionId(original.getTransactionId())
                .returnedAmount(original.getInterbankSettlementAmount())
                .returnedAmountCurrency(original.getInterbankSettlementCurrency())
                .returnReasonCode(returnReasonCode)
                .returnReasonDescription(returnReasonDescription)
                // Roles are reversed: original creditor returns funds to original debtor
                .returningAgentRoutingNumber(original.getCreditorAgentRoutingNumber())
                .receivingAgentRoutingNumber(original.getDebtorAgentRoutingNumber())
                .build();
    }
}
