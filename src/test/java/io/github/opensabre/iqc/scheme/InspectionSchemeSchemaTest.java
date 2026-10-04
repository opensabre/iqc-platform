package io.github.opensabre.iqc.scheme;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.*;

/** Checks the new schema's uniqueness contracts; this is not a substitute for MySQL Flyway CI. */
class InspectionSchemeSchemaTest {
    @Test
    void releasesAreUniqueByVersionAndDraftRevision() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:scheme_schema;MODE=MySQL", "sa", "")) {
            try (var input = getClass().getClassLoader().getResourceAsStream(
                    "db/migration/mysql/ddl/V1.1.25__ddl_add_business_schemes.sql")) {
                assertThat(input).isNotNull();
                for (String sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                    if (!sql.isBlank()) connection.createStatement().execute(sql);
                }
            }
            var statement = connection.createStatement();
            statement.executeUpdate("INSERT INTO iqc_inspection_scheme_version "
                    + "(id, scheme_id, version_no, source_draft_revision, snapshot_json, content_hash) "
                    + "VALUES ('v1', 's1', 1, 3, '{}', 'hash')");
            try (var input = getClass().getClassLoader().getResourceAsStream(
                    "db/migration/mysql/ddl/V1.1.27__ddl_add_scheme_publication_trial.sql")) {
                assertThat(input).isNotNull();
                statement.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            try (var result = statement.executeQuery("SELECT source_trial_task_id FROM iqc_inspection_scheme_version WHERE id='v1'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isNull();
            }
            statement.executeUpdate("UPDATE iqc_inspection_scheme_version SET source_trial_task_id='trial-1' WHERE id='v1'");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO iqc_inspection_scheme_version "
                    + "(id, scheme_id, version_no, source_draft_revision, snapshot_json, content_hash) "
                    + "VALUES ('v2', 's1', 1, 4, '{}', 'hash')")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO iqc_inspection_scheme_version "
                    + "(id, scheme_id, version_no, source_draft_revision, snapshot_json, content_hash) "
                    + "VALUES ('v3', 's1', 2, 3, '{}', 'hash')")).isInstanceOf(SQLException.class);
            assertThat(statement.executeUpdate("INSERT INTO iqc_inspection_scheme_version "
                    + "(id, scheme_id, version_no, source_draft_revision, snapshot_json, content_hash) "
                    + "VALUES ('v4', 's1', 2, 4, '{}', 'hash')")).isEqualTo(1);
        }
    }
}
