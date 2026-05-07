package io.openfednow.shadowledger;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Layer 4 — Shadow Ledger health indicator.
 *
 * <p>Exposes the current operating mode of the Shadow Ledger at
 * {@code /actuator/health/shadowLedger}.
 *
 * <p>Status semantics:
 * <ul>
 *   <li><b>UP / mode=ONLINE</b> — core system is available; the Shadow
 *       Ledger is mirroring balances in real time. Normal processing path.</li>
 *   <li><b>UP / mode=BRIDGE</b> — core system is offline; the Shadow
 *       Ledger is the authoritative balance source. Inbound payments are
 *       being accepted and queued for replay. The institution remains
 *       available to FedNow — this is the designed behaviour, not a
 *       failure state.</li>
 * </ul>
 *
 * <p>Both modes return {@code UP} because the Shadow Ledger is functioning
 * correctly in either case. The {@code coreBanking} indicator reports
 * {@code OUT_OF_SERVICE} when the core is offline, giving operators the
 * full picture without causing a false DOWN on the Shadow Ledger itself.
 *
 * <p>This indicator does NOT affect the Kubernetes readiness probe
 * (custom {@code HealthIndicator} beans are excluded from the
 * {@code readiness} group by default).
 */
@Component("shadowLedger")
public class ShadowLedgerHealthIndicator implements HealthIndicator {

    private final AvailabilityBridge availabilityBridge;

    public ShadowLedgerHealthIndicator(AvailabilityBridge availabilityBridge) {
        this.availabilityBridge = availabilityBridge;
    }

    @Override
    public Health health() {
        boolean inBridgeMode = availabilityBridge.isInBridgeMode();
        return Health.up()
                .withDetail("mode", inBridgeMode ? "BRIDGE" : "ONLINE")
                .withDetail("bridgeMode", inBridgeMode)
                .withDetail("description", inBridgeMode
                        ? "Core offline — accepting payments against Shadow Ledger; queued for replay"
                        : "Core online — Shadow Ledger mirroring balances in real time")
                .build();
    }
}
