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
    FEDNOW,
    /** The Clearing House's RTP® network (ISO 20022 XML over TCH private network). */
    RTP
}
