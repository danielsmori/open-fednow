-- V5: Source rail on saga_state
--
-- Records which instant-payment rail (FedNow or RTP) delivered the inbound
-- pacs.008 that started each saga. Layers 2-4 are rail-agnostic, but Layer 1
-- must know which gateway to dispatch asynchronous responses through —
-- reconciliation-time pacs.002 notifications, saga-compensation pacs.004
-- returns, and out-of-band status updates. See ADR-0005.
--
-- Default FEDNOW for backfill: existing rows pre-dating dual-rail support
-- all correspond to FedNow inbound traffic, since RTP support did not exist.

ALTER TABLE saga_state
    ADD COLUMN source_rail VARCHAR(10) NOT NULL DEFAULT 'FEDNOW';

ALTER TABLE saga_state
    ADD CONSTRAINT chk_saga_source_rail CHECK (source_rail IN ('FEDNOW', 'RTP'));

CREATE INDEX idx_saga_source_rail ON saga_state (source_rail);
