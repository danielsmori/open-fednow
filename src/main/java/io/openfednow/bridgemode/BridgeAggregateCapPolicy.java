package io.openfednow.bridgemode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Policy for the per-account aggregate send cap during bridge mode — issue #68.
 *
 * <p>During a core maintenance window, outbound FedNow sends are authorized
 * against the cached Shadow Ledger balance while card-processor STIP activity
 * debits the same account out of band (the "STIP gap", ADR-0010). The
 * single-transfer amount cap and the velocity check bound each send and the
 * send <em>count</em>, but not the aggregate amount that can leave one account
 * in a window. This policy holds the aggregate ceiling.
 *
 * <p>This class is deliberately storage-agnostic: the legacy Redis-backed send
 * path enforces it in {@code ShadowLedger}, and the SQL reliability path will
 * enforce the same policy natively in SQL (#137). Keeping one policy object
 * prevents the two paths from drifting apart.
 *
 * <h2>Configuration</h2>
 *
 * <ul>
 *   <li>{@code openfednow.bridge-mode.max-aggregate-send-amount} — the ceiling
 *       in USD. <strong>Default: unset (cap disabled).</strong> A shipped
 *       default would become a de facto compliance artifact for anyone who
 *       deploys without reading the docs; operators must opt in with a number
 *       they have reasoned about.</li>
 *   <li>{@code openfednow.bridge-mode.expected-window-hours} — how long a
 *       maintenance window is expected to last (default 6). This is an
 *       <strong>alerting threshold only</strong>: a window open longer than
 *       this is flagged to operators, but the cap never resets on a timer.
 *       The control fails closed — only a confirmed reconciliation of the
 *       account clears it.</li>
 * </ul>
 */
@Component
public class BridgeAggregateCapPolicy {

    private static final Logger log = LoggerFactory.getLogger(BridgeAggregateCapPolicy.class);

    /** Ceiling in USD, or {@code null} when the cap is disabled. */
    private final BigDecimal maxAggregateSendAmount;
    /** Ceiling in integer cents, computed once; meaningful only when enabled. */
    private final long maxAggregateSendCents;
    private final int expectedWindowHours;

    public BridgeAggregateCapPolicy(
            @Value("${openfednow.bridge-mode.max-aggregate-send-amount:}") String maxAggregateSendAmount,
            @Value("${openfednow.bridge-mode.expected-window-hours:6}") int expectedWindowHours,
            @Value("${openfednow.bridge-mode.allow-sends:false}") boolean allowSends) {
        this.maxAggregateSendAmount = parseCap(maxAggregateSendAmount);
        this.maxAggregateSendCents = this.maxAggregateSendAmount == null ? 0L
                : toCents(this.maxAggregateSendAmount);
        if (expectedWindowHours <= 0) {
            throw new IllegalArgumentException(
                    "openfednow.bridge-mode.expected-window-hours must be positive");
        }
        this.expectedWindowHours = expectedWindowHours;

        if (this.maxAggregateSendAmount == null) {
            log.info("Bridge-mode aggregate send cap DISABLED "
                    + "(openfednow.bridge-mode.max-aggregate-send-amount is not set)");
        } else {
            log.info("Bridge-mode aggregate send cap active maxAggregateSendAmount={} "
                            + "expectedWindowHours={}",
                    this.maxAggregateSendAmount, expectedWindowHours);
            if (!allowSends) {
                log.warn("openfednow.bridge-mode.max-aggregate-send-amount is set but "
                        + "openfednow.bridge-mode.allow-sends is false — no bridge-mode send "
                        + "can occur, so the aggregate cap will never be exercised");
            }
        }
    }

    /**
     * Converts the ceiling to the integer cents the ledger enforces, failing at
     * startup rather than on every counted send: a value that rounds to zero
     * cents would silently block every send, and one too large for a long
     * would throw on every send.
     */
    private static long toCents(BigDecimal cap) {
        long cents;
        try {
            cents = cap.setScale(2, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "openfednow.bridge-mode.max-aggregate-send-amount is too large: " + cap, e);
        }
        if (cents <= 0) {
            throw new IllegalArgumentException(
                    "openfednow.bridge-mode.max-aggregate-send-amount must be at least 0.01 "
                            + "(it rounds to zero cents): " + cap);
        }
        return cents;
    }

    private static BigDecimal parseCap(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        BigDecimal cap;
        try {
            cap = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "openfednow.bridge-mode.max-aggregate-send-amount is not a valid amount: "
                            + raw, e);
        }
        if (cap.signum() <= 0) {
            throw new IllegalArgumentException(
                    "openfednow.bridge-mode.max-aggregate-send-amount must be positive "
                            + "(leave it unset to disable the cap)");
        }
        return cap;
    }

    /** Whether an aggregate ceiling is configured at all. */
    public boolean isEnabled() {
        return maxAggregateSendAmount != null;
    }

    /** The ceiling in USD, or {@code null} when the cap is disabled. */
    public BigDecimal getMaxAggregateSendAmount() {
        return maxAggregateSendAmount;
    }

    /**
     * The ceiling in integer cents, for enforcement alongside stores that keep
     * amounts in minor units. Only valid when {@link #isEnabled()} is true.
     *
     * @throws IllegalStateException if the cap is disabled
     */
    public long getMaxAggregateSendCents() {
        if (maxAggregateSendAmount == null) {
            throw new IllegalStateException("Aggregate cap is disabled — check isEnabled() first");
        }
        return maxAggregateSendCents;
    }

    /**
     * Expected maintenance-window length in hours. Alerting threshold only —
     * exceeding it never resets the cap.
     */
    public int getExpectedWindowHours() {
        return expectedWindowHours;
    }
}
