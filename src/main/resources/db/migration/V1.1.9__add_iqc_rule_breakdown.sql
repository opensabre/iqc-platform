ALTER TABLE `iqc_inspection_result`
    ADD COLUMN `rule_breakdown_json` text AFTER `suggestion_json`;
