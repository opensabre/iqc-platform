-- Discovery progress belongs to the existing result, including zero matched actions.
ALTER TABLE iqc_inspection_conversation_result
 ADD COLUMN action_discovered_at_utc DATETIME(3) NULL,
 ADD COLUMN action_discovery_after_utc DATETIME(3) NULL,
 ADD INDEX idx_iqc_result_action_discovery (action_discovered_at_utc,action_discovery_after_utc,id);
