package io.openfednow.acl.adapters.fiserv;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body from the legacy local balance fixture. The fields, freshness,
 * and cent-string format have not been verified for Banking Hub.
 */
public record FiservBalanceResponse(

        /** Fixture account identifier, echoed from the request. */
        @JsonProperty("accountId")
        String accountId,

        /** Available balance as a fixture cent string (e.g. "500000" = $5,000.00). */
        @JsonProperty("availableBalance")
        String availableBalance,

        /** ISO 4217 currency code of the account. */
        @JsonProperty("currency")
        String currency,

        /** Account status: "ACTIVE", "FROZEN", or "CLOSED". */
        @JsonProperty("status")
        String status
) {}
