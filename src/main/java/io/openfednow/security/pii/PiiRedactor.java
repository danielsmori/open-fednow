package io.openfednow.security.pii;

import java.util.Arrays;
import java.util.Set;

/**
 * Small utility for redacting personally identifiable and sensitive values
 * before they land in durable storage (audit log rows, structured application
 * logs, error responses).
 *
 * <p>The point of this class is to have one place to update if the redaction
 * policy needs to change — every log statement or persistence site that
 * touches PII must call through here, so widening the policy is a one-line
 * edit, not a repo-wide sweep.
 *
 * <p>Redaction is deliberately conservative:
 * <ul>
 *   <li><b>Account numbers</b> keep the last four characters — enough for
 *       operators to correlate rows visually against a customer-support
 *       ticket, but not enough to reconstruct the original.</li>
 *   <li><b>Query-string values</b> matching a small allow-list of
 *       sensitive parameter names are replaced with {@code REDACTED} while
 *       preserving the parameter's key so the shape of the request is still
 *       recognisable.</li>
 * </ul>
 *
 * <p>This is not a general-purpose scrubber. It doesn't try to detect
 * PANs, SSNs, or email addresses in arbitrary text — a false negative in
 * that kind of scanner is worse than useless, and structured logging is the
 * right long-term answer. Prefer never passing PII to a log statement to
 * begin with; use this only where a sensible partial view is genuinely
 * useful.
 */
public final class PiiRedactor {

    static final String REDACTED_TOKEN = "REDACTED";
    static final String MASK_CHARACTER = "*";
    static final int ACCOUNT_TAIL_LENGTH = 4;

    /**
     * Names of query-string parameters whose values are always redacted.
     * Matching is case-insensitive and considers only the name — the value
     * itself is never inspected.
     */
    private static final Set<String> SENSITIVE_PARAMETER_NAMES = Set.of(
            "token",
            "access_token",
            "refresh_token",
            "apikey",
            "api_key",
            "password",
            "secret",
            "authorization",
            "signature"
    );

    private PiiRedactor() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Masks an account number, preserving only the last {@code 4} characters.
     *
     * <p>Numbers shorter than or equal to the reveal length collapse to
     * all-mask output — otherwise a 4-digit account would round-trip
     * unchanged, defeating the redaction.
     *
     * @param account raw account number (may be {@code null} or blank)
     * @return the masked value, or the input unchanged for {@code null}/blank
     */
    public static String maskAccount(String account) {
        if (account == null || account.isBlank()) {
            return account;
        }
        int length = account.length();
        if (length <= ACCOUNT_TAIL_LENGTH) {
            return MASK_CHARACTER.repeat(length);
        }
        return MASK_CHARACTER.repeat(length - ACCOUNT_TAIL_LENGTH)
                + account.substring(length - ACCOUNT_TAIL_LENGTH);
    }

    /**
     * Rewrites a raw query string, replacing values of sensitive parameters
     * with {@code REDACTED}. Non-sensitive parameters and the overall
     * {@code &} / {@code =} structure are preserved so the redacted line is
     * still a syntactically valid query string.
     *
     * <p>Parameters without a value ({@code ?foo}) and parameters with an
     * empty value ({@code ?foo=}) are left alone — there's nothing to mask.
     *
     * @param queryString the raw query string as returned by
     *                    {@code HttpServletRequest.getQueryString()};
     *                    may be {@code null}
     * @return a query string of the same shape with sensitive values masked,
     *         or {@code null} if the input was {@code null}
     */
    public static String redactQueryString(String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return queryString;
        }
        return Arrays.stream(queryString.split("&"))
                .map(PiiRedactor::redactParameter)
                .reduce((a, b) -> a + "&" + b)
                .orElse(queryString);
    }

    private static String redactParameter(String parameter) {
        int equalsIndex = parameter.indexOf('=');
        if (equalsIndex <= 0) {
            // No key, or a bare key with no value — nothing sensitive to mask
            return parameter;
        }
        String key = parameter.substring(0, equalsIndex);
        String value = parameter.substring(equalsIndex + 1);
        if (value.isEmpty()) {
            return parameter;
        }
        if (SENSITIVE_PARAMETER_NAMES.contains(key.toLowerCase(java.util.Locale.ROOT))) {
            return key + "=" + REDACTED_TOKEN;
        }
        return parameter;
    }
}
