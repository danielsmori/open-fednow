package io.openfednow.processing.saga;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Layer 3 — Detects payment sagas that have stalled in a forward-progress
 * state and triggers their compensation.
 *
 * <p>A saga that stalls in {@code FUNDS_RESERVED} or {@code CORE_SUBMITTED}
 * indefinitely holds funds in the Shadow Ledger and exposes the institution
 * to drift between Shadow Ledger and core balances. This monitor sweeps the
 * saga_state table on a fixed schedule, finds sagas whose {@code created_at}
 * exceeds the configured timeout, and routes each through the standard
 * compensation path with reason code {@code XPIR} (ISO 20022: Expired).
 *
 * <p>The reason code <em>XPIR</em> is distinct from the {@code NARR} used by
 * {@code SagaRecoveryService} on startup recovery, so operational dashboards
 * and alert rules can tell the two failure modes apart.
 *
 * <p>Each timed-out saga produces:
 * <ul>
 *   <li>A structured WARN log line, suitable for routing to alerts</li>
 *   <li>An increment to the {@code saga.timeout} Micrometer counter (exported
 *       via {@code /actuator/metrics})</li>
 *   <li>An invocation of {@link SagaOrchestrator#compensate(String, String)}
 *       which reverses the Shadow Ledger debit if funds had been reserved</li>
 * </ul>
 */
@Component
public class SagaTimeoutMonitor {

    private static final Logger log = LoggerFactory.getLogger(SagaTimeoutMonitor.class);

    /** ISO 20022 reason code recorded on the saga when it is compensated for timeout. */
    static final String TIMEOUT_REASON_CODE = "XPIR";

    /** Name of the Micrometer counter incremented on every timed-out saga. */
    static final String TIMEOUT_METRIC = "saga.timeout";

    private final SagaOrchestrator orchestrator;
    private final int timeoutSeconds;
    private final Counter timeoutCounter;

    public SagaTimeoutMonitor(SagaOrchestrator orchestrator,
                              MeterRegistry meterRegistry,
                              @Value("${openfednow.saga.timeout-seconds:30}") int timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("openfednow.saga.timeout-seconds must be positive");
        }
        this.orchestrator = orchestrator;
        this.timeoutSeconds = timeoutSeconds;
        this.timeoutCounter = Counter.builder(TIMEOUT_METRIC)
                .description("Number of payment sagas compensated due to timeout")
                .register(meterRegistry);
    }

    int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * Periodic sweep. Default cadence is 10 seconds; override via
     * {@code openfednow.saga.timeout-check-interval-seconds}.
     */
    @Scheduled(
            fixedDelayString = "#{${openfednow.saga.timeout-check-interval-seconds:10} * 1000}",
            initialDelayString = "#{${openfednow.saga.timeout-check-interval-seconds:10} * 1000}"
    )
    public int sweepTimedOutSagas() {
        List<String> staleIds = orchestrator.findTimedOutSagaIds(timeoutSeconds);
        if (staleIds.isEmpty()) {
            log.debug("Saga timeout monitor: no sagas exceeded {}s threshold", timeoutSeconds);
            return 0;
        }
        log.warn("Saga timeout monitor: {} saga(s) exceeded {}s threshold — compensating",
                staleIds.size(), timeoutSeconds);

        int compensated = 0;
        for (String sagaId : staleIds) {
            try {
                log.warn("Saga timed out sagaId={} thresholdSeconds={} action=COMPENSATE reason={}",
                        sagaId, timeoutSeconds, TIMEOUT_REASON_CODE);
                orchestrator.compensate(sagaId, TIMEOUT_REASON_CODE);
                timeoutCounter.increment();
                compensated++;
            } catch (Exception e) {
                log.error("Saga timeout compensation failed for sagaId={} — manual intervention required",
                        sagaId, e);
            }
        }
        return compensated;
    }
}
