package io.openfednow.acl.adapters.jackhenry;

/**
 * Thrown when the Jack Henry jXchange OAuth 2.0 token endpoint returns an error
 * or an unusable response.
 */
public class JackHenryAuthException extends RuntimeException {

    public JackHenryAuthException(String message) {
        super(message);
    }

    public JackHenryAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
