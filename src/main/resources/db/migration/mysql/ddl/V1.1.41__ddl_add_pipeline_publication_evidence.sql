-- Reuse immutable release versions for measured admission decisions and administrator override evidence.
ALTER TABLE iqc_pipeline_asset_version
  ADD COLUMN publication_decision_json longtext NULL,
  ADD COLUMN publication_decision_hash varchar(64) NULL;
