ALTER TABLE iqc_result_review ADD COLUMN label_result_id varchar(64) NULL;
CREATE UNIQUE INDEX uk_iqc_review_label_revision ON iqc_result_review (label_result_id, review_revision);
