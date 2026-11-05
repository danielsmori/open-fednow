package io.openfednow.acl.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** External core operations. A timeout leaves a mutation unknown, never safe to replay blindly. */
public interface ReliableOutboundCorePort {
    enum Status { CONFIRMED, REFUSED, UNKNOWN, UNSUPPORTED }

    record Identity(String tenantId, String accountId, String clientKey) {
        public Identity {
            require(tenantId, "tenantId");
            require(accountId, "accountId");
            require(clientKey, "clientKey");
        }
    }

    record Balance(Identity identity, BigDecimal amount, String currency,
                   String source, Instant observedAt) {
        public Balance {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(amount);
            require(currency, "currency");
            require(source, "source");
            Objects.requireNonNull(observedAt);
        }
    }

    record Mutation(Identity identity, BigDecimal amount, String currency,
                    String reservationId) {
        public Mutation {
            Objects.requireNonNull(identity);
            if (amount == null || amount.signum() <= 0)
                throw new IllegalArgumentException("amount must be positive");
            require(currency, "currency");
        }
    }

    /** A confirmed mutation requires an external identifier; a hold also needs expiry evidence. */
    record Outcome(Status status, Identity identity, String providerId,
                   Instant expiresAt, String reason) {
        public Outcome {
            Objects.requireNonNull(status);
            Objects.requireNonNull(identity);
            if (status == Status.CONFIRMED) require(providerId, "providerId");
            if (status == Status.UNKNOWN) require(reason, "reason");
        }

        public static Outcome unsupported(Identity identity) {
            return new Outcome(Status.UNSUPPORTED, identity, null, null, "No verified external contract");
        }
    }

    Optional<Balance> observeBalance(Identity identity);
    Outcome reserve(Mutation request);
    Outcome lookupReservation(Identity identity);
    Outcome postReservedDebit(Mutation request);
    Outcome lookupPosting(Identity identity);
    Outcome releaseReservation(Identity identity, String reservationId);
    Outcome reversePosting(Identity identity, String postingId);

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
