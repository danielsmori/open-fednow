-- V13: Cross-instance reconciliation lease (#68, PR 3)
--
-- Reconciliation must run on at most one instance at a time: the in-JVM
-- ReentrantLock in ReconciliationService only covers one pod, and with
-- automatic reconciliation on core recovery every pod observes the same
-- offline->online transition at roughly the same moment.
--
-- A single-row lease table is used instead of a Postgres advisory lock:
-- advisory locks are session-scoped, and with a pooled datasource a cycle
-- spanning many short transactions cannot hold one session reliably. The
-- lease is time-bounded, so an instance that crashes mid-cycle never blocks
-- reconciliation for good — its lease simply expires.
--
-- holder        instance identity (hostname + per-JVM suffix); NULL = free
-- leased_until  expiry; a lease past this moment is reclaimable by anyone

CREATE TABLE reconciliation_lease (
    id            INTEGER PRIMARY KEY,
    holder        VARCHAR(128),
    leased_until  TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_reconciliation_lease_singleton CHECK (id = 1)
);

INSERT INTO reconciliation_lease (id, holder, leased_until) VALUES (1, NULL, NULL);
