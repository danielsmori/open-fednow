package io.openfednow.shadowledger;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Operator-facing snapshot of an account's Shadow Ledger state.
 *
 * <p>The Redis balance already reflects every applied DEBIT / CREDIT / REVERSAL,
 * so {@code available} is what the account can spend right now. The framework
 * tracks each balance change in {@code shadow_ledger_transaction_log} with a
 * {@code core_confirmed} flag — {@code false} until reconciliation has confirmed
 * the operation against the authoritative core ledger. {@code reservedPendingCore}
 * is the sum of DEBITs that have hit the Shadow Ledger but are not yet
 * confirmed, i.e. the amount of provisional activity an operator should expect
 * the next reconciliation cycle to validate.
 *
 * @param accountId            institution-internal account identifier
 * @param available            current Redis balance (in USD)
 * @param reservedPendingCore  sum of unconfirmed DEBITs awaiting core confirmation (USD)
 * @param lastTransactionAt    most recent {@code applied_at} for this account; null if
 *                             the account has no transaction history yet
 */
public record AccountBalanceView(
        String accountId,
        BigDecimal available,
        BigDecimal reservedPendingCore,
        Instant lastTransactionAt
) {}
