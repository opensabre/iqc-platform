-- Reuse conversation archives; unknown upstream values remain NULL.
ALTER TABLE iqc_conversation
  ADD COLUMN employee_department_id varchar(64) NULL,
  ADD COLUMN asr_status varchar(32) NULL,
  ADD COLUMN customer_level varchar(64) NULL,
  ADD COLUMN complaint tinyint(1) NULL,
  ADD COLUMN business_fields_json longtext NULL;
