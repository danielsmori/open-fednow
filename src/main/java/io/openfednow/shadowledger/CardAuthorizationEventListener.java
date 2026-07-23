package io.openfednow.shadowledger;

import java.math.BigDecimal;

/**
 * Port for receiving real-time card authorization events from external card network streams.
 *
 * <p>Implementations bridge external card network authorizations into the shadow ledger system
 * during bridge mode operations to maintain accurate balance tracking.
 */
public interface CardAuthorizationEventListener {

    /**
     * Handles an incoming card authorization event.
     *
     * @apiNote Implementations MUST deduplicate incoming events by {@code authCode};
     *          the framework does not enforce at-most-once delivery. Redelivered authorization
     *          events must not trigger secondary ledger holds or double-debit the account.
     *
     * @param accountId the target ledger account identifier
     * @param amount    the transaction authorization amount represented as a {@link BigDecimal}
     * @param authCode  opaque to the framework; the network-assigned identifier used to correlate this
     *                  event with any subsequent reversal
     */
    void onAuthorization(String accountId, BigDecimal amount, String authCode);

    /**
     * Handles a card authorization reversal event.
     *
     * @apiNote Implementations MUST deduplicate reversal events by {@code authCode};
     *          the framework does not enforce at-most-once delivery. Redelivered reversal
     *          events must not execute duplicate release operations or double-credit the account.
     *
     * @param accountId the target ledger account identifier
     * @param amount    the reversal amount represented as a {@link BigDecimal}
     * @param authCode  opaque to the framework; the reference authorization code matching the original authorization
     */
    void onReversal(String accountId, BigDecimal amount, String authCode);
}