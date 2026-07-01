-- V7: Correlate saga and reconciliation records with admin_audit_log entries
--
-- The admin_audit_log table already carries the CorrelationFilter request_id
-- for every /admin/** call. But saga_state and reconciliation_run had no
-- matching column, so answering "which sagas resulted from this admin
-- action?" or "which audit entry triggered this reconciliation run?" required
-- manual log correlation.
--
-- Adding request_id to both tables gives ops a single-JOIN answer. The column
-- is nullable because most sagas are initiated by inbound payment traffic
-- (which does have a request_id in MDC) but also because scheduled work
-- (SagaTimeoutMonitor, AvailabilityBridge, etc.) runs outside any HTTP
-- request, and reconciliation triggered by the scheduled poll likewise has
-- no request_id.

ALTER TABLE saga_state
    ADD COLUMN request_id VARCHAR(50);

ALTER TABLE reconciliation_run
    ADD COLUMN request_id VARCHAR(50);

-- Look up sagas or reconciliation runs by the request_id from an audit entry.
-- Not a unique index — a single admin request may touch several sagas
-- (e.g., a scheduled reconciliation batch), and the same request_id appears
-- once per saga.
CREATE INDEX idx_saga_request_id           ON saga_state         (request_id);
CREATE INDEX idx_reconciliation_request_id ON reconciliation_run (request_id);
