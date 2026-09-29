package io.openfednow.gateway;

/** The request may have reached the rail, but no authoritative status was received. */
public class SubmissionOutcomeUnknownException extends RuntimeException {
    public SubmissionOutcomeUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
