package io.github.opensabre.iqc.quality;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;

class BusinessReviewSchemaTest {
    @Test
    void extendsExistingStoreWithoutChangingLegacyScoresOrUniqueness() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:business_review_schema;MODE=MySQL", "sa", "")) {
            var sql = connection.createStatement();
            sql.execute("CREATE TABLE iqc_result_review (id varchar(64) PRIMARY KEY, result_id varchar(64) NOT NULL UNIQUE, original_score int, final_score int)");
            sql.execute("INSERT INTO iqc_result_review VALUES ('old','message-result',83,90)");
            try (var input = getClass().getClassLoader().getResourceAsStream("db/migration/mysql/ddl/V1.1.28__ddl_extend_business_reviews.sql")) {
                assertThat(input).isNotNull();
                for (String statement : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";"))
                    if (!statement.isBlank()) sql.execute(statement);
            }
            try (var row = sql.executeQuery("SELECT target_type, original_score, final_score FROM iqc_result_review WHERE id='old'")) {
                assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("MESSAGE");
                assertThat(row.getInt(2)).isEqualTo(83); assertThat(row.getInt(3)).isEqualTo(90);
            }
            sql.execute("INSERT INTO iqc_result_review (id,target_type,business_result_id,review_revision,reviewed_result_json) VALUES ('b1','BUSINESS','conversation-result',1,'{\"finalScore\":66.67}')");
            sql.execute("INSERT INTO iqc_result_review (id,target_type,business_result_id,review_revision) VALUES ('b2','BUSINESS','conversation-result',2)");
            assertThatThrownBy(() -> sql.execute("INSERT INTO iqc_result_review (id,target_type,business_result_id,review_revision) VALUES ('duplicate','BUSINESS','conversation-result',2)"))
                    .isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> sql.execute("INSERT INTO iqc_result_review (id,result_id) VALUES ('duplicate-old','message-result')"))
                    .isInstanceOf(java.sql.SQLException.class);
            try (var row = sql.executeQuery("SELECT result_id, original_score, final_score, reviewed_result_json FROM iqc_result_review WHERE id='b1'")) {
                assertThat(row.next()).isTrue(); assertThat(row.getObject(1)).isNull(); assertThat(row.getObject(2)).isNull();
                assertThat(row.getObject(3)).isNull(); assertThat(row.getString(4)).contains("66.67");
            }
        }
    }
}
