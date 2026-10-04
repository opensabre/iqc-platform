package io.github.opensabre.iqc.label;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.*;

/** Isolated structural check of the new review target; release still requires real MySQL migration CI. */
class LabelReviewMigrationTest {
    @Test
    void allowsMultipleLabelRoundsWithoutChangingLegacyMessageUniqueness() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:label-review-migration;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE iqc_result_review (id varchar(64) PRIMARY KEY, result_id varchar(64) NULL, "
                        + "business_result_id varchar(64) NULL, review_revision int NULL, target_type varchar(32) NOT NULL)");
                statement.execute("CREATE UNIQUE INDEX uk_iqc_review_result ON iqc_result_review (result_id)");
                statement.execute("CREATE UNIQUE INDEX uk_iqc_review_business_revision ON iqc_result_review (business_result_id, review_revision)");
                var resource = getClass().getResourceAsStream("/db/migration/mysql/ddl/V1.1.30__ddl_add_label_review_target.sql");
                assertThat(resource).isNotNull();
                String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                for (String command : sql.split(";")) if (!command.isBlank()) statement.execute(command.trim());
                statement.execute("INSERT INTO iqc_result_review (id,result_id,target_type) VALUES ('message','m1','MESSAGE')");
                statement.execute("INSERT INTO iqc_result_review (id,label_result_id,review_revision,target_type) VALUES ('r1','label',1,'LABEL')");
                statement.execute("INSERT INTO iqc_result_review (id,label_result_id,review_revision,target_type) VALUES ('r2','label',2,'LABEL')");
                assertThatThrownBy(() -> statement.execute("INSERT INTO iqc_result_review (id,label_result_id,review_revision,target_type) VALUES ('r3','label',2,'LABEL')"))
                        .hasMessageContaining("UK_IQC_REVIEW_LABEL_REVISION");
                assertThatThrownBy(() -> statement.execute("INSERT INTO iqc_result_review (id,result_id,target_type) VALUES ('message2','m1','MESSAGE')"))
                        .hasMessageContaining("UK_IQC_REVIEW_RESULT");
            }
        }
    }
}
