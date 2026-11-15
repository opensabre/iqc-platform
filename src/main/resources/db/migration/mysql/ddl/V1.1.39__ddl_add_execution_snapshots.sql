-- Extend the existing execution history; historical attempts remain readable with null snapshots.
ALTER TABLE iqc_task_execution
    ADD COLUMN configuration_snapshot_json LONGTEXT NULL COMMENT 'Frozen wizard configuration for this attempt',
    ADD COLUMN selection_snapshot_json LONGTEXT NULL COMMENT 'Resolved data selection including runtime seed';

ALTER TABLE iqc_inspection_task
    ADD COLUMN unified_configuration_json LONGTEXT NULL COMMENT 'Unified references resolved at execution creation';
