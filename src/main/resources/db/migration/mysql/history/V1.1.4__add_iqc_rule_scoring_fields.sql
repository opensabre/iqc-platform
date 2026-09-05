-- IQC 规则评分配置：命中扣分、风险等级和一票否决。
ALTER TABLE `iqc_quality_rule`
    ADD COLUMN `deduction` int NOT NULL DEFAULT 10 AFTER `description`,
    ADD COLUMN `risk_level` varchar(32) NOT NULL DEFAULT 'MEDIUM' AFTER `deduction`,
    ADD COLUMN `veto` tinyint(1) NOT NULL DEFAULT 0 AFTER `risk_level`;
