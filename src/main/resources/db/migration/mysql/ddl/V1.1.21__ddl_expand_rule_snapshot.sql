-- DLS rule sets embed versioned slot and rule definitions in the immutable task snapshot.
ALTER TABLE `iqc_inspection_task`
    MODIFY COLUMN `rule_snapshot_json` MEDIUMTEXT NULL;
