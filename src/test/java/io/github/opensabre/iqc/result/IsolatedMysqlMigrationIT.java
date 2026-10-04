package io.github.opensabre.iqc.result;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly invoked acceptance against a dedicated temporary local server; never uses application credentials. */
class IsolatedMysqlMigrationIT {
    private static final String SERVER = "jdbc:mysql://127.0.0.1:34307/";
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";

    @Test
    void freshInstallIsRepeatableAndJointPayloadsRoundTripAndRollback() throws Exception {
        String directory = System.getenv("IQC_MYSQL_VALIDATION_DIR");
        assertThat(directory).startsWith("/private/tmp/iqc-mysql-validation.");
        try (var connection = DriverManager.getConnection(SERVER + OPTIONS, "root", "");
             var query = connection.createStatement();
             var server = query.executeQuery("SELECT @@port, @@datadir")) {
            assertThat(server.next()).isTrue();
            assertThat(server.getInt(1)).isEqualTo(34307);
            assertThat(server.getString(2)).isEqualTo(directory + "/");
        }
        // Verify the exact isolated server before creating any schema or executing migration DDL.
        try (var connection = DriverManager.getConnection(SERVER + OPTIONS, "root", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE iqc_validation CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        var flyway = Flyway.configure().dataSource(SERVER + "iqc_validation" + OPTIONS, "root", "")
                .locations("classpath:db/migration/mysql").baselineOnMigrate(false).cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isPositive();
        flyway.validate();
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

        try (var connection = DriverManager.getConnection(SERVER + OPTIONS, "root", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE iqc_upgrade_validation CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        String upgradeUrl = SERVER + "iqc_upgrade_validation" + OPTIONS;
        var beforeArchive = Flyway.configure().dataSource(upgradeUrl, "root", "")
                .locations("classpath:db/migration/mysql").baselineOnMigrate(false).cleanDisabled(true)
                .target("1.1.31").load();
        assertThat(beforeArchive.migrate().migrationsExecuted).isPositive();
        try (var connection = DriverManager.getConnection(upgradeUrl, "root", "");
             var statement = connection.createStatement();
             var column = statement.executeQuery("""
                     SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema='iqc_upgrade_validation' AND table_name='iqc_inspection_scheme_version' AND column_name='archived'
                     """)) {
            assertThat(column.next()).isTrue();
            assertThat(column.getInt(1)).isZero();
        }
        var finishArchiveUpgrade = Flyway.configure().dataSource(upgradeUrl, "root", "")
                .locations("classpath:db/migration/mysql").baselineOnMigrate(false).cleanDisabled(true).load();
        assertThat(finishArchiveUpgrade.migrate().migrationsExecuted).isEqualTo(1);
        finishArchiveUpgrade.validate();
        assertThat(finishArchiveUpgrade.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(upgradeUrl, "root", "");
             var statement = connection.createStatement();
             var column = statement.executeQuery("""
                     SELECT data_type, is_nullable, column_default FROM information_schema.columns
                     WHERE table_schema='iqc_upgrade_validation' AND table_name='iqc_inspection_scheme_version' AND column_name='archived'
                     """)) {
            assertThat(column.next()).isTrue();
            assertThat(column.getString(1)).isEqualTo("tinyint");
            assertThat(column.getString(2)).isEqualTo("NO");
            assertThat(column.getString(3)).isEqualTo("0");
        }
    }
}
