package io.openfednow.shadowledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared, durable "a reconciliation is owed" marker — issue #68.
 *
 * <p>Every instance sets it on each availability poll that finds the core
 * offline, when it queues an inbound payment for the core, and when it counts
 * an outbound send toward a bridge-mode aggregate window (cap enabled). Any
 * instance that sees the core online while it is set attempts a
 * reconciliation (via {@link AutomaticReconciliationTrigger}). Because it lives
 * in Redis rather than in one JVM:
 * <ul>
 *   <li>a restarted instance still knows reconciliation is owed;</li>
 *   <li>an instance that never saw the outage itself still runs it;</li>
 *   <li>a short outage that falls between two availability polls is still
 *       caught if an inbound payment was queued or a capped send was counted
 *       during it. Outbound sends with the cap disabled do not mark; such an
 *       outage is reconciled by the next one that is noticed, or manually.</li>
 * </ul>
 *
 * <p>Each {@link #mark} writes a fresh token. A cycle reads the token before it
 * starts and clears the marker only if the token is unchanged when it finishes
 * ({@link #clearIfUnchanged}), so bridge-mode activity <em>during</em> a cycle —
 * the core flapping, a late instance noticing an outage — keeps it set and
 * produces another cycle instead of being silently absorbed.
 */
@Component
public class ReconciliationPendingMarker {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationPendingMarker.class);

    static final String PENDING_KEY = "reconciliation:pending";

    /** Compare-and-delete: deletes KEYS[1] only if it still holds ARGV[1]. */
    private static final RedisScript<Long> CLEAR_IF_UNCHANGED_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then\n" +
                    "  return redis.call('DEL', KEYS[1])\n" +
                    "end\n" +
                    "return 0",
            Long.class);

    private final StringRedisTemplate redis;

    public ReconciliationPendingMarker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Records that a reconciliation is owed. Never throws: a marker failure must
     * not fail the payment or poll that triggered it. Callers on the payment
     * path depend on Redis for the Shadow Ledger itself, and the availability
     * poller re-marks on every offline poll, so a lost mark is re-attempted.
     */
    public void mark(String reason) {
        try {
            redis.opsForValue().set(PENDING_KEY, UUID.randomUUID().toString());
            log.debug("Reconciliation marked pending reason={}", reason);
        } catch (RuntimeException e) {
            log.warn("Could not mark reconciliation pending reason={}", reason, e);
        }
    }

    /** The current token, or empty when no reconciliation is owed. */
    public Optional<String> currentToken() {
        return Optional.ofNullable(redis.opsForValue().get(PENDING_KEY));
    }

    /**
     * Clears the marker if it still holds {@code token}.
     *
     * @return {@code true} if cleared; {@code false} if it was re-marked since
     *         the token was read (another cycle is owed) or is already gone
     */
    public boolean clearIfUnchanged(String token) {
        Long deleted = redis.execute(CLEAR_IF_UNCHANGED_SCRIPT, List.of(PENDING_KEY), token);
        return deleted != null && deleted == 1L;
    }
}
