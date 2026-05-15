package io.openfednow.acl.adapters.fis;

/**
 * Thrown when the FIS Code Connect OAuth 2.0 token endpoint returns an
 * empty, invalid, or otherwise unusable response.
 *
 * <p>This is an unchecked exception so that it propagates naturally through
 * the circuit breaker and triggers the fallback method without forcing callers
 * to declare checked exceptions.
 */
public class FisAuthException extends RuntimeException {

    public FisAuthException(String message) {
        super(message);
    }

    public FisAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
