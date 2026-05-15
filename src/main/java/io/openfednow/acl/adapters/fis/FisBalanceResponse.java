package io.openfednow.acl.adapters.fis;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body from the FIS IBS account balance inquiry endpoint.
 *
 * <p>FIS IBS returns the available balance as a plain decimal string
 * (e.g. {@code "5000.00"}), unlike Fiserv which uses fixed-point cent integers
 * (e.g. {@code "500000"}). The adapter converts to {@link java.math.BigDecimal}
 * via {@link java.math.BigDecimal#BigDecimal(String)} before returning.
 *
 * @see <a href="https://codeconnect.fisglobal.com">FIS Code Connect Developer Portal</a>
 */
public record FisBalanceResponse(

        /** FIS account identifier. */
        @JsonProperty("acctId")
        String acctId,

        /** Available balance as a decimal string (e.g. {@code "5000.00"}). */
        @JsonProperty("availBal")
        String availBal,

        /** ISO 4217 currency code. */
        @JsonProperty("currency")
        String currency,

        /** Account status: {@code ACTIVE}, {@code CLOSED}, {@code FROZEN}, etc. */
        @JsonProperty("acctStatus")
        String acctStatus
) {}
