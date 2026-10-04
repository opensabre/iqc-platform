-- Joint trials retain versioned label candidates and verbatim quotes across a conversation.
-- The original TEXT columns can reject otherwise valid multi-message facts above 64 KiB.
ALTER TABLE `iqc_inspection_result`
  MODIFY COLUMN `finding_json` mediumtext NULL,
  MODIFY COLUMN `evidence_json` mediumtext NULL;

ALTER TABLE `iqc_inspection_label_result`
  MODIFY COLUMN `value_json` mediumtext NULL;
