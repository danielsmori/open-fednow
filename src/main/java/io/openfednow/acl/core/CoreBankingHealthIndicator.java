package io.openfednow.acl.core;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Layer 2 — Core Banking Adapter health indicator.
 *
 * <p>Exposes the reachability of the active core banking system at
 * {@code /actuator/health/coreBanking}.
 *
 * <p>Status semantics:
 * <ul>
 *   <li><b>UP</b> — core system responded to the availability check;
 *       normal online processing is active.</li>
 *   <li><b>OUT_OF_SERVICE</b> — core system did not respond (scheduled
 *       maintenance or unplanned outage). The Shadow Ledger bridge mode
 *       is active and transactions are being queued for replay — the
 *       institution remains available to FedNow.</li>
 * </ul>
 *
 * <p>This indicator does NOT affect the Kubernetes readiness probe
 * (custom {@code HealthIndicator} beans are excluded from the
 * {@code readiness} group by default). It is intended for the operations
 * dashboard and alerting.
 */
@Component("coreBanking")
public class CoreBankingHealthIndicator implements HealthIndicator {

    private final CoreBankingAdapter coreBankingAdapter;

    public CoreBankingHealthIndicator(CoreBankingAdapter coreBankingAdapter) {
        this.coreBankingAdapter = coreBankingAdapter;
    }

    @Override
    public Health health() {
        String vendor = coreBankingAdapter.getVendorName();
        if (coreBankingAdapter.isCoreSystemAvailable()) {
            return Health.up()
                    .withDetail("vendor", vendor)
                    .withDetail("status", "reachable")
                    .build();
        }
        return Health.outOfService()
                .withDetail("vendor", vendor)
                .withDetail("status", "unreachable")
                .withDetail("note", "Shadow Ledger bridge mode is active; transactions queued for replay")
                .build();
    }
}
