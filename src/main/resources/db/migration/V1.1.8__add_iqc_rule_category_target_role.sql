-- IQC 规则分类与适用说话人，兼容历史规则并默认作用于双方。
ALTER TABLE `iqc_quality_rule`
    ADD COLUMN `category` varchar(64) NOT NULL DEFAULT 'CUSTOM' AFTER `code`,
    ADD COLUMN `target_role` varchar(32) NOT NULL DEFAULT 'all' AFTER `rule_type`;

ALTER TABLE `iqc_quality_rule_version`
    ADD COLUMN `category` varchar(64) NOT NULL DEFAULT 'CUSTOM' AFTER `code`,
    ADD COLUMN `target_role` varchar(32) NOT NULL DEFAULT 'all' AFTER `rule_type`;
