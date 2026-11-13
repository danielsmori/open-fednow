package io.openfednow.shadowledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Cross-instance lease that serializes reconciliation cycles — issue #68, PR 3.
 *
 * <p>With automatic reconciliation on core recovery, every instance observes
 * the same offline→online transition at roughly the same moment. The in-JVM
 * lock in {@link ReconciliationService} covers one pod; this lease covers the
 * deployment. It is a single row in {@code reconciliation_lease}, acquired and
 * released with plain conditional UPDATEs, so it works on any SQL database and
 * never pins a pooled connection the way a session-scoped Postgres advisory
 * lock would across a cycle made of many short transactions.
 *
 * <p><strong>Crash safety:</strong> the lease is time-bounded
 * ({@code openfednow.reconciliation.lease-seconds}, default 900). An instance
 * that dies mid-cycle leaves a lease that simply expires; the next cycle
 * reclaims it. A live holder renews the lease between batches, and a renewal
 * that discovers the lease was taken over aborts the cycle rather than run
 * concurrently with the new holder.
 *
 * <p><strong>Clock caveat:</strong> expiry timestamps are written from the
 * application clock and compared against the database clock ({@code NOW()}),
 * so the effective lease length varies by the app↔database clock skew. Keep
 * NTP healthy (reconciliation replay ordering already depends on it — see
 * known-limitations #3) and the lease long relative to plausible skew.
 */
@Component
public class ReconciliationLease {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationLease.class);

    private final JdbcTemplate jdbc;
    private final int leaseSeconds;

    /** This instance's identity: hostname plus a per-JVM random suffix. */
    private final String holderId;

    @Autowired
    public ReconciliationLease(JdbcTemplate jdbc,
                               @Value("${openfednow.reconciliation.lease-seconds:900}") int leaseSeconds) {
        this(jdbc, leaseSeconds, defaultHolderId());
    }

    /** Visible for tests: lets two "instances" share one database. */
    ReconciliationLease(JdbcTemplate jdbc, int leaseSeconds, String holderId) {
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException(
                    "openfednow.reconciliation.lease-seconds must be positive");
        }
        this.jdbc = jdbc;
        this.leaseSeconds = leaseSeconds;
        this.holderId = holderId;
    }

    private static String defaultHolderId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown-host";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Visible for tests and log correlation. */
    String getHolderId() {
        return holderId;
    }

    /**
     * Tries to take the lease. Succeeds when the lease is free, expired, or
     * already held by this instance (re-entrant across retriggers).
     *
     * @return {@code true} if this instance now holds the lease
     */
    public boolean tryAcquire() {
        int updated = jdbc.update("""
                UPDATE reconciliation_lease
                SET holder = ?, leased_until = ?
                WHERE id = 1
                  AND (holder IS NULL OR leased_until IS NULL OR leased_until < NOW() OR holder = ?)
                """,
                holderId, expiry(), holderId);
        if (updated == 1) {
            log.debug("Reconciliation lease acquired holder={}", holderId);
            return true;
        }
        // 0 rows updated normally means another instance holds the lease — but
        // it is also what a missing row looks like (e.g. someone truncated the
        // table). That state would otherwise block reconciliation forever with
        // a misleading log line, so self-heal it once.
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_lease WHERE id = 1", Integer.class);
        if (rows != null && rows == 0) {
            log.warn("Reconciliation lease row missing — recreating it");
            try {
                jdbc.update("INSERT INTO reconciliation_lease (id, holder, leased_until) "
                        + "VALUES (1, ?, ?)", holderId, expiry());
                log.debug("Reconciliation lease acquired holder={} (row recreated)", holderId);
                return true;
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // another instance recreated it first and holds it
            }
        }
        log.info("Reconciliation lease unavailable — another instance is reconciling");
        return false;
    }

    /**
     * Extends the lease mid-cycle. Fails only when the lease is no longer
     * held by this instance — i.e. it expired and another instance took it —
     * in which case the caller must abort its cycle.
     *
     * @return {@code true} if the lease is still this instance's
     */
    public boolean renew() {
        int updated = jdbc.update(
                "UPDATE reconciliation_lease SET leased_until = ? WHERE id = 1 AND holder = ?",
                expiry(), holderId);
        if (updated != 1) {
            log.error("Reconciliation lease lost mid-cycle holder={} — another instance took over; "
                    + "aborting this cycle", holderId);
            return false;
        }
        return true;
    }

    /**
     * Frees the lease if this instance still holds it. Safe to call when it
     * does not (e.g. after a lost renewal): the conditional UPDATE no-ops.
     */
    public void release() {
        int updated = jdbc.update(
                "UPDATE reconciliation_lease SET holder = NULL, leased_until = NULL "
                        + "WHERE id = 1 AND holder = ?",
                holderId);
        if (updated == 1) {
            log.debug("Reconciliation lease released holder={}", holderId);
        }
    }

    private Timestamp expiry() {
        return Timestamp.from(Instant.now().plusSeconds(leaseSeconds));
    }
}
