package io.openfednow.processing.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openfednow.iso20022.Pacs002Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Layer 3 — Idempotency Service
 *
 * <p>Prevents duplicate transaction processing in the event of retries,
 * network failures, or FedNow resubmissions.
 *
 * <p>FedNow may retransmit a pacs.008 if it does not receive a timely
 * pacs.002 acknowledgment. Without idempotency controls, this could result
 * in the same payment being posted to the core banking system twice.
 *
 * <p>Two-tier storage:
 * <ul>
 *   <li>Redis — sub-millisecond duplicate check during normal processing.
 *       Key {@code idempotency:{endToEndId}} is stored as a JSON-encoded
 *       {@link Pacs002Message} with a 48-hour TTL.</li>
 *   <li>PostgreSQL — durable backing store that survives Redis restarts;
 *       queried as a fallback when the Redis key has expired or is missing.</li>
 * </ul>
 */
@Component
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** How long to retain idempotency records (FedNow retry window is 24 hours). */
    private static final long RETENTION_HOURS = 48;
    private static final String KEY_PREFIX = "idempotency:";

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IdempotencyService(StringRedisTemplate redis, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Checks whether a transaction has already been processed.
     *
     * <p>Redis is checked first (fast path). If the key is absent (Redis restart
     * or TTL expired), the PostgreSQL table is queried as a fallback.
     *
     * @param endToEndId the end-to-end transaction identifier from the pacs.008
     * @return the original pacs.002 response if the transaction was already processed,
     *         or empty if this is a new transaction
     */
    public Optional<Pacs002Message> checkDuplicate(String endToEndId) {
        // Fast path — Redis
        String cached = redis.opsForValue().get(KEY_PREFIX + endToEndId);
        if (cached != null) {
            try {
                log.debug("Idempotency cache hit e2e={}", endToEndId);
                return Optional.of(objectMapper.readValue(cached, Pacs002Message.class));
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize cached idempotency response e2e={}", endToEndId, e);
            }
        }

        // Fallback — PostgreSQL (covers Redis restart / key expiry edge cases)
        List<Pacs002Message> rows = jdbc.query(
                "SELECT response_status, response_reason_code FROM idempotency_keys " +
                "WHERE end_to_end_id = ?",
                (rs, rowNum) -> {
                    Pacs002Message response = new Pacs002Message();
                    response.setOriginalEndToEndId(endToEndId);
                    response.setTransactionStatus(
                            Pacs002Message.TransactionStatus.valueOf(rs.getString("response_status")));
                    response.setRejectReasonCode(rs.getString("response_reason_code"));
                    return response;
                },
                endToEndId);

        if (!rows.isEmpty()) {
            log.debug("Idempotency DB hit e2e={}", endToEndId);
        }
        return rows.stream().findFirst();
    }

    /**
     * Records the outcome of a processed transaction for future duplicate detection.
     *
     * <p>Writes to Redis (with TTL) and to PostgreSQL (durable). The DB insert
     * uses {@code ON CONFLICT DO NOTHING} so concurrent recordings of the same
     * outcome are safe.
     *
     * @param endToEndId  the end-to-end transaction identifier
     * @param response    the pacs.002 response that was returned
     */
    public void recordOutcome(String endToEndId, Pacs002Message response) {
        // Write to Redis with TTL
        try {
            String json = objectMapper.writeValueAsString(response);
            redis.opsForValue().set(KEY_PREFIX + endToEndId, json, Duration.ofHours(RETENTION_HOURS));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize idempotency response e2e={}", endToEndId, e);
        }

        // Write to PostgreSQL for durability
        OffsetDateTime now = OffsetDateTime.now();
        String status = response.getTransactionStatus() != null
                ? response.getTransactionStatus().name()
                : Pacs002Message.TransactionStatus.ACSC.name();

        jdbc.update(
                """
                INSERT INTO idempotency_keys
                    (end_to_end_id, message_id, response_status, response_reason_code,
                     processed_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (end_to_end_id) DO NOTHING
                """,
                endToEndId,
                response.getOriginalEndToEndId() != null ? response.getOriginalEndToEndId() : endToEndId,
                status,
                response.getRejectReasonCode(),
                now,
                now.plusHours(RETENTION_HOURS));

        log.debug("Idempotency outcome recorded e2e={} status={}", endToEndId, status);
    }
}
