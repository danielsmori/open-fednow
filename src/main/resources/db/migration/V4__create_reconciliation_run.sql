-- V4: Reconciliation Run
--
-- Audit log of every reconciliation cycle executed by ReconciliationService.
-- A reconciliation run is triggered each time the core banking system returns
-- online after a maintenance window, and replays all queued transactions.
--
-- This table is the operational record that allows engineers and auditors to
-- verify that every maintenance window was reconciled correctly, how many
-- transactions were processed, and whether any discrepancies were detected.
--
-- The zero-discrepancy policy means discrepancies_detected > 0 should
-- always trigger an alert and halt further queueing until resolved.

CREATE TABLE reconciliation_run (
    id                      BIGSERIAL       PRIMARY KEY,

    started_at              TIMESTAMP WITH TIME ZONE     NOT NULL DEFAULT NOW(),

    -- NULL until the run completes (successfully or with errors)
    completed_at            TIMESTAMP WITH TIME ZONE,

    -- Count of transactions replayed from the RabbitMQ queue against the core
    transactions_replayed   INT             NOT NULL DEFAULT 0,

    -- Count of balance discrepancies detected between Shadow Ledger and core.
    -- Any value > 0 is a critical alert — zero tolerance is enforced.
    discrepancies_detected  INT             NOT NULL DEFAULT 0,

    -- NULL while in progress; TRUE = clean reconciliation, FALSE = discrepancies found
    successful              BOOLEAN,

    -- Free-text summary from ReconciliationReport (error details, discrepancy info)
    summary                 TEXT,

    -- How this reconciliation was initiated
    triggered_by            VARCHAR(20)     NOT NULL DEFAULT 'SCHEDULED',

    CONSTRAINT chk_recon_triggered_by CHECK (
        triggered_by IN ('SCHEDULED', 'MANUAL')
    ),
    CONSTRAINT chk_recon_discrepancies_non_negative CHECK (
        discrepancies_detected >= 0
    ),
    CONSTRAINT chk_recon_replayed_non_negative CHECK (
        transactions_replayed >= 0
    )
);

-- Time-range queries: "show me all reconciliation runs in the last 7 days"
CREATE INDEX idx_recon_started_at ON reconciliation_run (started_at);

-- Index to surface failed reconciliation runs.
-- On PostgreSQL in production this can be narrowed with WHERE successful = FALSE;
-- H2 does not support partial index syntax.
CREATE INDEX idx_recon_failed ON reconciliation_run (successful, started_at);
