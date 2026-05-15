package io.openfednow.acl.adapters.fis;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for the FIS IBS (Internet Banking Solution) transaction posting endpoint.
 *
 * <p>FIS IBS uses abbreviated EFX-style field names (e.g. {@code transId}, {@code availBal})
 * and transmits monetary amounts as plain decimal strings ({@code "1000.50"}) rather than
 * the fixed-point cent integers used by Fiserv ({@code "100050"}). This distinction is
 * handled by the adapter so that the rest of the system works exclusively with
 * {@link java.math.BigDecimal} values.
 *
 * <p>The {@code txnType} field is always {@code "INSTANT_CREDIT"} for FedNow inbound
 * credit transfers, indicating real-time settlement is required.
 *
 * @see <a href="https://codeconnect.fisglobal.com">FIS Code Connect Developer Portal</a>
 */
public record FisTransactionRequest(

        /** Unique transaction identifier from the pacs.008. */
        @JsonProperty("transId")
        String transId,

        /** End-to-end identifier carried unchanged from pacs.008 for deduplication. */
        @JsonProperty("endToEndId")
        String endToEndId,

        /** Settlement amount as a decimal string (e.g. {@code "1000.50"}). */
        @JsonProperty("amount")
        String amount,

        /** ISO 4217 currency code. FedNow supports USD only. */
        @JsonProperty("currency")
        String currency,

        /** ABA routing number of the debtor (sending) institution. */
        @JsonProperty("debitRtgNum")
        String debitRtgNum,

        /** ABA routing number of the creditor (receiving) institution. */
        @JsonProperty("creditRtgNum")
        String creditRtgNum,

        /** Debtor account number at the sending institution. */
        @JsonProperty("debitAcctId")
        String debitAcctId,

        /** Creditor account number at the receiving institution. */
        @JsonProperty("creditAcctId")
        String creditAcctId,

        /** Name of the debtor (payer). */
        @JsonProperty("debitName")
        String debitName,

        /** Name of the creditor (payee). */
        @JsonProperty("creditName")
        String creditName,

        /** Optional remittance information / payment memo. */
        @JsonProperty("memo")
        String memo,

        /** Transaction type — always {@code "INSTANT_CREDIT"} for FedNow transfers. */
        @JsonProperty("txnType")
        String txnType
) {}
