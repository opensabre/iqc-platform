-- Reuse the existing sample store for complete reviewed conversations; retain all legacy rows.
ALTER TABLE iqc_quality_sample
    MODIFY COLUMN content_snapshot MEDIUMTEXT NOT NULL,
    MODIFY COLUMN expected_json MEDIUMTEXT NULL;
