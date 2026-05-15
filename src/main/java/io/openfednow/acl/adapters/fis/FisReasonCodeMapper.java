package io.openfednow.acl.adapters.fis;

import java.util.Map;

/**
 * Maps FIS IBS (Internet Banking Solution) proprietary rejection codes to
 * ISO 20022 reason codes.
 *
 * <p>FIS IBS returns vendor-specific {@code rsnCode} values in transaction
 * responses. These must be translated to ISO 20022 codes before being
 * surfaced in a pacs.002 payment status report sent back to FedNow.
 *
 * <p>FIS error codes use a different naming convention from Fiserv — they are
 * more terse and use underscores with abbreviated words (e.g. {@code INSUFF_FUNDS}
 * vs. Fiserv's {@code INSF}). This is a deliberate demonstration that the
 * OpenFedNow adapter framework handles each vendor's proprietary code space
 * independently behind the {@link io.openfednow.acl.core.CoreBankingAdapter}
 * interface.
 *
 * <p>Codes not present in the map fall back to {@code NARR} (narrative reason),
 * which instructs the FedNow recipient to inspect the accompanying description.
 *
 * @see <a href="https://codeconnect.fisglobal.com">FIS Code Connect Developer Portal</a>
 * @see <a href="https://www.iso20022.org/catalogue-messages/additional-content-messages/external-code-sets">
 *      ISO 20022 External Code Sets</a>
 */
public class FisReasonCodeMapper {

    private static final Map<String, String> FIS_TO_ISO20022 = Map.of(
            "INSUFF_FUNDS",  "AM04",   // InsufficientFunds
            "INVLD_ACCT",    "AC01",   // IncorrectAccountNumber
            "CLSD_ACCT",     "AC04",   // ClosedAccountNumber
            "FRZN_ACCT",     "AC06",   // BlockedAccount
            "DUPE_TXN",      "DUPL",   // DuplicatePayment
            "LMT_EXCDED",    "AM14",   // AmountExceedsClearingSystemLimit
            "TXN_AMT_EXCDED","AM02",   // NotAllowedAmount
            "INVLD_RTTNG",   "RC01"    // BankIdentifierIncorrect
    );

    private FisReasonCodeMapper() {}

    /**
     * Converts a FIS IBS rejection code to the corresponding ISO 20022 reason code.
     *
     * @param fisCode the proprietary FIS rejection code (e.g. {@code "INSUFF_FUNDS"})
     * @return ISO 20022 reason code (e.g. {@code "AM04"}), or {@code "NARR"} if unmapped
     */
    public static String toIso20022(String fisCode) {
        if (fisCode == null) {
            return "NARR";
        }
        return FIS_TO_ISO20022.getOrDefault(fisCode, "NARR");
    }

    /**
     * Returns {@code true} if the FIS status string represents an accepted transaction.
     *
     * <p>FIS IBS uses {@code "ACCEPTED"} as its approval status, unlike Fiserv
     * which uses {@code "APPROVED"}. Both are mapped to
     * {@link io.openfednow.acl.core.CoreBankingResponse.Status#ACCEPTED}.
     *
     * @param fisStatus the {@code status} field from the FIS transaction response
     * @return {@code true} for {@code "ACCEPTED"} or {@code "APPROVED"}
     */
    public static boolean isAccepted(String fisStatus) {
        return "ACCEPTED".equals(fisStatus) || "APPROVED".equals(fisStatus);
    }
}
