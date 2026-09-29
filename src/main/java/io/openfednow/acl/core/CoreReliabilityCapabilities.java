package io.openfednow.acl.core;

/**
 * Version 1 evidence contract for outbound reliability prerequisites.
 * A local mock response is never evidence of a bank-wide reservation or
 * vendor-side idempotency. No current vendor-shaped adapter is approved for
 * the SQL reliability send path.
 */
public record CoreReliabilityCapabilities(
        String version,
        Evidence balanceRead,
        Evidence balanceAuthorityAndFreshness,
        Evidence allChannelReservation,
        Evidence reservationLookupAndExpiry,
        Evidence postingIdentityAndDeduplication,
        Evidence reversalIdentityAndDeduplication,
        Evidence lostAcknowledgmentLookup,
        Evidence dependencyOutageHandling,
        Evidence otherChannelCoordination) {

    public enum Evidence { LOCAL_FIXTURE, INTERFACE_ONLY, UNSUPPORTED, VENDOR_VERIFIED }

    public static CoreReliabilityCapabilities unsupported() {
        return new CoreReliabilityCapabilities("1.0", Evidence.UNSUPPORTED,
                Evidence.UNSUPPORTED, Evidence.UNSUPPORTED, Evidence.UNSUPPORTED,
                Evidence.UNSUPPORTED, Evidence.UNSUPPORTED, Evidence.UNSUPPORTED,
                Evidence.UNSUPPORTED, Evidence.UNSUPPORTED);
    }

    public static CoreReliabilityCapabilities vendorShapedFixture() {
        return new CoreReliabilityCapabilities("1.0", Evidence.LOCAL_FIXTURE,
                Evidence.INTERFACE_ONLY, Evidence.UNSUPPORTED, Evidence.UNSUPPORTED,
                Evidence.INTERFACE_ONLY, Evidence.UNSUPPORTED, Evidence.UNSUPPORTED,
                Evidence.LOCAL_FIXTURE, Evidence.UNSUPPORTED);
    }

    public boolean permitsLiveOutboundReservation() {
        return allChannelReservation == Evidence.VENDOR_VERIFIED
                && reservationLookupAndExpiry == Evidence.VENDOR_VERIFIED
                && postingIdentityAndDeduplication == Evidence.VENDOR_VERIFIED
                && lostAcknowledgmentLookup == Evidence.VENDOR_VERIFIED
                && otherChannelCoordination == Evidence.VENDOR_VERIFIED;
    }
}
