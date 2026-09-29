-- SQL is authoritative for this synthetic outbound reference flow. These
-- tables do not make the legacy Redis Shadow Ledger atomic with saga_state.
CREATE TABLE reliability_account (
    account_id VARCHAR(34) PRIMARY KEY,
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    ledger_minor BIGINT NOT NULL,
    held_minor BIGINT NOT NULL DEFAULT 0,
    exclusive_control BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_reliability_balances CHECK (ledger_minor >= 0 AND held_minor >= 0 AND held_minor <= ledger_minor)
);

CREATE TABLE reliability_payment (
    operation_id VARCHAR(36) PRIMARY KEY,
    institution_id VARCHAR(35) NOT NULL,
    direction VARCHAR(8) NOT NULL DEFAULT 'OUTBOUND',
    rail VARCHAR(16) NOT NULL DEFAULT 'FEDNOW',
    business_key VARCHAR(35) NOT NULL,
    payload_fingerprint CHAR(64) NOT NULL,
    request_json TEXT NOT NULL,
    transaction_id VARCHAR(35) NOT NULL,
    message_id VARCHAR(35) NOT NULL,
    account_id VARCHAR(34) NOT NULL REFERENCES reliability_account(account_id),
    amount_minor BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    attempt_id VARCHAR(36),
    status_source VARCHAR(40),
    status_reference VARCHAR(100),
    service_status VARCHAR(16),
    posting_status VARCHAR(16),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    next_inquiry_at TIMESTAMP WITH TIME ZONE,
    inquiry_count INTEGER NOT NULL DEFAULT 0,
    investigation_reason VARCHAR(256),
    inquiry_lease_token VARCHAR(36),
    inquiry_lease_until TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_reliability_business UNIQUE (institution_id, direction, rail, business_key),
    CONSTRAINT uq_reliability_transaction UNIQUE (institution_id, direction, rail, transaction_id),
    CONSTRAINT uq_reliability_message UNIQUE (institution_id, direction, rail, message_id),
    CONSTRAINT chk_reliability_amount CHECK (amount_minor > 0),
    CONSTRAINT chk_reliability_state CHECK (state IN ('RESERVED', 'SUBMITTING', 'OUTCOME_UNKNOWN', 'SETTLED', 'REJECTED', 'INVESTIGATION'))
);

CREATE TABLE reliability_effect (
    operation_id VARCHAR(36) NOT NULL REFERENCES reliability_payment(operation_id),
    effect_type VARCHAR(16) NOT NULL,
    account_id VARCHAR(34) NOT NULL,
    amount_minor BIGINT NOT NULL,
    applied_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (operation_id, effect_type),
    CONSTRAINT chk_reliability_effect_type CHECK (effect_type IN ('HOLD', 'POST', 'RELEASE'))
);

CREATE TABLE reliability_status_event (
    event_id BIGSERIAL PRIMARY KEY,
    operation_id VARCHAR(36) NOT NULL REFERENCES reliability_payment(operation_id),
    source VARCHAR(40) NOT NULL,
    reference VARCHAR(100) NOT NULL,
    service_status VARCHAR(16) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    disposition VARCHAR(24) NOT NULL,
    CONSTRAINT uq_reliability_event UNIQUE (operation_id, source, reference)
);

CREATE TABLE reliability_investigation_audit (
    audit_id BIGSERIAL PRIMARY KEY,
    operation_id VARCHAR(36) NOT NULL REFERENCES reliability_payment(operation_id),
    actor VARCHAR(100) NOT NULL,
    reason VARCHAR(256) NOT NULL,
    prior_state VARCHAR(24) NOT NULL,
    resulting_state VARCHAR(24) NOT NULL,
    evidence_reference VARCHAR(256) NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_reliability_inquiry ON reliability_payment(state, next_inquiry_at);
