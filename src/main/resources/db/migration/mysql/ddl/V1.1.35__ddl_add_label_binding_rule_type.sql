-- Expand first; backfill and the final constraint are separately versioned.
ALTER TABLE iqc_label_rule_binding ADD COLUMN rule_type varchar(32) DEFAULT NULL;
