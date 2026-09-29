ALTER TABLE reliability_account ADD COLUMN core_mode VARCHAR(8) NOT NULL DEFAULT 'SYNC';
ALTER TABLE reliability_account ADD CONSTRAINT chk_reliability_core_mode
    CHECK (core_mode IN ('SYNC', 'ASYNC'));

ALTER TABLE reliability_payment DROP CONSTRAINT chk_reliability_state;
ALTER TABLE reliability_payment ADD CONSTRAINT chk_reliability_state CHECK (
    state IN ('CORE_PENDING', 'RESERVED', 'SUBMITTING', 'OUTCOME_UNKNOWN',
              'SETTLED', 'REJECTED', 'INVESTIGATION')
);

CREATE TABLE reliability_core_ack (
    operation_id VARCHAR(36) PRIMARY KEY REFERENCES reliability_payment(operation_id),
    status VARCHAR(8) NOT NULL DEFAULT 'PENDING',
    acknowledged_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_reliability_ack CHECK (status IN ('PENDING', 'ACKED'))
);
