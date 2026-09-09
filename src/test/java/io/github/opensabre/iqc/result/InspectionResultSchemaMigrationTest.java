package io.github.opensabre.iqc.result;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class InspectionResultSchemaMigrationTest {
    private static final String MIGRATION =
            "db/migration/mysql/ddl/V1.1.23__ddl_expand_inspection_result_rule_id.sql";

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
}
