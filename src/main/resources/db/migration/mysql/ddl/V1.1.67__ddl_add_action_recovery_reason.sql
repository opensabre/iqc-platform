-- Successful blocked recovery reasons belong to their original state transition. No backfill.
ALTER TABLE iqc_action_execution_event ADD COLUMN recovery_reason VARCHAR(1000) NULL;
