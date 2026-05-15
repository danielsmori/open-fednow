package io.openfednow.acl.adapters.fiserv;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for the Fiserv Communicator Open transaction posting endpoint.
 *
 * <p>Field names and structure match the Fiserv Communicator Open REST API
 * specification (docs.fiserv.dev). The {@code amount} field uses Fiserv's
 * fixed-point encoding: a cent-denominated integer string produced by
 * {@link FiservAmountEncoder#encode(java.math.BigDecimal)}.
 *
 * <p>The {@code paymentType} field is always {@code "INSTANT"} for FedNow
 * transactions to indicate real-time settlement is required.
 *
 * @see <a href="https://docs.fiserv.dev">Fiserv Developer Documentation</a>
 */
public record FiservTransactionRequest(

        /** Unique transaction identifier from the pacs.008. */
        @JsonProperty("transactionId")
        String transactionId,

        /** End-to-end identifier carried unchanged from pacs.008 for deduplication. */
        @JsonProperty("endToEndId")
        String endToEndId,

        /** Settlement amount as a Fiserv fixed-point cent string (e.g. "100050" = $1,000.50). */
        @JsonProperty("amount")
        String amount,

        /** ISO 4217 currency code. FedNow only supports "USD". */
        @JsonProperty("currency")
        String currency,

        /** ABA routing number of the debtor (sending) institution. */
        @JsonProperty("debtorRoutingNum")
        String debtorRoutingNum,

        /** ABA routing number of the creditor (receiving) institution. */
        @JsonProperty("creditorRoutingNum")
        String creditorRoutingNum,

        /** Debtor account number at the sending institution. */
        @JsonProperty("debtorAccount")
        String debtorAccount,

        /** Creditor account number at the receiving institution. */
        @JsonProperty("creditorAccount")
        String creditorAccount,

        /** Name of the debtor (payer). */
        @JsonProperty("debtorName")
        String debtorName,

        /** Name of the creditor (payee). */
        @JsonProperty("creditorName")
        String creditorName,

        /** Optional remittance information / payment memo. */
        @JsonProperty("remittanceInfo")
        String remittanceInfo,

        /** Payment type — always "INSTANT" for FedNow real-time transfers. */
        @JsonProperty("paymentType")
        String paymentType
) {}
