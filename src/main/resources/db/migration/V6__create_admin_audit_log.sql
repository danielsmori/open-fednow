-- V6: Admin Audit Log
--
-- Records every access attempt to /admin/** endpoints — both granted and
-- denied — so privileged operations against the framework are traceable to a
-- specific operator identity with a timestamp. Required for compliance review
-- and incident response in any regulated financial deployment.
--
-- Result classification:
--   GRANTED  — request reached the controller (HTTP 2xx / 3xx)
--   DENIED   — authentication or authorization failed (HTTP 401 / 403)
--   REJECTED — other client error (HTTP 4xx other than 401 / 403)
--   ERROR    — server-side failure (HTTP 5xx)
--
-- Rows are append-only. The principal column carries the authenticated
-- username on GRANTED rows and the *attempted* username (or 'anonymous')
-- on DENIED rows, parsed from the Authorization: Basic header. Storing the
-- attempted principal on failures is what makes the table useful for
-- detecting credential probing.

CREATE TABLE admin_audit_log (
    id              BIGSERIAL                       PRIMARY KEY,

    requested_at    TIMESTAMP WITH TIME ZONE        NOT NULL DEFAULT NOW(),

    -- Authenticated or attempted principal; 'anonymous' when no credentials
    -- were supplied at all.
    principal       VARCHAR(100)                    NOT NULL,

    http_method     VARCHAR(10)                     NOT NULL,
    request_path    VARCHAR(500)                    NOT NULL,
    query_string    TEXT,

    -- One of GRANTED / DENIED / REJECTED / ERROR (see header comment).
    result          VARCHAR(20)                     NOT NULL,
    status_code     INT                             NOT NULL,

    -- Correlation ID populated by CorrelationFilter on every request; lets
    -- audit rows be joined to the structured logs for that same request.
    request_id      VARCHAR(50),

    CONSTRAINT chk_admin_audit_result CHECK (
        result IN ('GRANTED', 'DENIED', 'REJECTED', 'ERROR')
    )
);

-- Newest-first scans for the audit endpoint and dashboards
CREATE INDEX idx_admin_audit_requested_at ON admin_audit_log (requested_at);

-- "Show me everything user X did" — incident-response shape
CREATE INDEX idx_admin_audit_principal    ON admin_audit_log (principal, requested_at);
