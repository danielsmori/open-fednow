package io.openfednow.processing.saga;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.openfednow.shadowledger.ShadowLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Layer 3 — Retries Shadow Ledger reversals that failed inside saga compensation.
 *
 * <p>When {@link SagaOrchestrator#compensate(String, String)} or
 * {@link SagaOrchestrator#cancelInboundSaga(String, String)} runs, the
 * Shadow Ledger reversal can fail — Redis briefly unreachable, the audit
 * log write throwing, or any other transient. The current code catches and
 * logs that failure so the saga still reaches {@code FAILED}, but the
 * underlying Shadow Ledger debit or credit is left unreversed. Without a
 * retry path the account stays out of sync until an operator intervenes.
 *
 * <p>This service is the periodic sweep that finds those orphaned reversals
 * and retries them. The query identifies FAILED sagas whose underlying
 * transaction has an original {@code DEBIT} or {@code CREDIT} row but no
 * matching {@code REVERSAL} — exactly the divergence the audit trail
 * surfaces.
 *
 * <p>The retry calls both {@link ShadowLedger#reverseDebit(String)} and
 * {@link ShadowLedger#reverseCredit(String)} on each candidate. Both
 * methods are idempotent (skip on existing REVERSAL) and direction-aware
 * (filter on transaction type), so calling both is safe and cheap — at
 * most one will do real work, the other no-ops.
 *
 * <p>Configurable:
 * <ul>
 *   <li>{@code openfednow.compensation-retry.interval-seconds} — sweep
 *       cadence. Default 300s (5 minutes).</li>
 *   <li>{@code openfednow.compensation-retry.batch-size} — max sagas
 *       retried per sweep. Default 200 — caps the work per run so a
 *       large backlog doesn't dominate a single iteration.</li>
 * </ul>
 *
 * <p>Each retry outcome increments one of two Micrometer counters:
 * {@code saga.compensation.retry.succeeded} or
 * {@code saga.compensation.retry.failed}.
 */
@Component
public class CompensationRetryService {

    private static final Logger log = LoggerFactory.getLogger(CompensationRetryService.class);

    static final String RETRY_SUCCESS_METRIC = "saga.compensation.retry.succeeded";
    static final String RETRY_FAILURE_METRIC = "saga.compensation.retry.failed";

    private final JdbcTemplate jdbc;
    private final ShadowLedger shadowLedger;
    private final int batchSize;
    private final Counter successCounter;
    private final Counter failureCounter;

    public CompensationRetryService(
            JdbcTemplate jdbc,
            ShadowLedger shadowLedger,
            MeterRegistry meterRegistry,
            @Value("${openfednow.compensation-retry.batch-size:200}") int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "openfednow.compensation-retry.batch-size must be positive");
        }
        this.jdbc = jdbc;
        this.shadowLedger = shadowLedger;
        this.batchSize = batchSize;
        this.successCounter = Counter.builder(RETRY_SUCCESS_METRIC)
                .description("Saga compensation reversals that succeeded on retry")
                .register(meterRegistry);
        this.failureCounter = Counter.builder(RETRY_FAILURE_METRIC)
                .description("Saga compensation reversal retries that still failed")
                .register(meterRegistry);
    }

    int getBatchSize() {
        return batchSize;
    }

    /**
     * Periodic sweep. Cadence configurable via
     * {@code openfednow.compensation-retry.interval-seconds} (default 300s).
     *
     * <p>The first sweep waits a full interval after startup so the saga
     * recovery service ({@link SagaRecoveryService}) — which runs on
     * {@code ApplicationReadyEvent} — completes first and does not race
     * with this scheduled retry.
     */
    @Scheduled(
            fixedDelayString = "#{${openfednow.compensation-retry.interval-seconds:300} * 1000}",
            initialDelayString = "#{${openfednow.compensation-retry.interval-seconds:300} * 1000}"
    )
    public int retryFailedCompensations() {
        // Find FAILED sagas whose Shadow Ledger reversal didn't complete.
        // The schema doesn't carry a direct "reversal pending" flag, so the
        // truth is in the audit log: a transaction has an original DEBIT or
        // CREDIT but no REVERSAL.
        // transaction_id is UNIQUE on saga_state (uq_saga_transaction_id) so no
        // DISTINCT is needed — and H2's PG-compat mode rejects DISTINCT combined
        // with ORDER BY on a non-projected column.
        List<String> candidates = jdbc.queryForList(
                """
                SELECT s.transaction_id
                FROM saga_state s
                WHERE s.state = 'FAILED'
                  AND EXISTS (
                      SELECT 1 FROM shadow_ledger_transaction_log o
                      WHERE o.transaction_id = s.transaction_id
                        AND o.transaction_type IN ('DEBIT', 'CREDIT')
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM shadow_ledger_transaction_log r
                      WHERE r.transaction_id = s.transaction_id
                        AND r.transaction_type = 'REVERSAL'
                  )
                ORDER BY s.updated_at ASC
                LIMIT ?
                """,
                String.class, batchSize);

        if (candidates.isEmpty()) {
            log.debug("Compensation retry: no FAILED sagas missing reversals");
            return 0;
        }

        log.info("Compensation retry: found {} FAILED saga(s) needing reversal",
                candidates.size());

        int succeeded = 0;
        for (String txnId : candidates) {
            try {
                // Both methods are idempotent and direction-aware. Calling both is
                // safe: the one whose transaction_type filter matches does the work;
                // the other no-ops on the existing-REVERSAL check we add next.
                shadowLedger.reverseDebit(txnId);
                shadowLedger.reverseCredit(txnId);
                log.info("Compensation retry succeeded transactionId={}", txnId);
                successCounter.increment();
                succeeded++;
            } catch (Exception e) {
                log.error("Compensation retry failed transactionId={} — manual intervention may be required",
                        txnId, e);
                failureCounter.increment();
            }
        }
        log.info("Compensation retry sweep complete: {} of {} succeeded",
                succeeded, candidates.size());
        return succeeded;
    }
}
