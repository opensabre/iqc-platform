-- Reusable subscription configuration only; archive facts and receipts remain Conversation and Task.
CREATE TABLE iqc_event_subscription (
 id varchar(64) NOT NULL,
 code varchar(64) NOT NULL,
 name varchar(100) NOT NULL,
 draft_config_json longtext NOT NULL,
 draft_revision int NOT NULL DEFAULT 1,
 active_revision_id varchar(64) DEFAULT NULL,
 status varchar(32) NOT NULL DEFAULT 'DISABLED',
 last_scanned_at_utc datetime(3) DEFAULT NULL,
 retry_not_before_utc datetime(3) DEFAULT NULL,
 last_error varchar(1000) DEFAULT NULL,
 owner_group_id varchar(64) DEFAULT NULL,
 created_by varchar(128) DEFAULT NULL,
 created_time datetime(3) DEFAULT NULL,
 updated_by varchar(128) DEFAULT NULL,
 updated_time datetime(3) DEFAULT NULL,
 PRIMARY KEY(id),
 UNIQUE KEY uk_iqc_subscription_code(code),
 KEY idx_iqc_subscription_owner(created_by,owner_group_id),
 KEY idx_iqc_subscription_discovery(status,last_scanned_at_utc,retry_not_before_utc)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE iqc_event_subscription_revision (
 id varchar(64) NOT NULL,
 subscription_id varchar(64) NOT NULL,
 version_no int NOT NULL,
 source_draft_revision int NOT NULL,
 snapshot_json longtext NOT NULL,
 content_hash varchar(64) NOT NULL,
 created_by varchar(128) DEFAULT NULL,
 created_time datetime(3) DEFAULT NULL,
 updated_by varchar(128) DEFAULT NULL,
 updated_time datetime(3) DEFAULT NULL,
 PRIMARY KEY(id),
 UNIQUE KEY uk_iqc_subscription_version(subscription_id,version_no),
 UNIQUE KEY uk_iqc_subscription_revision(subscription_id,source_draft_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE iqc_inspection_task
 ADD COLUMN event_subscription_id varchar(64) DEFAULT NULL,
 ADD COLUMN event_subscription_revision_id varchar(64) DEFAULT NULL,
 ADD KEY idx_iqc_subscription_instances(event_subscription_id,created_time,id);
-- No cursor is used: committed eligible archive records remain rediscoverable until a receipt exists.
CREATE INDEX idx_iqc_archive_completion ON iqc_conversation(status,created_time,id);
