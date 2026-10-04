package io.github.opensabre.iqc.scheme;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

class SchemeScoringSchemaTest {
    @Test
    void migrationKeepsHistoricalScoresAndSupportsNullableFractionalSchemeResults() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:scheme_scoring_schema;MODE=MySQL", "sa", "")) {
            var statement = connection.createStatement();
            for (String table : new String[]{"iqc_inspection_result", "iqc_inspection_rule_result", "iqc_inspection_conversation_result"})
                statement.execute("CREATE TABLE " + table + " (id varchar(64) PRIMARY KEY, score int NOT NULL DEFAULT 0)");
            statement.execute("INSERT INTO iqc_inspection_conversation_result (id, score) VALUES ('old', 83)");
            try (var input = getClass().getClassLoader().getResourceAsStream("db/migration/mysql/ddl/V1.1.26__ddl_add_scheme_scoring_results.sql")) {
                assertThat(input).isNotNull();
                for (String sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";"))
                    if (!sql.isBlank()) statement.execute(sql);
            }
            statement.execute("INSERT INTO iqc_inspection_conversation_result (id, final_score, score_status) VALUES ('new', 66.67, 'FINAL')");
            statement.execute("INSERT INTO iqc_inspection_result (id) VALUES ('observation')");
            try (var rows = statement.executeQuery("SELECT score, final_score FROM iqc_inspection_conversation_result WHERE id='new'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getObject(1)).isNull();
                assertThat(rows.getBigDecimal(2)).isEqualByComparingTo("66.67");
            }
            try (var rows = statement.executeQuery("SELECT score, final_score FROM iqc_inspection_conversation_result WHERE id='old'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(83); assertThat(rows.getObject(2)).isNull();
            }
            try (var rows = statement.executeQuery("SELECT score FROM iqc_inspection_result")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getObject(1)).isNull();
            }
        }
    }
}
