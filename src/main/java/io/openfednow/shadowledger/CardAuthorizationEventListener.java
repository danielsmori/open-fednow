package io.openfednow.shadowledger;

/**
 * Port for real-time card authorization events from an institution's card
 * processor.
 *
 * <p>Implementations should call {@link ShadowLedger#applyDebit(String, long,
 * String)} for authorizations and the matching credit path for reversals so
 * the Shadow Ledger stays synchronized with STIP-approved card activity while
 * the core is unavailable. Vendor-specific processors such as Visa DPS, FIS
 * CardManager, Fiserv STAR, or Jack Henry should be integrated in separate
 * adapter modules.
 */
public interface CardAuthorizationEventListener {

    /**
     * Handles an approved card authorization that should reduce available balance.
     *
     * @param accountId the account that received the authorization
     * @param amountCents the authorized amount in cents
     * @param authCode the card processor authorization code
     */
    void onAuthorization(String accountId, long amountCents, String authCode);

    /**
     * Handles a card authorization reversal that should restore available balance.
     *
     * @param accountId the account that received the reversal
     * @param amountCents the reversed amount in cents
     * @param originalAuthCode the original card processor authorization code
     */
    void onReversal(String accountId, long amountCents, String originalAuthCode);
}
