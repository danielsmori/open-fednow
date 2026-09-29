-- SUBMITTING is durable before the external call. After a lost response or
-- restart, OUTCOME_UNKNOWN keeps the reservation until rail status is verified.
ALTER TABLE saga_state DROP CONSTRAINT chk_saga_state;
ALTER TABLE saga_state ADD CONSTRAINT chk_saga_state CHECK (
    state IN ('INITIATED', 'FUNDS_RESERVED', 'SUBMITTING', 'OUTCOME_UNKNOWN',
              'CORE_SUBMITTED', 'FEDNOW_CONFIRMED', 'COMPLETED',
              'COMPENSATING', 'FAILED')
);
