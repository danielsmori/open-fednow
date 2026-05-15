package io.openfednow.acl.adapters.fiserv;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body from the Fiserv Communicator Open transaction posting endpoint.
 *
 * <p>The {@code status} field indicates the outcome:
 * <ul>
 *   <li>{@code "APPROVED"} — transaction accepted and posted by the core system</li>
 *   <li>{@code "REJECTED"} — transaction rejected; see {@code reasonCode} for details</li>
 *   <li>{@code "PENDING"}  — transaction accepted asynchronously; a webhook or
 *       polling call is required to retrieve the final outcome</li>
 * </ul>
 *
 * <p>When {@code status} is {@code "REJECTED"}, the {@code reasonCode} field contains
 * a Fiserv-specific code that is mapped to an ISO 20022 reason code by
 * {@link FiservReasonCodeMapper}.
 */
public record FiservTransactionResponse(

        /** Transaction outcome: "APPROVED", "REJECTED", or "PENDING". */
        @JsonProperty("status")
        String status,

        /** Fiserv-assigned reference number for accepted transactions. Null on rejection. */
        @JsonProperty("transactionRef")
        String transactionRef,

        /** Fiserv rejection code (e.g. "INSF", "INVLD_ACCT"). Null when status is "APPROVED". */
        @JsonProperty("reasonCode")
        String reasonCode,

        /** Human-readable message from the Fiserv system. */
        @JsonProperty("message")
        String message
) {}
