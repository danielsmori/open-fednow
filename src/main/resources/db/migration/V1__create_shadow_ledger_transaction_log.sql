-- V1: Shadow Ledger Transaction Log
--
-- Immutable, append-only audit log of every balance operation applied to
-- the Shadow Ledger. This is the authoritative record of FedNow activity
-- during both normal operation and maintenance-window periods, and the
-- primary input for the ReconciliationService when the core returns online.
--
-- One row per balance operation (DEBIT, CREDIT, REVERSAL, or RECONCILIATION).
-- Rows are never updated or deleted — compensations are new REVERSAL rows.

CREATE TABLE shadow_ledger_transaction_log (
    id                  BIGSERIAL       PRIMARY KEY,

    -- ISO 20022 identifiers (max 35 chars per spec)
    transaction_id      VARCHAR(35)     NOT NULL,
    end_to_end_id       VARCHAR(35)     NOT NULL,

    -- Internal account identifier used by the core banking system
    account_id          VARCHAR(34)     NOT NULL,

    -- DEBIT:          outbound FedNow payment reserved against this account
    -- CREDIT:         inbound FedNow payment credited to this account
    -- REVERSAL:       saga compensation reversal of a prior DEBIT
    -- RECONCILIATION: balance correction applied after core confirmation
    transaction_type    VARCHAR(20)     NOT NULL,

    -- Signed amount in USD (always positive; direction is conveyed by type)
    amount              NUMERIC(19, 2)  NOT NULL,

    -- Balance snapshot before and after this operation (for audit and drift detection)
    balance_before      NUMERIC(19, 2)  NOT NULL,
    balance_after       NUMERIC(19, 2)  NOT NULL,

    -- Nullable: not every operation originates from a saga (e.g., reconciliation)
    saga_id             VARCHAR(50),

    -- FALSE until the core banking system has confirmed the transaction.
    -- Rows where core_confirmed = FALSE are the reconciliation replay queue.
    core_confirmed      BOOLEAN         NOT NULL DEFAULT FALSE,

    applied_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_sltl_transaction_type CHECK (
        transaction_type IN ('DEBIT', 'CREDIT', 'REVERSAL', 'RECONCILIATION')
    ),
    CONSTRAINT chk_sltl_amount_positive CHECK (amount > 0)
);

-- Lookup by FedNow transaction or end-to-end ID
CREATE INDEX idx_sltl_transaction_id  ON shadow_ledger_transaction_log (transaction_id);
CREATE INDEX idx_sltl_end_to_end_id   ON shadow_ledger_transaction_log (end_to_end_id);

-- Balance history and reconciliation range scans per account
CREATE INDEX idx_sltl_account_id      ON shadow_ledger_transaction_log (account_id, applied_at);

-- Time-ordered scan for reconciliation replay after a maintenance window
CREATE INDEX idx_sltl_applied_at      ON shadow_ledger_transaction_log (applied_at);

-- Partial index: only unconfirmed rows — the pending reconciliation queue.
-- This index stays small because confirmed rows are excluded.
CREATE INDEX idx_sltl_pending_confirm ON shadow_ledger_transaction_log (applied_at)
    WHERE core_confirmed = FALSE;
