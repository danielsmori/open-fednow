package io.openfednow.iso20022;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * ISO 20022 pacs.008.001.08 — FI-to-FI Customer Credit Transfer
 *
 * <p>Represents the primary payment message used by FedNow for credit transfers.
 * This is the message type sent when a financial institution initiates or
 * receives a real-time payment through the FedNow network.
 *
 * <p>This model captures the fields required for FedNow processing. The full
 * ISO 20022 pacs.008 schema contains additional optional fields documented in
 * docs/iso20022-mapping.md.
 *
 * @see <a href="https://www.iso20022.org/catalogue-messages/iso-20022-messages-archive?search=pacs.008">
 *      ISO 20022 pacs.008 specification</a>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Pacs008Message {

    /** Message identification — unique identifier for this message instance. */
    private String messageId;

    /** Creation date and time of the message. */
    private OffsetDateTime creationDateTime;

    /** Number of individual transactions in this message (FedNow: always 1). */
    private int numberOfTransactions;

    /** End-to-end identification — assigned by the originating party, carried through. */
    private String endToEndId;

    /** Transaction identification — unique ID assigned by the instructing agent. */
    private String transactionId;

    /** Interbank settlement amount in USD. */
    private BigDecimal interbankSettlementAmount;

    /** Interbank settlement currency (FedNow: always "USD"). */
    private String interbankSettlementCurrency;

    /** ABA routing number of the debtor's financial institution. */
    private String debtorAgentRoutingNumber;

    /** ABA routing number of the creditor's financial institution. */
    private String creditorAgentRoutingNumber;

    /** Debtor account number. */
    private String debtorAccountNumber;

    /** Creditor account number. */
    private String creditorAccountNumber;

    /** Debtor name. */
    private String debtorName;

    /** Creditor name. */
    private String creditorName;

    /** Remittance information (payment memo). */
    private String remittanceInformation;
}
