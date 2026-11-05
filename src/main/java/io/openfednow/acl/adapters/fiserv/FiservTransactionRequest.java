package io.openfednow.acl.adapters.fiserv;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for the legacy local transaction fixture. Its fields and amount
 * encoding have not been mapped to a Banking Hub API specification.
 */
public record FiservTransactionRequest(

        /** Unique transaction identifier from the pacs.008. */
        @JsonProperty("transactionId")
        String transactionId,

        /** End-to-end identifier carried from pacs.008; deduplication is unverified. */
        @JsonProperty("endToEndId")
        String endToEndId,

        /** Settlement amount as a fixture cent string (e.g. "100050" = $1,000.50). */
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

        /** Locally modeled payment type; no provider settlement semantics are implied. */
        @JsonProperty("paymentType")
        String paymentType
) {}
