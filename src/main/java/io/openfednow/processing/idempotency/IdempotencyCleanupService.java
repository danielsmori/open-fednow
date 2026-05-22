package io.openfednow.processing.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Layer 3 — Sweeps expired idempotency records from PostgreSQL.
 *
 * <p>{@link IdempotencyService} writes every processed payment outcome to both
 * Redis (with native TTL via {@code SETEX}) and PostgreSQL (durable backing
 * store, with an {@code expires_at} timestamp column). Redis entries are
 * reclaimed automatically when the TTL elapses; the PostgreSQL rows accumulate
 * unless explicitly deleted. This service is the periodic sweep.
 *
 * <p>The query is supported by {@code idx_idempotency_expires_at}, so the cost
 * scales with the number of rows actually being deleted, not with the table
 * size. The sweep is safe to run concurrently with normal traffic: it touches
 * only already-expired rows that no live request could be matching.
 *
 * <p>Schedule is driven by {@code openfednow.idempotency.cleanup-interval-minutes}
 * (default 60). Logs at INFO when rows are deleted and at DEBUG otherwise to
 * keep operational dashboards quiet during idle periods.
 */
@Component
public class IdempotencyCleanupService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupService.class);

    private final JdbcTemplate jdbc;

    public IdempotencyCleanupService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Deletes idempotency rows whose {@code expires_at} is in the past.
     *
     * <p>Configurable interval; default is one hour. The cron expression
     * {@code ${...:60}} pattern resolves to {@code <minutes> minutes in
     * milliseconds} — Spring's {@code fixedDelayString} accepts a plain
     * millisecond literal.
     */
    @Scheduled(
            fixedDelayString = "#{${openfednow.idempotency.cleanup-interval-minutes:60} * 60 * 1000}",
            initialDelayString = "#{${openfednow.idempotency.cleanup-interval-minutes:60} * 60 * 1000}"
    )
    public int sweepExpired() {
        int deleted = jdbc.update("DELETE FROM idempotency_keys WHERE expires_at < NOW()");
        if (deleted > 0) {
            log.info("Idempotency cleanup: deleted {} expired row(s)", deleted);
        } else {
            log.debug("Idempotency cleanup: no expired rows");
        }
        return deleted;
    }
}
