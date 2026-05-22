package io.openfednow.security.audit;

/**
 * Classification of an admin-endpoint access attempt, derived from the final
 * HTTP status returned to the client.
 *
 * <p>The mapping is fixed:
 * <ul>
 *   <li>{@link #GRANTED}  — 1xx / 2xx / 3xx (request reached the controller)</li>
 *   <li>{@link #DENIED}   — 401 (no / bad credentials) or 403 (lacks role)</li>
 *   <li>{@link #REJECTED} — other 4xx (validation error, not-found, etc.)</li>
 *   <li>{@link #ERROR}    — 5xx (server-side failure)</li>
 * </ul>
 */
public enum AuditResult {
    GRANTED,
    DENIED,
    REJECTED,
    ERROR;

    /**
     * Maps an HTTP status code to its audit result.
     *
     * @param statusCode the final response status returned to the client
     */
    public static AuditResult fromStatus(int statusCode) {
        if (statusCode >= 500) {
            return ERROR;
        }
        if (statusCode == 401 || statusCode == 403) {
            return DENIED;
        }
        if (statusCode >= 400) {
            return REJECTED;
        }
        return GRANTED;
    }
}
