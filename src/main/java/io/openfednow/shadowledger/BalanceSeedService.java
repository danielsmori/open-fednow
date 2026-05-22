package io.openfednow.shadowledger;

import io.openfednow.acl.core.CoreBankingAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Layer 4 — Seeds Shadow Ledger balances from the core banking system.
 *
 * <p>The Shadow Ledger stores balances in Redis as the live read path. On a
 * fresh deployment, or after a Redis eviction / restart, those keys are absent
 * and every account reads as zero — every outbound payment would then fail the
 * sufficient-funds check until balances were manually loaded.
 *
 * <p>This service closes that gap. On {@link ApplicationReadyEvent} it iterates
 * the configured seed-account list, fetches each balance from the
 * {@link CoreBankingAdapter}, and writes it into the Shadow Ledger via
 * {@link ShadowLedger#seedBalanceIfAbsent(String, java.math.BigDecimal)} — safe
 * to run repeatedly because existing Redis values are never overwritten.
 *
 * <p>The {@link #seedAllConfigured()} method exposes the same logic for the
 * admin endpoint, which uses unconditional overwrite semantics so an operator
 * can resync from the core on demand.
 *
 * <p>Failure modes are isolated: a per-account adapter exception is logged and
 * the next account is attempted. A core that is entirely unavailable at startup
 * simply leaves balances unseeded — the framework continues to start so that
 * inbound traffic can still be handled, and the operator can re-seed once the
 * core is reachable again.
 */
@Component
public class BalanceSeedService {

    private static final Logger log = LoggerFactory.getLogger(BalanceSeedService.class);

    private final ShadowLedger shadowLedger;
    private final CoreBankingAdapter coreBankingAdapter;
    private final List<String> seedAccountIds;

    public BalanceSeedService(ShadowLedger shadowLedger,
                              CoreBankingAdapter coreBankingAdapter,
                              @Value("${openfednow.shadow-ledger.seed-accounts:}") String seedAccountsProperty) {
        this.shadowLedger = shadowLedger;
        this.coreBankingAdapter = coreBankingAdapter;
        this.seedAccountIds = parseAccountIds(seedAccountsProperty);
    }

    private static List<String> parseAccountIds(String property) {
        if (property == null || property.isBlank()) {
            return List.of();
        }
        return Arrays.stream(property.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableList());
    }

    /** Visible to tests. */
    List<String> configuredSeedAccountIds() {
        return seedAccountIds;
    }

    /**
     * Triggered after the application context is fully ready. Seeds balances from the
     * core only for accounts not already present in Redis, so a recycled middleware pod
     * connecting to a healthy Redis cluster does not clobber live balances.
     */
    @EventListener(ApplicationReadyEvent.class)
    public BalanceSeedReport seedOnStartup() {
        if (seedAccountIds.isEmpty()) {
            log.info("Shadow Ledger startup seed: no accounts configured (openfednow.shadow-ledger.seed-accounts is empty)");
            return BalanceSeedReport.empty();
        }
        log.info("Shadow Ledger startup seed: {} configured account(s)", seedAccountIds.size());
        return runSeed(SeedMode.IF_ABSENT);
    }

    /**
     * Re-seeds every configured account from the core banking system, overwriting any
     * existing Redis values. Invoked by the {@code POST /admin/shadow-ledger/seed}
     * endpoint.
     */
    public BalanceSeedReport seedAllConfigured() {
        log.info("Shadow Ledger admin re-seed requested for {} configured account(s)",
                seedAccountIds.size());
        return runSeed(SeedMode.OVERWRITE);
    }

    private BalanceSeedReport runSeed(SeedMode mode) {
        List<BalanceSeedReport.AccountSeedOutcome> outcomes = new ArrayList<>(seedAccountIds.size());
        for (String accountId : seedAccountIds) {
            outcomes.add(seedOne(accountId, mode));
        }
        BalanceSeedReport report = BalanceSeedReport.of(outcomes);
        log.info("Shadow Ledger seed complete seeded={} skipped={} failed={}",
                report.seededCount(), report.skippedCount(), report.failedCount());
        return report;
    }

    private BalanceSeedReport.AccountSeedOutcome seedOne(String accountId, SeedMode mode) {
        BigDecimal coreBalance;
        try {
            coreBalance = coreBankingAdapter.getAvailableBalance(accountId);
        } catch (Exception e) {
            log.warn("Failed to fetch core balance for accountId={} ({}). Skipping seed.",
                    accountId, e.getMessage());
            return BalanceSeedReport.AccountSeedOutcome.failed(accountId, e.getMessage());
        }
        if (coreBalance == null) {
            log.warn("Core returned null balance for accountId={}. Skipping seed.", accountId);
            return BalanceSeedReport.AccountSeedOutcome.failed(accountId, "Core returned null balance");
        }
        try {
            switch (mode) {
                case IF_ABSENT -> {
                    boolean seeded = shadowLedger.seedBalanceIfAbsent(accountId, coreBalance);
                    return seeded
                            ? BalanceSeedReport.AccountSeedOutcome.seeded(accountId, coreBalance)
                            : BalanceSeedReport.AccountSeedOutcome.skipped(accountId,
                                    "Existing Shadow Ledger balance preserved");
                }
                case OVERWRITE -> {
                    shadowLedger.seedBalance(accountId, coreBalance);
                    return BalanceSeedReport.AccountSeedOutcome.seeded(accountId, coreBalance);
                }
                default -> throw new IllegalStateException("Unhandled SeedMode: " + mode);
            }
        } catch (Exception e) {
            log.warn("Failed to write Shadow Ledger seed for accountId={} ({})",
                    accountId, e.getMessage());
            return BalanceSeedReport.AccountSeedOutcome.failed(accountId, e.getMessage());
        }
    }

    private enum SeedMode { IF_ABSENT, OVERWRITE }
}
