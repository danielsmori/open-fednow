package io.openfednow.processing.saga;

import io.openfednow.processing.saga.PaymentSaga.SagaState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Layer 3 — Recovers in-flight payment sagas after an application restart.
 *
 * <p>Saga state is durable: every transition is persisted to {@code saga_state}
 * before being acted upon (see {@link SagaOrchestrator}). When the middleware
 * restarts, sagas in non-terminal states are orphaned with no thread of execution
 * driving them forward. This service queries those rows on {@link ApplicationReadyEvent}
 * and routes each to the appropriate completion path:
 *
 * <table>
 *   <caption>Recovery dispatch by saga state</caption>
 *   <tr><th>State at restart</th><th>Recovery action</th><th>Rationale</th></tr>
 *   <tr><td>INITIATED</td><td>compensate (NARR)</td>
 *       <td>No funds reserved, no core call — safe to fail closed</td></tr>
 *   <tr><td>FUNDS_RESERVED</td><td>compensate (NARR)</td>
 *       <td>Shadow Ledger debit is reversed; pacs.004 path triggered if applicable</td></tr>
 *   <tr><td>CORE_SUBMITTED</td><td>compensate (NARR)</td>
 *       <td>Core outcome unknown; reverse debit and let reconciliation reconcile</td></tr>
 *   <tr><td>FEDNOW_CONFIRMED</td><td>advance to COMPLETED</td>
 *       <td>Settlement confirmed pre-restart; only the final state transition was lost</td></tr>
 *   <tr><td>COMPENSATING</td><td>advance to FAILED</td>
 *       <td>Compensation was already in progress; finalize the terminal state</td></tr>
 * </table>
 *
 * <p>Recovery is naturally idempotent: the query only returns non-terminal sagas, so
 * a second invocation after all sagas have reached COMPLETED or FAILED is a no-op.
 */
@Component
public class SagaRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(SagaRecoveryService.class);

    /** ISO 20022 reason code recorded against sagas compensated due to an interrupted restart. */
    static final String RECOVERY_REASON = "NARR";

    private final SagaOrchestrator orchestrator;
    private final JdbcTemplate jdbc;

    public SagaRecoveryService(SagaOrchestrator orchestrator, JdbcTemplate jdbc) {
        this.orchestrator = orchestrator;
        this.jdbc = jdbc;
    }

    /**
     * Recovers all in-flight sagas at application startup.
     *
     * <p>Triggered by {@link ApplicationReadyEvent} so that all beans
     * (including {@link SagaOrchestrator}) are fully initialized before recovery
     * runs. Returns the number of sagas processed — primarily for testing.
     */
    @EventListener(ApplicationReadyEvent.class)
    public int recoverInflightSagasOnStartup() {
        return recoverInflightSagas();
    }

    /**
     * Recovers all non-terminal sagas now. Exposed so tests can drive recovery
     * without triggering a Spring context refresh.
     */
    public int recoverInflightSagas() {
        List<String> staleSagaIds = jdbc.queryForList(
                """
                SELECT saga_id FROM saga_state
                WHERE state NOT IN ('COMPLETED', 'FAILED')
                ORDER BY updated_at ASC
                """,
                String.class);

        if (staleSagaIds.isEmpty()) {
            log.info("Saga recovery: no in-flight sagas found at startup");
            return 0;
        }

        log.warn("Saga recovery: found {} in-flight saga(s) — completing or compensating each",
                staleSagaIds.size());

        int recovered = 0;
        for (String sagaId : staleSagaIds) {
            try {
                recoverOne(sagaId);
                recovered++;
            } catch (Exception e) {
                log.error("Saga recovery failed for sagaId={} — manual intervention required",
                        sagaId, e);
            }
        }
        log.info("Saga recovery complete: {} of {} saga(s) processed",
                recovered, staleSagaIds.size());
        return recovered;
    }

    private void recoverOne(String sagaId) {
        PaymentSaga saga = orchestrator.resume(sagaId);
        SagaState state = saga.getState();
        log.info("Recovering sagaId={} fromState={} sourceRail={}",
                sagaId, state, saga.getSourceRail());

        switch (state) {
            case FEDNOW_CONFIRMED ->
                    orchestrator.advance(saga, SagaState.COMPLETED);
            case COMPENSATING ->
                    orchestrator.advance(saga, SagaState.FAILED);
            case INITIATED, FUNDS_RESERVED, CORE_SUBMITTED ->
                    orchestrator.compensate(sagaId, RECOVERY_REASON);
            case COMPLETED, FAILED -> {
                // The SELECT filtered these out, but be defensive against races.
                log.debug("Saga sagaId={} already in terminal state {} — skipping", sagaId, state);
            }
        }
    }
}
