-- Resolve the exact bound version, never substitute the current rule definition.
UPDATE iqc_label_rule_binding b
JOIN iqc_quality_rule_version v ON v.rule_id=b.rule_id AND v.version_no=b.rule_version_no
SET b.rule_type=UPPER(v.rule_type);
