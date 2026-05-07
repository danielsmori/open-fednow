-- V3: Idempotency Keys
--
-- Durable record of every processed transaction, used to detect and suppress
-- duplicate pacs.008 submissions from FedNow.
--
-- The IdempotencyService uses Redis as the primary store (sub-millisecond
-- lookup, 48-hour TTL). This table is the durable backing store that survives
-- Redis restarts and provides the audit trail required for compliance.
--
-- Rows are keyed by EndToEndId (the ISO 20022 deduplication identifier).
-- FedNow's retry window is 24 hours; rows are retained for 48 hours and
-- cleaned up by a scheduled job using the expires_at index.

CREATE TABLE idempotency_keys (
    -- ISO 20022 EndToEndId — the deduplication key assigned by the originating
    -- institution and carried unchanged through FedNow and the pacs.008
    end_to_end_id           VARCHAR(35)     PRIMARY KEY,

    -- MsgId from the pacs.008 — retained for diagnostic correlation
    message_id              VARCHAR(35)     NOT NULL,

    -- The pacs.002 status returned to FedNow for this payment:
    --   ACSC — accepted and settled
    --   RJCT — rejected (see response_reason_code)
    --   ACSP — accepted, settlement in process (provisional acceptance)
    response_status         VARCHAR(4)      NOT NULL,

    -- ISO 20022 reason code; non-null when response_status = RJCT
    response_reason_code    VARCHAR(4),

    processed_at            TIMESTAMP WITH TIME ZONE     NOT NULL DEFAULT NOW(),

    -- Absolute expiry timestamp (processed_at + 48 hours).
    -- A scheduled cleanup job deletes rows where expires_at < NOW().
    expires_at              TIMESTAMP WITH TIME ZONE     NOT NULL,

    CONSTRAINT chk_idempotency_response_status CHECK (
        response_status IN ('ACSC', 'RJCT', 'ACSP')
    ),
    CONSTRAINT chk_idempotency_reason_on_reject CHECK (
        response_status != 'RJCT' OR response_reason_code IS NOT NULL
    )
);

-- Cleanup job: DELETE FROM idempotency_keys WHERE expires_at < NOW()
CREATE INDEX idx_idempotency_expires_at ON idempotency_keys (expires_at);
