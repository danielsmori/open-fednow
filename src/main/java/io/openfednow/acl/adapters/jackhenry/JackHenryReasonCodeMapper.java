package io.openfednow.acl.adapters.jackhenry;

import java.util.Map;

/**
 * Maps Jack Henry jXchange iAdapter error codes to ISO 20022 reason codes.
 *
 * <p>Jack Henry jXchange returns numeric error codes in SOAP fault responses
 * ({@code ErrRec/ErrCode}). These must be translated to ISO 20022 codes before
 * being surfaced in a pacs.002 payment status report sent back to FedNow.
 *
 * <p>jXchange uses distinct numeric ranges for different error categories:
 * <ul>
 *   <li>3000–3099: Business-level transaction rejections (account, funds, limits)</li>
 *   <li>9900000+: System-level connectivity and authentication errors (mapped to TIMEOUT
 *       by the circuit breaker, not by this mapper)</li>
 * </ul>
 *
 * <p>Error codes not present in the map fall back to {@code NARR} (narrative reason),
 * which instructs the FedNow recipient to inspect the accompanying description field.
 *
 * @see <a href="https://jackhenry.dev/jxchange-soap/overview/portal-navigation/jxchange-environment/error-handling/">
 *      Jack Henry jXchange Error Handling</a>
 * @see <a href="https://www.iso20022.org/catalogue-messages/additional-content-messages/external-code-sets">
 *      ISO 20022 External Code Sets</a>
 */
public class JackHenryReasonCodeMapper {

    /**
     * Maps jXchange numeric ErrCode values to ISO 20022 reason codes.
     * Codes are documented in the Jack Henry iAdapter Error Code Reference.
     */
    private static final Map<String, String> JH_TO_ISO20022 = Map.of(
            "3050", "AM04",   // InsufficientFunds
            "3051", "AC04",   // ClosedAccountNumber
            "3052", "AC06",   // BlockedAccount
            "3053", "AC01",   // IncorrectAccountNumber
            "3054", "DUPL",   // DuplicatePayment
            "3055", "AM14",   // AmountExceedsClearingSystemLimit
            "3056", "AM02",   // NotAllowedAmount
            "3057", "RC01"    // BankIdentifierIncorrect
    );

    private JackHenryReasonCodeMapper() {}

    /**
     * Converts a jXchange numeric error code to the corresponding ISO 20022 reason code.
     *
     * @param jxErrCode the numeric jXchange error code as a string (e.g. {@code "3050"})
     * @return ISO 20022 reason code (e.g. {@code "AM04"}), or {@code "NARR"} if unmapped
     */
    public static String toIso20022(String jxErrCode) {
        if (jxErrCode == null) {
            return "NARR";
        }
        return JH_TO_ISO20022.getOrDefault(jxErrCode.trim(), "NARR");
    }

    /**
     * Returns {@code true} if the TrnAdd result status indicates a successfully
     * posted transaction.
     *
     * <p>jXchange uses {@code "POSTED"} as the primary success status for
     * {@code TrnAdd} responses. Some implementations also return {@code "APPROVED"}
     * for same-day credits pending final clearance.
     *
     * @param status the {@code Status} field from a {@code TrnAddRslt} element
     * @return {@code true} for {@code "POSTED"} or {@code "APPROVED"}
     */
    public static boolean isPosted(String status) {
        return "POSTED".equals(status) || "APPROVED".equals(status);
    }
}
