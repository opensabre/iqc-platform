-- Missing versions and same-type duplicates require explicit data repair before upgrade.
-- The CHECK also rejects legacy null rows even when a server uses permissive SQL mode.
ALTER TABLE iqc_label_rule_binding
  ADD CONSTRAINT ck_iqc_label_binding_rule_type CHECK (rule_type IS NOT NULL AND CHAR_LENGTH(TRIM(rule_type)) > 0),
  ADD UNIQUE KEY uk_iqc_label_binding_type (label_id, rule_type);
