package io.openfednow.shadowledger;

import java.util.Optional;

/**
 * Cross-instance mutual exclusion for reconciliation cycles — issue #68.
 *
 * <p>{@link ReconciliationService} already refuses overlapping cycles within one
 * JVM. Once reconciliation starts automatically when the core returns, every
 * instance notices the recovery at about the same time, so the same guarantee
 * is needed across instances: at most one cycle runs anywhere at a time.
 *
 * <p>Implementations must release the lock if the holding instance dies, without
 * relying on that instance to clean up.
 *
 * @see PostgresAdvisoryReconciliationLock
 */
@FunctionalInterface
public interface ReconciliationLock {

    /**
     * Tries to take the lock without waiting.
     *
     * @return a lease to {@link Lease#close() close} when the cycle ends, or empty
     *         when another instance holds the lock
     * @throws RuntimeException if the lock's backing store cannot be reached — the
     *         caller must not run a cycle in that case
     */
    Optional<Lease> tryAcquire();

    /** A held lock. Closing it releases the lock; closing twice is harmless. */
    interface Lease extends AutoCloseable {
        @Override
        void close();

        /**
         * Whether the lock is still held. A lock can be lost without the holder
         * noticing — e.g. its database session is ended by an idle timeout or
         * an operator — after which another instance may take it. Long cycles
         * call this periodically and stop if it returns {@code false}.
         */
        default boolean stillHeld() {
            return true;
        }
    }

    /**
     * No cross-instance exclusion — only the caller's in-JVM guard applies. Used
     * for databases without advisory locks (the H2 test database) and in unit
     * tests. Safe only for single-instance deployments.
     */
    ReconciliationLock LOCAL_ONLY = () -> Optional.of(() -> { });
}
