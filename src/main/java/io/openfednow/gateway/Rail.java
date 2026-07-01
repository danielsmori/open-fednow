package io.openfednow.gateway;

/**
 * Identifies which instant-payment rail an inbound message arrived on.
 *
 * <p>Layers 2–4 are rail-agnostic and operate on parsed ISO 20022 messages without
 * knowing which rail delivered them (see ADR-0005). Layer 1, however, must record
 * the source rail so that asynchronous response paths — reconciliation-time
 * notifications, pacs.004 returns triggered by saga compensation, and out-of-band
 * status updates — can be dispatched back to the correct rail.
 */
public enum Rail {
    /** Federal Reserve's FedNow Service (REST/JSON over Fed PKI mutual TLS). */
    FEDNOW("USD"),
    /** The Clearing House's RTP® network (ISO 20022 XML over TCH private network). */
    RTP("USD");

    private final String supportedCurrency;

    Rail(String supportedCurrency) {
        this.supportedCurrency = supportedCurrency;
    }

    /**
     * ISO 4217 code of the currency this rail settles in. FedNow and RTP are both
     * USD-only today. This getter exists so that non-USD future rails — or a
     * hypothetical multi-currency rail — can be added by extending the enum
     * rather than by editing every gateway that validates inbound messages.
     */
    public String getSupportedCurrency() {
        return supportedCurrency;
    }

    /**
     * True when this rail can settle the given ISO 4217 currency. Matching is
     * case-sensitive because ISO 4217 is defined in uppercase; a well-formed
     * inbound message will already comply, and rejecting mixed-case values is
     * a small integrity check on the caller.
     */
    public boolean supportsCurrency(String currency) {
        return supportedCurrency.equals(currency);
    }
}
