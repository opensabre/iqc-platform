ALTER TABLE `iqc_inspection_scheme_version`
  ADD COLUMN `archived` boolean NOT NULL DEFAULT FALSE AFTER `content_hash`;
