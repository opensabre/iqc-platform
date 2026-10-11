-- No backfill: earlier successful confirmations did not durably retain their reason.
ALTER TABLE iqc_action_execution_event
 ADD COLUMN reconciliation_reason VARCHAR(1000) NULL;
