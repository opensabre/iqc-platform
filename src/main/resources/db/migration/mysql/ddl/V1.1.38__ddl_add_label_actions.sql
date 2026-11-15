-- Metadata only: execution/deduction policies are deliberately not inferred here.
CREATE TABLE iqc_label_action_definition (
  id BIGINT NOT NULL, type varchar(32) NOT NULL, name varchar(100) NOT NULL,
  description varchar(500) DEFAULT NULL, status varchar(32) NOT NULL DEFAULT 'ENABLED',
  version_no int NOT NULL DEFAULT 1, owner_group_id varchar(64) DEFAULT NULL,
  created_by varchar(128) DEFAULT NULL, created_time datetime(3) DEFAULT NULL,
  updated_by varchar(128) DEFAULT NULL, updated_time datetime(3) DEFAULT NULL,
  PRIMARY KEY (id), KEY idx_iqc_action_scope (owner_group_id, status),
  CONSTRAINT ck_iqc_action_type CHECK (type IN ('RECORD_TAG','DEDUCT_SCORE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE iqc_label_action_binding (
  id BIGINT NOT NULL, label_id varchar(64) NOT NULL, action_id BIGINT NOT NULL,
  display_order int NOT NULL DEFAULT 0,
  created_by varchar(128) DEFAULT NULL, created_time datetime(3) DEFAULT NULL,
  updated_by varchar(128) DEFAULT NULL, updated_time datetime(3) DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_iqc_label_action (label_id, action_id),
  KEY idx_iqc_label_action_ref (action_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
