-- Governance supplies distributed rate checks and event counters, but no model-call capacity reservation.
CREATE TABLE iqc_model_gateway_capacity (
 model_key VARCHAR(64) NOT NULL,
 occupied BIGINT NOT NULL DEFAULT 0,
 concurrency_limit INT NOT NULL DEFAULT 0,
 PRIMARY KEY(model_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE iqc_model_gateway_call (
 call_id VARCHAR(64) NOT NULL,
 model_key VARCHAR(64) NOT NULL,
 logical_record_id VARCHAR(512) DEFAULT NULL,
 rule_id VARCHAR(128) DEFAULT NULL,
 owner VARCHAR(128) DEFAULT NULL,
 status VARCHAR(32) NOT NULL,
 reserved_at_utc DATETIME(3) NOT NULL,
 settled_at_utc DATETIME(3) DEFAULT NULL,
 input_tokens BIGINT DEFAULT NULL,
 output_tokens BIGINT DEFAULT NULL,
 total_tokens BIGINT DEFAULT NULL,
 response_model_id VARCHAR(256) DEFAULT NULL,
 usage_status VARCHAR(32) DEFAULT NULL,
 error_type VARCHAR(128) DEFAULT NULL,
 PRIMARY KEY(call_id),
 KEY idx_iqc_gateway_model(model_key,reserved_at_utc,call_id),
 KEY idx_iqc_gateway_status(status,reserved_at_utc),
 KEY idx_iqc_gateway_owner(owner,reserved_at_utc)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
