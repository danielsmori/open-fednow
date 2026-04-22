package io.openfednow.acl.core;

/**
 * Represents the normalized response from a core banking adapter after
 * a transaction submission. Vendor-specific status codes are mapped to
 * ISO 20022 reason codes by each adapter implementation.
 */
public class CoreBankingResponse {

    public enum Status {
        /** Transaction accepted and posted by the core system. */
        ACCEPTED,
        /** Transaction rejected by the core system (e.g., insufficient funds, invalid account). */
        REJECTED,
        /** Core system accepted the request but processing is asynchronous. */
        PENDING,
        /** Core system did not respond within the timeout window. */
        TIMEOUT
    }

    private final Status status;
    private final String iso20022ReasonCode;
    private final String vendorStatusCode;
    private final String transactionReference;

    public CoreBankingResponse(Status status, String iso20022ReasonCode,
                                String vendorStatusCode, String transactionReference) {
        this.status = status;
        this.iso20022ReasonCode = iso20022ReasonCode;
        this.vendorStatusCode = vendorStatusCode;
        this.transactionReference = transactionReference;
    }

    public Status getStatus() { return status; }
    public String getIso20022ReasonCode() { return iso20022ReasonCode; }
    public String getVendorStatusCode() { return vendorStatusCode; }
    public String getTransactionReference() { return transactionReference; }

    public boolean isAccepted() { return status == Status.ACCEPTED; }
    public boolean isRejected() { return status == Status.REJECTED; }
    public boolean isPending() { return status == Status.PENDING; }
}
