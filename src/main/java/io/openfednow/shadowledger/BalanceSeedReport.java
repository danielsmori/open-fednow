package io.openfednow.shadowledger;

import java.math.BigDecimal;
import java.util.List;

/**
 * Operator-facing summary of a single seed cycle.
 *
 * <p>Returned from both the startup seed and the on-demand admin re-seed. Each
 * configured account produces one {@link AccountSeedOutcome} so the operator can
 * see exactly which accounts were seeded, which were skipped because Redis
 * already held a balance, and which failed (and why).
 */
public record BalanceSeedReport(
        int seededCount,
        int skippedCount,
        int failedCount,
        List<AccountSeedOutcome> outcomes
) {

    public static BalanceSeedReport empty() {
        return new BalanceSeedReport(0, 0, 0, List.of());
    }

    public static BalanceSeedReport of(List<AccountSeedOutcome> outcomes) {
        int seeded = 0, skipped = 0, failed = 0;
        for (AccountSeedOutcome outcome : outcomes) {
            switch (outcome.status()) {
                case SEEDED -> seeded++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
        }
        return new BalanceSeedReport(seeded, skipped, failed, List.copyOf(outcomes));
    }

    /**
     * Per-account result of a seed attempt.
     *
     * @param accountId institution-internal account identifier
     * @param status    {@link Status#SEEDED}, {@link Status#SKIPPED}, or {@link Status#FAILED}
     * @param balance   the balance written or read; null for FAILED
     * @param message   human-readable note; null on the SEEDED happy path
     */
    public record AccountSeedOutcome(
            String accountId,
            Status status,
            BigDecimal balance,
            String message
    ) {
        public enum Status { SEEDED, SKIPPED, FAILED }

        public static AccountSeedOutcome seeded(String accountId, BigDecimal balance) {
            return new AccountSeedOutcome(accountId, Status.SEEDED, balance, null);
        }

        public static AccountSeedOutcome skipped(String accountId, String message) {
            return new AccountSeedOutcome(accountId, Status.SKIPPED, null, message);
        }

        public static AccountSeedOutcome failed(String accountId, String message) {
            return new AccountSeedOutcome(accountId, Status.FAILED, null, message);
        }
    }
}
