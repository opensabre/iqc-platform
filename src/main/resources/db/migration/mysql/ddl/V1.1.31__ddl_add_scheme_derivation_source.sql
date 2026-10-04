ALTER TABLE `iqc_inspection_scheme`
  ADD COLUMN `source_scheme_id` varchar(64) DEFAULT NULL AFTER `owner_group_id`,
  ADD COLUMN `source_scheme_name` varchar(100) DEFAULT NULL AFTER `source_scheme_id`,
  ADD COLUMN `source_scheme_code` varchar(64) DEFAULT NULL AFTER `source_scheme_name`,
  ADD COLUMN `source_version_no` int DEFAULT NULL AFTER `source_scheme_code`,
  ADD COLUMN `source_content_hash` varchar(64) DEFAULT NULL AFTER `source_version_no`;
