-- V14: Record automatic recovery reconciliations truthfully (#68, PR 3)
--
-- Reconciliation can now start on its own when the core comes back online.
-- Those runs were indistinguishable from cron runs ('SCHEDULED') in this
-- compliance-oriented audit table; 'AUTO_RECOVERY' names the actual trigger.

ALTER TABLE reconciliation_run DROP CONSTRAINT chk_recon_triggered_by;

ALTER TABLE reconciliation_run ADD CONSTRAINT chk_recon_triggered_by CHECK (
    triggered_by IN ('SCHEDULED', 'MANUAL', 'AUTO_RECOVERY')
);
