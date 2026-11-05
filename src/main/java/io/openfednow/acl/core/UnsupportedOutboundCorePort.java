package io.openfednow.acl.core;

import java.util.Optional;

/** Safe default until a specific provider contract and authorization exist. */
public final class UnsupportedOutboundCorePort implements ReliableOutboundCorePort {
    @Override public Optional<Balance> observeBalance(Identity identity) { return Optional.empty(); }
    @Override public Outcome reserve(Mutation request) { return Outcome.unsupported(request.identity()); }
    @Override public Outcome lookupReservation(Identity identity) { return Outcome.unsupported(identity); }
    @Override public Outcome postReservedDebit(Mutation request) { return Outcome.unsupported(request.identity()); }
    @Override public Outcome lookupPosting(Identity identity) { return Outcome.unsupported(identity); }
    @Override public Outcome releaseReservation(Identity identity, String reservationId) { return Outcome.unsupported(identity); }
    @Override public Outcome reversePosting(Identity identity, String postingId) { return Outcome.unsupported(identity); }
}
