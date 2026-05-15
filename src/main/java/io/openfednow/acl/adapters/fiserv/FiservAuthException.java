package io.openfednow.acl.adapters.fiserv;

/**
 * Thrown when the Fiserv OAuth token endpoint returns an invalid or empty response.
 * Callers should treat this as a temporary failure and allow the circuit breaker
 * to open if it recurs.
 */
public class FiservAuthException extends RuntimeException {

    public FiservAuthException(String message) {
        super(message);
    }

    public FiservAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
