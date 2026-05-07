package io.openfednow.iso20022;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
@Schema(description = "ISO 20022 pacs.008.001.08 — FI-to-FI Customer Credit Transfer. " +
        "The primary payment message used by FedNow for real-time credit transfers between financial institutions.")
public class Pacs008Message {

    @Schema(description = "Unique identifier for this message instance.", example = "MSG-20240115-001", maxLength = 35)
    private String messageId;

    @Schema(description = "ISO 8601 creation timestamp of the message.")
    private OffsetDateTime creationDateTime;

    @Schema(description = "Number of individual transactions in this message. FedNow always submits exactly 1.", example = "1")
    private int numberOfTransactions;

    @Schema(description = "End-to-end identification assigned by the originating party and carried unchanged through FedNow. " +
            "Used as the deduplication key by IdempotencyService.", example = "E2E-20240115-001", maxLength = 35)
    private String endToEndId;

    @Schema(description = "Transaction identification assigned by the instructing agent.", example = "TXN-20240115-001", maxLength = 35)
    private String transactionId;

    @Schema(description = "Interbank settlement amount in USD. Must be greater than zero.", example = "1000.00")
    private BigDecimal interbankSettlementAmount;

    @Schema(description = "Interbank settlement currency. FedNow only supports USD.", example = "USD")
    private String interbankSettlementCurrency;

    @Schema(description = "ABA routing number of the debtor's (sending) financial institution.", example = "021000021", minLength = 9, maxLength = 9)
    private String debtorAgentRoutingNumber;

    @Schema(description = "ABA routing number of the creditor's (receiving) financial institution.", example = "026009593", minLength = 9, maxLength = 9)
    private String creditorAgentRoutingNumber;

    @Schema(description = "Debtor account number at the sending institution.", example = "123456789", maxLength = 34)
    private String debtorAccountNumber;

    @Schema(description = "Creditor account number at the receiving institution.", example = "987654321", maxLength = 34)
    private String creditorAccountNumber;

    @Schema(description = "Name of the debtor (payer).", example = "Alice Smith")
    private String debtorName;

    @Schema(description = "Name of the creditor (payee).", example = "Bob Jones")
    private String creditorName;

    @Schema(description = "Remittance information / payment memo.", example = "Invoice #12345")
    private String remittanceInformation;
}
