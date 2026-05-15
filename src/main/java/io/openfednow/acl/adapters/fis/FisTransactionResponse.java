package io.openfednow.acl.adapters.fis;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body from the FIS IBS transaction posting endpoint.
 *
 * <p>FIS IBS uses {@code "ACCEPTED"} as the primary approval status (Fiserv uses
 * {@code "APPROVED"}). Rejection reason codes use the FIS-specific code set
 * (e.g. {@code "INSUFF_FUNDS"}) and are mapped to ISO 20022 by
 * {@link FisReasonCodeMapper}.
 *
 * @see <a href="https://codeconnect.fisglobal.com">FIS Code Connect Developer Portal</a>
 */
public record FisTransactionResponse(

        /** Transaction outcome: {@code ACCEPTED}, {@code REJECTED}, or {@code PENDING}. */
        @JsonProperty("status")
        String status,

        /** FIS-assigned transaction reference, present when {@code status} is ACCEPTED. */
        @JsonProperty("fiTransId")
        String fiTransId,

        /** FIS proprietary rejection code (e.g. {@code "INSUFF_FUNDS"}), null on success. */
        @JsonProperty("rsnCode")
        String rsnCode,

        /** Human-readable description of the transaction outcome. */
        @JsonProperty("rsnDesc")
        String rsnDesc
) {}
