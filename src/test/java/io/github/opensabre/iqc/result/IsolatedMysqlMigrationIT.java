package io.github.opensabre.iqc.result;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly invoked acceptance against a dedicated temporary local server; never uses application credentials. */
class IsolatedMysqlMigrationIT {
    private static final String SERVER = "jdbc:mysql://127.0.0.1:34307/";
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";

    @Test
    void freshInstallIsRepeatableAndJointPayloadsRoundTripAndRollback() throws Exception {
        verifyIsolatedServer();
        // Verify the exact isolated server before creating any schema or executing migration DDL.
        try (var connection = DriverManager.getConnection(SERVER + OPTIONS, "root", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE iqc_validation CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        var flyway = Flyway.configure().dataSource(SERVER + "iqc_validation" + OPTIONS, "root", "")
                .locations("classpath:db/migration/mysql").baselineOnMigrate(false).cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isPositive();
        flyway.validate();
        assertCurrentSchema(SERVER + "iqc_validation" + OPTIONS);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(SERVER + "iqc_validation" + OPTIONS, "root", "")) {
            try (var statement = connection.createStatement(); var columns = statement.executeQuery("""
                    SELECT table_name, column_name, data_type FROM information_schema.columns
                    WHERE table_schema='iqc_validation' AND
                    ((table_name='iqc_inspection_result' AND column_name IN ('finding_json','evidence_json'))
                    OR (table_name='iqc_inspection_label_result' AND column_name='value_json'))
                    """)) {
                int count = 0;
                while (columns.next()) { assertThat(columns.getString(3)).isEqualTo("mediumtext"); count++; }
                assertThat(count).isEqualTo(3);
            }
            try (var statement = connection.createStatement(); var column = statement.executeQuery("""
                    SELECT data_type, is_nullable, column_default FROM information_schema.columns
                    WHERE table_schema='iqc_validation' AND table_name='iqc_inspection_scheme_version' AND column_name='archived'
                    """)) {
                assertThat(column.next()).isTrue();
                assertThat(column.getString(1)).isEqualTo("tinyint");
                assertThat(column.getString(2)).isEqualTo("NO");
                assertThat(column.getString(3)).isEqualTo("0");
                assertThat(column.next()).isFalse();
            }
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO iqc_inspection_scheme_version
                            (id,scheme_id,version_no,source_draft_revision,snapshot_json,content_hash)
                        VALUES ('archive-default','archive-scheme',1,1,'{}',REPEAT('a',64))
                        """);
                try (var value = statement.executeQuery("SELECT archived FROM iqc_inspection_scheme_version WHERE id='archive-default'")) {
                    assertThat(value.next()).isTrue();
                    assertThat(value.getBoolean(1)).isFalse();
                }
                statement.executeUpdate("UPDATE iqc_inspection_scheme_version SET archived=TRUE WHERE id='archive-default'");
                try (var value = statement.executeQuery("SELECT archived,snapshot_json FROM iqc_inspection_scheme_version WHERE id='archive-default'")) {
                    assertThat(value.next()).isTrue();
                    assertThat(value.getBoolean(1)).isTrue();
                    assertThat(value.getString(2)).isEqualTo("{}");
                }
            }
            String payload = "{\"quote\":\"" + "客户明确陈述".repeat(12000) + "\"}";
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO iqc_inspection_result
                    (id,task_id,conversation_id,message_id,speaker_role,result_status,reason,finding_json,evidence_json)
                    VALUES ('mysql-observation','test-task','test-conversation','test-message','user','NOT_HIT','test',?,?)
                    """)) {
                insert.setString(1, payload); insert.setString(2, payload); insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO iqc_inspection_label_result
                    (id,conversation_result_id,label_id,label_version_no,value_code,value_json,source_rule_result_id)
                    VALUES ('mysql-label','test-result','test-label',1,'value',?,'test-rule-result')
                    """)) {
                insert.setString(1, payload); insert.executeUpdate();
            }
            connection.commit();
            try (var statement = connection.createStatement(); var values = statement.executeQuery("""
                    SELECT finding_json,evidence_json,value_json FROM iqc_inspection_result
                    CROSS JOIN iqc_inspection_label_result
                    WHERE iqc_inspection_result.id='mysql-observation' AND iqc_inspection_label_result.id='mysql-label'
                    """)) {
                assertThat(values.next()).isTrue();
                for (int column = 1; column <= 3; column++) assertThat(values.getString(column)).isEqualTo(payload);
            }
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE iqc_inspection_result SET finding_json='rollback' WHERE id='mysql-observation'");
                statement.executeUpdate("UPDATE iqc_inspection_label_result SET value_json='rollback' WHERE id='mysql-label'");
            }
            connection.rollback();
            try (var statement = connection.createStatement(); var values = statement.executeQuery("""
                    SELECT finding_json,value_json FROM iqc_inspection_result CROSS JOIN iqc_inspection_label_result
                    WHERE iqc_inspection_result.id='mysql-observation' AND iqc_inspection_label_result.id='mysql-label'
                    """)) {
                assertThat(values.next()).isTrue();
                assertThat(values.getString(1)).isEqualTo(payload); assertThat(values.getString(2)).isEqualTo(payload);
            }
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {"1.1.31", "1.1.44"})
    void historicalVersionUpgradesWithoutReapplyingBaseline(String version) throws Exception {
        verifyIsolatedServer();
        String schema = "iqc_upgrade_" + version.replace('.', '_');
        try (var connection = DriverManager.getConnection(SERVER + OPTIONS, "root", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        String upgradeUrl = SERVER + schema + OPTIONS;
        var historical = Flyway.configure().dataSource(upgradeUrl, "root", "")
                .locations("classpath:db/migration/mysql").baselineOnMigrate(false).cleanDisabled(true)
                .target(version).load();
        assertThat(historical.migrate().migrationsExecuted).isPositive();
        historical.validate();
        // Keep fixture data in an existing table: upgrading must preserve released product state.
        try (var connection = DriverManager.getConnection(upgradeUrl, "root", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO iqc_inspection_scheme_version
                    (id,scheme_id,version_no,source_draft_revision,snapshot_json,content_hash)
                    VALUES ('preserved-version','preserved-scheme',1,1,'{}',REPEAT('b',64))
                    """);
        }
        var current = Flyway.configure().dataSource(upgradeUrl, "root", "")
                .locations("classpath:db/migration/mysql").baselineOnMigrate(false).cleanDisabled(true).load();
        int expected = current.info().pending().length;
        assertThat(expected).isPositive();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(expected);
        current.validate();
        assertThat(current.migrate().migrationsExecuted).isZero();
        assertCurrentSchema(upgradeUrl);
        try (var connection = DriverManager.getConnection(upgradeUrl, "root", "");
             var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT archived,snapshot_json FROM iqc_inspection_scheme_version WHERE id='preserved-version'")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getBoolean(1)).isFalse();
            assertThat(row.getString(2)).isEqualTo("{}");
        }
        try (var connection = DriverManager.getConnection(upgradeUrl, "root", "");
             var statement = connection.createStatement();
             var baseline = statement.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE type='SQL_BASELINE' AND success=1")) {
            assertThat(baseline.next()).isTrue();
            assertThat(baseline.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void actionJournalUpgradeDoesNotFabricateEarlierAttempts() throws Exception {
        verifyIsolatedServer();
        String schema = "iqc_journal_upgrade_" + Long.toHexString(System.nanoTime());
        String url = SERVER + schema + OPTIONS;
        try (var server = DriverManager.getConnection(SERVER + OPTIONS,"root","");
             var ddl = server.createStatement()) {
            ddl.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
            try {
                var historical = Flyway.configure().dataSource(url,"root","")
                        .locations("classpath:db/migration/mysql").target("1.1.63").load();
                historical.migrate(); historical.validate();
                try (var db = DriverManager.getConnection(url,"root",""); var sql = db.createStatement()) {
                    sql.executeUpdate("""
                        INSERT INTO iqc_action_execution(id,idempotency_key,task_id,execution_id,conversation_id,
                        source_result_id,decision_reference,action_policy_hash,action_id,action_type,owner,
                        input_snapshot_json,input_snapshot_hash,policy_snapshot_json,status,version,attempt_count,
                        claim_token,outcome,evidence_reference)
                        VALUES ('historical',REPEAT('1',64),'task','eid','conversation','result','decision',
                        REPEAT('2',64),'alert','SEND_ALERT','alice','{}',REPEAT('3',64),'{}','WAITING_RETRY',2,1,
                        REPEAT('a',32),'CONFIRMED_RETRYABLE_FAILURE','old-proof')
                        """);
                }
                var current = Flyway.configure().dataSource(url,"root","")
                        .locations("classpath:db/migration/mysql").load();
                int pending = current.info().pending().length;
                assertThat(current.migrate().migrationsExecuted).isEqualTo(pending);
                current.validate(); assertThat(current.migrate().migrationsExecuted).isZero();
                try (var db = DriverManager.getConnection(url,"root",""); var sql = db.createStatement()) {
                    try (var count = sql.executeQuery("SELECT COUNT(*) FROM iqc_action_execution_event")) {
                        assertThat(count.next()).isTrue(); assertThat(count.getInt(1)).isZero();
                    }
                    sql.executeUpdate("""
                        UPDATE iqc_action_execution SET status='RUNNING',version=3,attempt_count=2,
                        claim_token=REPEAT('b',32),outcome=NULL,evidence_reference=NULL,updated_by='system'
                        WHERE id='historical' AND version=2
                        """);
                    try (var event = sql.executeQuery("""
                        SELECT version,previous_version,JSON_UNQUOTE(JSON_EXTRACT(previous_state_json,'$.evidence_reference')),
                        JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.claim_token'))
                        FROM iqc_action_execution_event WHERE action_execution_id='historical'
                        """)) {
                        assertThat(event.next()).isTrue(); assertThat(event.getLong(1)).isEqualTo(3);
                        assertThat(event.getLong(2)).isEqualTo(2); assertThat(event.getString(3)).isEqualTo("old-proof");
                        assertThat(event.getString(4)).isEqualTo("b".repeat(32)); assertThat(event.next()).isFalse();
                    }
                }
            } finally { ddl.execute("DROP DATABASE " + schema); }
        }
    }

    @Test
    void findingLabelUpgradePreservesRuleRowsAndEnforcesDistinctProvenance() throws Exception {
        verifyIsolatedServer();
        String schema="iqc_finding_upgrade_"+Long.toHexString(System.nanoTime());
        String url=SERVER+schema+OPTIONS;
        try (var server=DriverManager.getConnection(SERVER+OPTIONS,"root",""); var ddl=server.createStatement()) {
            ddl.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");
            try {
                var old=Flyway.configure().dataSource(url,"root","")
                        .locations("classpath:db/migration/mysql").target("1.1.67").load();
                old.migrate(); old.validate();
                try (var db=DriverManager.getConnection(url,"root",""); var sql=db.createStatement()) {
                    sql.executeUpdate("INSERT INTO iqc_inspection_label_result(id,conversation_result_id,label_id,label_version_no,value_code,value_json,source_rule_result_id) VALUES ('legacy','result','label',1,'old','{}','rule')");
                }
                var current=Flyway.configure().dataSource(url,"root","").locations("classpath:db/migration/mysql").load();
                assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
                current.validate(); assertThat(current.migrate().migrationsExecuted).isZero();
                try (var db=DriverManager.getConnection(url,"root",""); var sql=db.createStatement()) {
                    try (var row=sql.executeQuery("SELECT source_rule_result_id,source_action_execution_id,value_json FROM iqc_inspection_label_result WHERE id='legacy'")) {
                        assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("rule");
                        assertThat(row.getString(2)).isNull(); assertThat(row.getString(3)).isEqualTo("{}");
                    }
                    sql.executeUpdate("INSERT INTO iqc_inspection_label_result(id,conversation_result_id,label_id,label_version_no,value_code,value_json,generation_source,source_finding_code,source_action_execution_id,source_input_hash,source_projection_hash) VALUES ('finding','result','label',1,'new','{}','PIPELINE_FINDING','finding-code','action',REPEAT('a',64),REPEAT('b',64))");
                    org.assertj.core.api.Assertions.assertThatThrownBy(()->sql.executeUpdate("UPDATE iqc_inspection_label_result SET source_rule_result_id='fake-rule' WHERE id='finding'"))
                            .isInstanceOf(java.sql.SQLException.class);
                    org.assertj.core.api.Assertions.assertThatThrownBy(()->sql.executeUpdate("UPDATE iqc_inspection_label_result SET source_input_hash=NULL WHERE id='finding'"))
                            .isInstanceOf(java.sql.SQLException.class);
                    org.assertj.core.api.Assertions.assertThatThrownBy(()->sql.executeUpdate("UPDATE iqc_inspection_label_result SET source_rule_result_id=NULL WHERE id='legacy'"))
                            .isInstanceOf(java.sql.SQLException.class);
                }
            } finally { ddl.execute("DROP DATABASE "+schema); }
        }
    }

    /** Fail closed on the exact disposable server identity before any schema writes. */
    static void verifyIsolatedServer() throws Exception {
        String directory = System.getenv("IQC_MYSQL_VALIDATION_DIR");
        assertThat(directory).startsWith("/private/tmp/iqc-mysql-validation.");
        try (var connection = DriverManager.getConnection(SERVER + OPTIONS, "root", "");
             var statement = connection.createStatement();
             var identity = statement.executeQuery("SELECT @@port, @@datadir")) {
            assertThat(identity.next()).isTrue();
            assertThat(identity.getInt(1)).isEqualTo(34307);
            assertThat(identity.getString(2)).isEqualTo(directory + "/");
        }
    }

    /** Check runtime-critical types/defaults rather than accepting a successful Flyway status alone. */
    private static void assertCurrentSchema(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url, "root", "")) {
            for (String[] expected : new String[][] {
                    {"iqc_action_reconciliation_attempt", "observed_version", "bigint", "NO", null},
                    {"iqc_action_reconciliation_attempt", "actor_user_id", "varchar", "NO", null},
                    {"iqc_action_reconciliation_attempt", "reason", "varchar", "NO", null},
                    {"iqc_action_reconciliation_attempt", "confirmed_version", "bigint", "YES", null},
                    {"iqc_action_execution_event", "recovery_reason", "varchar", "YES", null},
                    {"iqc_action_execution_event", "reconciliation_reason", "varchar", "YES", null},
                    {"iqc_action_execution_event", "state_json", "json", "NO", null},
                    {"iqc_action_execution_event", "recorded_at_utc", "datetime", "NO", null},
                    {"iqc_inspection_conversation_result", "action_discovered_at_utc", "datetime", "YES", null},
                    {"iqc_inspection_conversation_result", "action_discovery_after_utc", "datetime", "YES", null},
                    {"iqc_inspection_task", "trigger_request_json", "longtext", "YES", null},
                    {"iqc_inspection_task", "model_budget_wait_key", "char", "YES", null},
                    {"iqc_inspection_task", "periodic_schedule_id", "varchar", "YES", null},
                    {"iqc_model_gateway_call", "budget_reconciled_cost", "decimal", "YES", null},
                    {"iqc_model_gateway_call", "budget_reconciled_at_utc", "datetime", "YES", null},
                    {"iqc_model_gateway_capacity", "token_budget_committed", "bigint", "NO", "0"},
                    {"iqc_model_gateway_capacity", "cost_budget_committed", "decimal", "NO", "0.000000000000000000"},
                    {"iqc_model_gateway_capacity", "budget_breached", "tinyint", "NO", "0"}}) {
                try (var query = connection.prepareStatement("""
                        SELECT data_type,is_nullable,column_default FROM information_schema.columns
                        WHERE table_schema=DATABASE() AND table_name=? AND column_name=?
                        """)) {
                    query.setString(1, expected[0]); query.setString(2, expected[1]);
                    try (var row = query.executeQuery()) {
                        assertThat(row.next()).as(expected[0] + "." + expected[1]).isTrue();
                        assertThat(row.getString(1)).isEqualTo(expected[2]);
                        assertThat(row.getString(2)).isEqualTo(expected[3]);
                        assertThat(row.getString(3)).isEqualTo(expected[4]);
                        assertThat(row.next()).isFalse();
                    }
                }
            }
            try (var query = connection.createStatement();
                 var identity = query.executeQuery("""
                         SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index),MAX(non_unique)
                         FROM information_schema.statistics
                         WHERE table_schema=DATABASE() AND table_name='iqc_inspection_task'
                         AND index_name='uk_iqc_trigger_identity'
                         """)) {
                assertThat(identity.next()).isTrue();
                assertThat(identity.getString(1)).isEqualTo("created_by,trigger_source,trigger_event_id");
                assertThat(identity.getInt(2)).isZero();
            }
            try (var query = connection.createStatement();
                 var history = query.executeQuery("SELECT version,success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")) {
                assertThat(history.next()).isTrue();
                assertThat(history.getString(1)).isEqualTo("1.1.68");
                assertThat(history.getBoolean(2)).isTrue();
            }
            try (var query = connection.createStatement();
                 var actionKey = query.executeQuery("""
                         SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index),MAX(non_unique)
                         FROM information_schema.statistics
                         WHERE table_schema=DATABASE() AND table_name='iqc_action_execution'
                         AND index_name='uk_iqc_action_idempotency'
                         """)) {
                assertThat(actionKey.next()).isTrue();
                assertThat(actionKey.getString(1)).isEqualTo("idempotency_key");
                assertThat(actionKey.getInt(2)).isZero();
            }
        }
    }
}
