package io.openfednow.acl.adapters.fiserv;

import java.util.Map;

/**
 * Maps Fiserv-specific transaction status codes to ISO 20022 reason codes.
 *
 * <p>Fiserv Communicator Open and DNA return proprietary rejection codes in the
 * {@code reasonCode} field of the transaction response. These must be translated
 * to ISO 20022 codes before being returned in a pacs.002 status report.
 *
 * <p>Mappings are derived from the Fiserv Communicator Open API documentation
 * (docs.fiserv.dev) and the FedNow ISO 20022 code set published by the
 * Federal Reserve.
 *
 * <p>Codes not present in the map fall back to {@code NARR} (narrative reason),
 * which instructs the FedNow recipient to inspect the accompanying description.
 *
 * @see <a href="https://docs.fiserv.dev">Fiserv Developer Documentation</a>
 * @see <a href="https://www.iso20022.org/catalogue-messages/additional-content-messages/external-code-sets">
 *      ISO 20022 External Code Sets</a>
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
     * Converts a Fiserv rejection code to the corresponding ISO 20022 reason code.
     *
     * @param fiservCode the proprietary Fiserv rejection code (e.g. {@code "INSF"})
     * @return ISO 20022 reason code (e.g. {@code "AM04"}), or {@code "NARR"} if unmapped
     */
    public static String toIso20022(String fiservCode) {
        if (fiservCode == null) {
            return "NARR";
        }
        return FISERV_TO_ISO20022.getOrDefault(fiservCode, "NARR");
    }

    /**
     * Returns {@code true} if the Fiserv status string represents an accepted transaction.
     *
     * @param fiservStatus the {@code status} field from the Fiserv transaction response
     * @return {@code true} for {@code "APPROVED"} or {@code "ACCEPTED"}
     */
    public static boolean isApproved(String fiservStatus) {
        return "APPROVED".equals(fiservStatus) || "ACCEPTED".equals(fiservStatus);
    }
}
