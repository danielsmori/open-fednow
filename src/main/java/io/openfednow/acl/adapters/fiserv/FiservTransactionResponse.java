package io.openfednow.acl.adapters.fiserv;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body from the legacy local transaction fixture. The statuses and
 * reason codes have not been verified for Banking Hub; fixture acceptance is
 * not proof of an external posting.
 */
public record FiservTransactionResponse(

        /** Transaction outcome: "APPROVED", "REJECTED", or "PENDING". */
        @JsonProperty("status")
        String status,

        /** Locally modeled reference number. */
        @JsonProperty("transactionRef")
        String transactionRef,

        /** Locally modeled rejection code (e.g. "INSF", "INVLD_ACCT"). */
        @JsonProperty("reasonCode")
        String reasonCode,

        /** Human-readable fixture message. */
        @JsonProperty("message")
        String message
) {}
