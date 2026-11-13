package io.openfednow.shadowledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ReconciliationLock} backed by a PostgreSQL session-level advisory lock
 * ({@code pg_try_advisory_lock}) — issue #68.
 *
 * <p>The lock belongs to a database <em>session</em>, so the lease keeps one
 * dedicated connection checked out of the pool for the whole cycle (the
 * cycle's own work uses other connections). If the holding instance dies or
 * loses its connection, PostgreSQL ends the session and releases the lock
 * itself — no TTL, no fencing token, and no chance of a lock outliving its
 * holder.
 *
 * <p>Deployment notes:
 * <ul>
 *   <li>Every instance must reach the <em>same</em> PostgreSQL primary.</li>
 *   <li>Session-level advisory locks do not work through a connection pooler in
 *       transaction-pooling mode (e.g. PgBouncer {@code pool_mode=transaction}):
 *       the session that took the lock is not the one that releases it. Use
 *       session pooling or a direct connection.</li>
 *   <li>A cycle holds one extra pooled connection while it runs; size the pool
 *       accordingly.</li>
 * </ul>
 */
public class PostgresAdvisoryReconciliationLock implements ReconciliationLock {

    private static final Logger log = LoggerFactory.getLogger(PostgresAdvisoryReconciliationLock.class);

    /**
     * Advisory lock key, application-wide. The bytes spell "OFNRECON"; any
     * other code taking advisory locks in the same database must avoid it.
     */
    static final long LOCK_KEY = 0x4F464E5245434F4EL;

    /**
     * Whether the current session holds the lock. A single-bigint advisory key
     * appears in {@code pg_locks} split into {@code classid} (high 32 bits) and
     * {@code objid} (low 32 bits), with {@code objsubid = 1}.
     */
    static final String HELD_BY_THIS_SESSION_SQL =
            "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype = 'advisory' AND granted "
                    + "AND pid = pg_backend_pid() AND classid = ?::bigint::oid "
                    + "AND objid = ?::bigint::oid AND objsubid = 1)";

    private final DataSource dataSource;

    public PostgresAdvisoryReconciliationLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<Lease> tryAcquire() {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot reach PostgreSQL to take the reconciliation lock", e);
        }
        try {
            // Session-level locks are independent of transactions; autocommit
            // keeps the connection from sitting idle inside a transaction.
            connection.setAutoCommit(true);
            boolean acquired;
            try (PreparedStatement ps = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, LOCK_KEY);
                try (ResultSet rs = ps.executeQuery()) {
                    acquired = rs.next() && rs.getBoolean(1);
                }
            }
            if (!acquired) {
                closeQuietly(connection);
                return Optional.empty();
            }
            log.debug("Reconciliation advisory lock acquired");
            return Optional.of(new AdvisoryLease(connection));
        } catch (SQLException | RuntimeException e) {
            // We may or may not hold the lock; ending the session releases it either way.
            abortQuietly(connection);
            throw new IllegalStateException("Failed to take the reconciliation advisory lock", e);
        }
    }

    private static final class AdvisoryLease implements Lease {
        private final Connection connection;
        private final AtomicBoolean closed = new AtomicBoolean();

        AdvisoryLease(Connection connection) {
            this.connection = connection;
        }

        /**
         * Asks PostgreSQL, on the lease's own session, whether that session
         * still holds the lock. Also keeps the session from sitting idle for
         * the whole cycle. Any error counts as "not held".
         */
        @Override
        public boolean stillHeld() {
            if (closed.get()) {
                return false;
            }
            try (PreparedStatement ps = connection.prepareStatement(HELD_BY_THIS_SESSION_SQL)) {
                ps.setLong(1, LOCK_KEY >>> 32);
                ps.setLong(2, LOCK_KEY & 0xFFFFFFFFL);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            } catch (SQLException | RuntimeException e) {
                log.warn("Reconciliation advisory lock liveness check failed — treating the lock as lost", e);
                return false;
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            boolean released = false;
            try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, LOCK_KEY);
                try (ResultSet rs = ps.executeQuery()) {
                    released = rs.next() && rs.getBoolean(1);
                }
            } catch (SQLException | RuntimeException e) {
                log.warn("Releasing the reconciliation advisory lock failed — ending the session instead", e);
            }
            if (released) {
                log.debug("Reconciliation advisory lock released");
                closeQuietly(connection);
            } else {
                // Never hand a connection that may still hold the lock back to the
                // pool: a pooled session would keep the lock forever. Aborting the
                // physical connection ends the session, which releases the lock.
                abortQuietly(connection);
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException | RuntimeException e) {
            log.debug("Closing reconciliation lock connection failed", e);
        }
    }

    private static void abortQuietly(Connection connection) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException e) {
            log.debug("Aborting reconciliation lock connection failed", e);
        }
        closeQuietly(connection);
    }
}
