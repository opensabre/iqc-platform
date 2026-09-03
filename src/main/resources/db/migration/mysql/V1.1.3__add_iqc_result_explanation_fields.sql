-- IQC 可解释结果：Finding、Evidence、Suggestion 以 JSON 保存，避免一期过早固化多表模型。
ALTER TABLE `iqc_inspection_result`
    ADD COLUMN `risk_level` varchar(32) DEFAULT NULL AFTER `score`,
    ADD COLUMN `deduction` int NOT NULL DEFAULT 0 AFTER `risk_level`,
    ADD COLUMN `finding_json` text AFTER `evidence`,
    ADD COLUMN `evidence_json` text AFTER `finding_json`,
    ADD COLUMN `suggestion_json` text AFTER `evidence_json`;
