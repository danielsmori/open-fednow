package io.openfednow.acl.adapters.fiserv;

import java.util.Map;

/**
 * Maps locally modeled fixture rejection codes to ISO 20022 reason codes.
 * These source codes and mappings have not been verified for Banking Hub.
 * Unknown values fall back to {@code NARR} in the fixture.
 */
public class FiservReasonCodeMapper {

    private static final Map<String, String> FISERV_TO_ISO20022 = Map.of(
            "INSF",        "AM04",   // InsufficientFunds
            "INVLD_ACCT",  "AC01",   // IncorrectAccountNumber
            "CLSD_ACCT",   "AC04",   // ClosedAccountNumber
            "ACCT_FRZN",   "AC06",   // BlockedAccount
            "DUPLC",       "DUPL",   // DuplicatePayment
            "DAILY_LMT",   "AM14",   // AmountExceedsClearingSystemLimit
            "TXN_LMT",     "AM02",   // NotAllowedAmount
            "INVLD_RTNG",  "RC01"    // BankIdentifierIncorrect
    );

    private FiservReasonCodeMapper() {}

    /**
     * Converts a locally modeled rejection code to an ISO 20022 reason code.
     *
     * @param fiservCode the fixture rejection code (e.g. {@code "INSF"})
     * @return ISO 20022 reason code (e.g. {@code "AM04"}), or {@code "NARR"} if unmapped
     */
    public static String toIso20022(String fiservCode) {
        if (fiservCode == null) {
            return "NARR";
        }
        return FISERV_TO_ISO20022.getOrDefault(fiservCode, "NARR");
    }

    /**
     * Returns {@code true} if the fixture status string represents acceptance.
     *
     * @param fiservStatus the {@code status} field from the Fiserv transaction response
     * @return {@code true} for {@code "APPROVED"} or {@code "ACCEPTED"}
     */
    public static boolean isApproved(String fiservStatus) {
        return "APPROVED".equals(fiservStatus) || "ACCEPTED".equals(fiservStatus);
    }
}
