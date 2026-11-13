package io.openfednow.shadowledger;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Snapshot of an account's open bridge-mode aggregate send window — issue #68.
 *
 * <p>Returned by {@link ShadowLedger#getAggregateView(String)}; absent (null)
 * when the account has no open window.
 *
 * @param accountId    institution-internal account identifier
 * @param totalCounted aggregate of counted, not-yet-released sends in USD
 * @param openedAt     when the window opened (first counted send); null only if
 *                     the hash exists without the bookkeeping field, which
 *                     indicates outside interference with the key
 * @param countedSends number of sends currently counted (not yet released)
 */
public record BridgeAggregateView(
        String accountId,
        BigDecimal totalCounted,
        Instant openedAt,
        long countedSends) {
}
