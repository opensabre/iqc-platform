-- IQC 任务支持多个规则，保留 rule_set_id 作为旧接口兼容字段。
ALTER TABLE `iqc_inspection_task`
    ADD COLUMN `rule_ids_json` text AFTER `rule_set_id`;
