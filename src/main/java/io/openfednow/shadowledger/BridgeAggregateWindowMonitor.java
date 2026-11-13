package io.openfednow.shadowledger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.openfednow.bridgemode.BridgeAggregateCapPolicy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes the state of every open bridge-mode aggregate window as gauges —
 * issue #68.
 *
 * <p>The send-time metrics ({@code bridge_mode.aggregate.cap_exceeded},
 * {@code bridge_mode.aggregate.pct_of_cap}) show what happens to sends. These
 * gauges show the standing exposure, so operators can see accounts trending
 * toward the cap, and windows that stay open too long, before anything is
 * rejected:
 * <ul>
 *   <li>{@code bridge_mode.aggregate.windows_open} — accounts with an open window</li>
 *   <li>{@code bridge_mode.aggregate.windows_near_cap} — windows at or above
 *       {@code openfednow.bridge-mode.near-cap-threshold-percent} of the cap</li>
 *   <li>{@code bridge_mode.aggregate.windows_overdue} — windows open longer than
 *       {@code openfednow.bridge-mode.expected-window-hours}; the cap is never
 *       lifted on a timer, so a non-zero value means reconciliation has not
 *       released these accounts (core still offline, reconciliation failing, or
 *       a send whose rail outcome is unresolved)</li>
 *   <li>{@code bridge_mode.aggregate.oldest_window_age_seconds}</li>
 * </ul>
 * None carries an account tag: per-account series would be unbounded and would
 * put account ids in metrics. Use {@code GET /admin/accounts/{id}/bridge-aggregate}
 * to inspect one account.
 *
 * <p>The scan runs every {@code openfednow.bridge-mode.aggregate-metrics-interval-seconds}
 * on its own thread — never the shared Spring scheduler thread, whose other
 * jobs (saga timeouts, compensation retries) must not wait on Redis. It only
 * runs while the cap is enabled; the gauges then read 0. A failed scan keeps
 * the previous values and is logged.
 */
@Component
public class BridgeAggregateWindowMonitor {

    private static final Logger log = LoggerFactory.getLogger(BridgeAggregateWindowMonitor.class);

    static final String WINDOWS_OPEN_METRIC = "bridge_mode.aggregate.windows_open";
    static final String WINDOWS_NEAR_CAP_METRIC = "bridge_mode.aggregate.windows_near_cap";
    static final String WINDOWS_OVERDUE_METRIC = "bridge_mode.aggregate.windows_overdue";
    static final String OLDEST_WINDOW_AGE_METRIC = "bridge_mode.aggregate.oldest_window_age_seconds";

    private final ShadowLedger shadowLedger;
    private final BridgeAggregateCapPolicy policy;
    private final int nearCapThresholdPercent;
    private final long intervalSeconds;
    private final Clock clock;
    private ScheduledExecutorService executor;

    private final AtomicLong windowsOpen = new AtomicLong();
    private final AtomicLong windowsNearCap = new AtomicLong();
    private final AtomicLong windowsOverdue = new AtomicLong();
    private final AtomicLong oldestWindowAgeSeconds = new AtomicLong();

    @Autowired
    public BridgeAggregateWindowMonitor(
            ShadowLedger shadowLedger,
            BridgeAggregateCapPolicy policy,
            MeterRegistry meterRegistry,
            @Value("${openfednow.bridge-mode.near-cap-threshold-percent:80}") int nearCapThresholdPercent,
            @Value("${openfednow.bridge-mode.aggregate-metrics-interval-seconds:60}") long intervalSeconds) {
        this(shadowLedger, policy, meterRegistry, nearCapThresholdPercent, intervalSeconds, Clock.systemUTC());
    }

    /** Visible for tests: a fixed clock. */
    BridgeAggregateWindowMonitor(ShadowLedger shadowLedger, BridgeAggregateCapPolicy policy,
                                 MeterRegistry meterRegistry, int nearCapThresholdPercent,
                                 long intervalSeconds, Clock clock) {
        if (nearCapThresholdPercent < 1 || nearCapThresholdPercent > 100) {
            throw new IllegalArgumentException(
                    "openfednow.bridge-mode.near-cap-threshold-percent must be between 1 and 100");
        }
        if (intervalSeconds <= 0) {
            throw new IllegalArgumentException(
                    "openfednow.bridge-mode.aggregate-metrics-interval-seconds must be positive");
        }
        this.shadowLedger = shadowLedger;
        this.policy = policy;
        this.nearCapThresholdPercent = nearCapThresholdPercent;
        this.intervalSeconds = intervalSeconds;
        this.clock = clock;

        gauge(meterRegistry, WINDOWS_OPEN_METRIC, windowsOpen,
                "Accounts with an open bridge-mode aggregate send window");
        gauge(meterRegistry, WINDOWS_NEAR_CAP_METRIC, windowsNearCap,
                "Open aggregate windows at or above the near-cap threshold (percent of cap)");
        gauge(meterRegistry, WINDOWS_OVERDUE_METRIC, windowsOverdue,
                "Open aggregate windows older than openfednow.bridge-mode.expected-window-hours");
        gauge(meterRegistry, OLDEST_WINDOW_AGE_METRIC, oldestWindowAgeSeconds,
                "Age in seconds of the oldest open aggregate window");
    }

    private static void gauge(MeterRegistry registry, String name, AtomicLong value, String description) {
        Gauge.builder(name, value, AtomicLong::get).description(description).register(registry);
    }

    @PostConstruct
    void start() {
        if (!policy.isEnabled()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bridge-aggregate-metrics");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::scanSafely, 0, intervalSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void scanSafely() {
        try {
            scan();
        } catch (RuntimeException e) {
            log.warn("Bridge aggregate window scan failed — gauges keep their previous values", e);
        }
    }

    /** Recomputes every gauge from the open windows. Visible for tests. */
    void scan() {
        if (!policy.isEnabled()) {
            return;
        }
        BigDecimal cap = policy.getMaxAggregateSendAmount();
        BigDecimal nearCap = cap.multiply(BigDecimal.valueOf(nearCapThresholdPercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        Instant now = clock.instant();
        Duration expectedWindow = Duration.ofHours(policy.getExpectedWindowHours());

        long open = 0;
        long near = 0;
        long overdue = 0;
        long oldestAge = 0;
        for (String accountId : shadowLedger.openAggregateAccountIds()) {
            BridgeAggregateView view = shadowLedger.getAggregateView(accountId);
            if (view == null) {
                continue; // closed since the scan listed it
            }
            open++;
            if (view.totalCounted().compareTo(nearCap) >= 0) {
                near++;
            }
            if (view.openedAt() != null) {
                Duration age = Duration.between(view.openedAt(), now);
                oldestAge = Math.max(oldestAge, Math.max(0, age.getSeconds()));
                if (age.compareTo(expectedWindow) > 0) {
                    overdue++;
                }
            }
        }

        long previousOverdue = windowsOverdue.getAndSet(overdue);
        windowsOpen.set(open);
        windowsNearCap.set(near);
        oldestWindowAgeSeconds.set(oldestAge);
        if (overdue > 0 && overdue != previousOverdue) {
            log.warn("{} bridge-mode aggregate window(s) open longer than {}h — the accounts stay capped "
                            + "until reconciliation confirms their sends; check reconciliation and unresolved "
                            + "sends (GET /admin/sagas)", overdue, policy.getExpectedWindowHours());
        }
    }
}
