package io.github.opensabre.iqc.result;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class InspectionResultSchemaMigrationTest {
    private static final String MIGRATION =
            "db/migration/mysql/ddl/V1.1.23__ddl_expand_inspection_result_rule_id.sql";
    private static final String JOINT_LABEL_MIGRATION =
            "db/migration/mysql/ddl/V1.1.29__ddl_expand_joint_label_result_payloads.sql";

    @Test
    void aggregatedRuleIdsLongerThanLegacyColumnCanBePersisted() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:inspection_result_schema;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")) {
            connection.createStatement().execute(
                    "CREATE TABLE iqc_inspection_result (id varchar(64) PRIMARY KEY, rule_id varchar(64))");

            try (var input = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
                assertThat(input).as("migration %s", MIGRATION).isNotNull();
                connection.createStatement().execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }

            String aggregatedRuleIds = "1".repeat(59) + ",agent:" + "2".repeat(19);
            try (var statement = connection.prepareStatement(
                    "INSERT INTO iqc_inspection_result (id, rule_id) VALUES (?, ?)")) {
                statement.setString(1, "result-1");
                statement.setString(2, aggregatedRuleIds);
                statement.executeUpdate();
            }

            try (var result = connection.createStatement().executeQuery(
                    "SELECT rule_id FROM iqc_inspection_result WHERE id = 'result-1'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(aggregatedRuleIds);
            }
        }
    }

    @Test
    void jointLabelPayloadColumnsSupportJsonBeyondLegacyTextLimitInH2() throws Exception {
        // H2 cannot execute MySQL's multi-column MODIFY. Assert migration targets and test equivalent column types only.
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:joint_label_payload_schema;MODE=MySQL", "sa", "")) {
            connection.createStatement().execute("""
                    CREATE TABLE iqc_inspection_result (
                      id varchar(64) PRIMARY KEY, finding_json text, evidence_json text)
                    """);
            connection.createStatement().execute("""
                    CREATE TABLE iqc_inspection_label_result (
                      id varchar(64) PRIMARY KEY, value_json text)
                    """);

            try (var input = getClass().getClassLoader().getResourceAsStream(JOINT_LABEL_MIGRATION)) {
                assertThat(input).as("migration %s", JOINT_LABEL_MIGRATION).isNotNull();
                String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(migration).contains("MODIFY COLUMN `finding_json` mediumtext NULL")
                        .contains("MODIFY COLUMN `evidence_json` mediumtext NULL")
                        .contains("MODIFY COLUMN `value_json` mediumtext NULL");
            }
            connection.createStatement().execute("ALTER TABLE iqc_inspection_result MODIFY COLUMN finding_json mediumtext NULL");
            connection.createStatement().execute("ALTER TABLE iqc_inspection_result MODIFY COLUMN evidence_json mediumtext NULL");
            connection.createStatement().execute("ALTER TABLE iqc_inspection_label_result MODIFY COLUMN value_json mediumtext NULL");

            String payload = "{\"quotes\":\"" + "引文".repeat(40_000) + "\"}";
            assertThat(payload.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(65_535);
            try (var statement = connection.prepareStatement("""
                    INSERT INTO iqc_inspection_result (id, finding_json, evidence_json) VALUES (?, ?, ?)
                    """)) {
                statement.setString(1, "result-1");
                statement.setString(2, payload);
                statement.setString(3, payload);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO iqc_inspection_label_result (id, value_json) VALUES (?, ?)
                    """)) {
                statement.setString(1, "label-result-1");
                statement.setString(2, payload);
                statement.executeUpdate();
            }

            try (var result = connection.createStatement().executeQuery("""
                    SELECT finding_json, evidence_json FROM iqc_inspection_result WHERE id = 'result-1'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(payload);
                assertThat(result.getString(2)).isEqualTo(payload);
            }
            try (var result = connection.createStatement().executeQuery("""
                    SELECT value_json FROM iqc_inspection_label_result WHERE id = 'label-result-1'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(payload);
            }
        }
    }
}
