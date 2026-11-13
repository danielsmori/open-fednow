package io.openfednow.shadowledger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.openfednow.bridgemode.BridgeAggregateCapPolicy;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sampled gauges over the open bridge-mode aggregate send windows — issue #68.
 *
 * <p>The {@code bridge_mode.aggregate.pct_of_cap} distribution (recorded at
 * each counted send) shows traffic; these gauges show <em>state</em>, including
 * the state nothing else would surface: a window held open by a send whose rail
 * outcome never resolved keeps its account capped, and before these gauges only
 * a log line said so.
 *
 * <ul>
 *   <li>{@code bridge_mode.aggregate.open_windows} — accounts currently capped
 *       by an open window.</li>
 *   <li>{@code bridge_mode.aggregate.windows_near_cap} — of those, windows at
 *       {@value #NEAR_CAP_PERCENT}% of the cap or more: the accounts whose next
 *       sends are about to be rejected.</li>
 *   <li>{@code bridge_mode.aggregate.windows_overdue} — windows open longer
 *       than {@code openfednow.bridge-mode.expected-window-hours}. The expected
 *       window length never resets the cap (#68 is fail-closed); this gauge is
 *       what it alerts through.</li>
 * </ul>
 *
 * <p>Sampling SCANs the window keys and reads each window, so it runs on its
 * own thread (never the shared scheduler thread; a Redis outage must not stall
 * saga timeouts), at a fixed interval
 * ({@code openfednow.bridge-mode.metrics-sample-interval-seconds}, default 60),
 * skipping a tick if the previous one is still running. With the cap disabled
 * the gauges read 0 and Redis is not touched. If a sample fails, the gauges
 * keep their last values and a warning is logged.
 */
@Component
public class BridgeAggregateWindowMetrics {

    private static final Logger log = LoggerFactory.getLogger(BridgeAggregateWindowMetrics.class);

    static final String OPEN_WINDOWS_METRIC = "bridge_mode.aggregate.open_windows";
    static final String NEAR_CAP_METRIC = "bridge_mode.aggregate.windows_near_cap";
    static final String OVERDUE_METRIC = "bridge_mode.aggregate.windows_overdue";
    static final int NEAR_CAP_PERCENT = 80;

    private final ShadowLedger shadowLedger;
    private final BridgeAggregateCapPolicy policy;
    private final ExecutorService executor;

    private final AtomicInteger openWindows = new AtomicInteger();
    private final AtomicInteger windowsNearCap = new AtomicInteger();
    private final AtomicInteger windowsOverdue = new AtomicInteger();

    @Autowired
    public BridgeAggregateWindowMetrics(ShadowLedger shadowLedger,
                                        BridgeAggregateCapPolicy policy,
                                        MeterRegistry meterRegistry) {
        this(shadowLedger, policy, meterRegistry,
                new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1),
                        runnable -> {
                            Thread thread = new Thread(runnable, "bridge-aggregate-metrics");
                            thread.setDaemon(true);
                            return thread;
                        }, new ThreadPoolExecutor.DiscardPolicy()));
    }

    /** Visible for tests: lets a test run samples synchronously. */
    BridgeAggregateWindowMetrics(ShadowLedger shadowLedger,
                                 BridgeAggregateCapPolicy policy,
                                 MeterRegistry meterRegistry,
                                 ExecutorService executor) {
        this.shadowLedger = shadowLedger;
        this.policy = policy;
        this.executor = executor;
        gauge(meterRegistry, OPEN_WINDOWS_METRIC, openWindows,
                "Accounts currently capped by an open bridge-mode aggregate window");
        gauge(meterRegistry, NEAR_CAP_METRIC, windowsNearCap,
                "Open aggregate windows at " + NEAR_CAP_PERCENT + "% of the cap or more");
        gauge(meterRegistry, OVERDUE_METRIC, windowsOverdue,
                "Aggregate windows open longer than expected-window-hours");
    }

    private static void gauge(MeterRegistry registry, String name, AtomicInteger value, String description) {
        Gauge.builder(name, value, AtomicInteger::get).description(description).register(registry);
    }

    @Scheduled(fixedDelayString = "${openfednow.bridge-mode.metrics-sample-interval-seconds:60}000")
    public void sample() {
        // off the shared scheduler thread; DiscardPolicy skips a tick if the
        // previous sample is still running
        executor.execute(this::sampleNow);
    }

    /** One synchronous sample; package-private for tests. */
    void sampleNow() {
        if (!policy.isEnabled()) {
            openWindows.set(0);
            windowsNearCap.set(0);
            windowsOverdue.set(0);
            return;
        }
        try {
            BigDecimal nearCapFrom = policy.getMaxAggregateSendAmount()
                    .multiply(BigDecimal.valueOf(NEAR_CAP_PERCENT))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            Instant overdueBefore = Instant.now().minus(Duration.ofHours(policy.getExpectedWindowHours()));
            int open = 0;
            int nearCap = 0;
            int overdue = 0;
            for (String accountId : shadowLedger.openAggregateAccountIds()) {
                BridgeAggregateView view = shadowLedger.getAggregateView(accountId);
                if (view == null) {
                    continue; // closed between the SCAN and the read
                }
                open++;
                if (view.totalCounted().compareTo(nearCapFrom) >= 0) {
                    nearCap++;
                }
                if (view.openedAt() != null && view.openedAt().isBefore(overdueBefore)) {
                    overdue++;
                }
            }
            openWindows.set(open);
            windowsNearCap.set(nearCap);
            windowsOverdue.set(overdue);
            if (overdue > 0) {
                log.warn("{} bridge aggregate window(s) have been open longer than {}h — "
                                + "the capped accounts need reconciliation or investigation",
                        overdue, policy.getExpectedWindowHours());
            }
        } catch (RuntimeException e) {
            log.warn("Bridge aggregate window sample failed — gauges keep their last values", e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
