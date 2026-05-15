package io.openfednow.acl.adapters.fiserv;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body from the Fiserv Communicator Open account balance inquiry endpoint.
 *
 * <p>The {@code availableBalance} field uses Fiserv's fixed-point encoding and is
 * decoded to a {@link java.math.BigDecimal} by
 * {@link FiservAmountEncoder#decode(String)}.
 *
 * <p>The {@code status} field reflects the account status:
 * <ul>
 *   <li>{@code "ACTIVE"}  — account is open and transactable</li>
 *   <li>{@code "FROZEN"}  — account is blocked; no debits permitted</li>
 *   <li>{@code "CLOSED"}  — account is closed</li>
 * </ul>
 */
public record FiservBalanceResponse(

        /** Institution's internal account identifier, echoed from the request. */
        @JsonProperty("accountId")
        String accountId,

        /** Available balance as a Fiserv fixed-point cent string (e.g. "500000" = $5,000.00). */
        @JsonProperty("availableBalance")
        String availableBalance,

        /** ISO 4217 currency code of the account. */
        @JsonProperty("currency")
        String currency,

        /** Account status: "ACTIVE", "FROZEN", or "CLOSED". */
        @JsonProperty("status")
        String status
) {}
