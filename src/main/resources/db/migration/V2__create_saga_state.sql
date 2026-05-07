-- V2: Saga State
--
-- Durable persistence for the PaymentSaga state machine. Ensures that if
-- the middleware restarts mid-saga (e.g., during a compensation sequence),
-- the SagaOrchestrator can resume from the last known state rather than
-- losing track of in-flight payments.
--
-- One row per payment saga. State transitions update the existing row;
-- the updated_at column tracks the last transition timestamp.

CREATE TABLE saga_state (
    -- Saga identifier assigned by SagaOrchestrator at initiation
    saga_id             VARCHAR(50)     PRIMARY KEY,

    -- ISO 20022 identifiers linking this saga to its pacs.008
    transaction_id      VARCHAR(35)     NOT NULL,
    end_to_end_id       VARCHAR(35)     NOT NULL,

    -- Current state in the saga lifecycle:
    --   INITIATED → FUNDS_RESERVED → CORE_SUBMITTED → FEDNOW_CONFIRMED → COMPLETED
    --   Any state → COMPENSATING → FAILED  (error path)
    state               VARCHAR(25)     NOT NULL,

    -- Set when compensation is triggered — the ISO 20022 reason code
    -- that caused the failure (e.g., AM04, AC04, NARR)
    return_reason_code  VARCHAR(4),

    -- Human-readable description of the failure or compensation reason
    failure_description VARCHAR(256),

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_saga_transaction_id  UNIQUE (transaction_id),
    CONSTRAINT uq_saga_end_to_end_id   UNIQUE (end_to_end_id),
    CONSTRAINT chk_saga_state CHECK (
        state IN (
            'INITIATED',
            'FUNDS_RESERVED',
            'CORE_SUBMITTED',
            'FEDNOW_CONFIRMED',
            'COMPLETED',
            'COMPENSATING',
            'FAILED'
        )
    )
);

-- Look up saga by pacs.008 transaction ID (used during reconciliation)
CREATE INDEX idx_saga_transaction_id ON saga_state (transaction_id);

-- Find all sagas in a given state (e.g., alert on stuck COMPENSATING sagas)
CREATE INDEX idx_saga_state          ON saga_state (state);

-- Monitor long-running sagas — find sagas not updated within expected window
CREATE INDEX idx_saga_updated_at     ON saga_state (updated_at);
