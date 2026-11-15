package io.github.opensabre.iqc.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class RuleReferenceGuardTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private RuleReferenceGuard guard;

    @BeforeEach void setup() {
        var data = new JdbcDataSource(); data.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(data); guard = new RuleReferenceGuard(jdbc, mapper);
        jdbc.execute("CREATE TABLE iqc_label_rule_binding(id varchar(64) PRIMARY KEY,rule_id varchar(64))");
        for (var table : new String[]{"iqc_quality_rule_set", "iqc_quality_rule_set_version"})
            jdbc.execute("CREATE TABLE " + table + "(id varchar(64) PRIMARY KEY,rule_ids_json clob)");
        jdbc.execute("CREATE TABLE iqc_inspection_scheme(id varchar(64) PRIMARY KEY,draft_config_json clob)");
        jdbc.execute("CREATE TABLE iqc_inspection_scheme_version(id varchar(64) PRIMARY KEY,snapshot_json clob)");
        jdbc.execute("CREATE TABLE iqc_inspection_task(id varchar(64) PRIMARY KEY,rule_ids_json clob,rule_snapshot_json clob,label_scope_snapshot_json clob)");
        jdbc.execute("CREATE TABLE iqc_task_execution(id varchar(64) PRIMARY KEY,configuration_snapshot_json clob)");
    }

    @ParameterizedTest
    @CsvSource({"iqc_quality_rule_set,rule_ids_json", "iqc_quality_rule_set_version,rule_ids_json",
            "iqc_inspection_scheme,draft_config_json", "iqc_inspection_scheme_version,snapshot_json",
            "iqc_inspection_task,rule_ids_json", "iqc_inspection_task,rule_snapshot_json",
            "iqc_inspection_task,label_scope_snapshot_json", "iqc_task_execution,configuration_snapshot_json"})
    void checksEveryPersistenceSource(String table, String column) {
        String json = column.equals("rule_ids_json") ? "[\"r1\"]" : "{\"nested\":{\"ruleId\":\"r1\"}}";
        jdbc.update("INSERT INTO " + table + " (id," + column + ") VALUES (?,?)", "private-asset", json);
        assertThatThrownBy(() -> guard.requireUnreferenced("r1")).hasMessage("规则仍被引用，不能删除或停用");
        assertThatCode(() -> guard.requireUnreferenced("r10")).doesNotThrowAnyException();
    }

    @Test void bindingsAreCheckedWithoutReadingAssetNames() {
        jdbc.update("INSERT INTO iqc_label_rule_binding VALUES ('private','r1')");
        assertThatThrownBy(() -> guard.requireUnreferenced("r1")).hasMessage("规则仍被引用，不能删除或停用");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"rule\":{\"id\":\"r1\"}}", "{\"appliesWhen\":{\"id\":\"r1\"}}",
            "{\"prefilter\":{\"id\":\"r1\"}}", "{\"candidate\":{\"id\":\"r1\"}}",
            "{\"rules\":[{\"id\":\"r1\"}]}", "{\"ruleIds\":[\"r1\"]}",
            "{\"enginePlan\":{\"engines\":[{\"configuration\":{\"tags\":[{\"ruleId\":\"r1\"}]}}]}}"})
    void understandsStructuralReferences(String json) throws Exception {
        assertThat(RuleReferenceGuard.contains(mapper.readTree(json), "r1", false)).isTrue();
        assertThat(RuleReferenceGuard.contains(mapper.readTree(json), "r", false)).isFalse();
    }

    @Test void namesPromptsAndUnrelatedIdsDoNotBecomeReferences() throws Exception {
        var node = mapper.readTree("{\"name\":\"r1\",\"prompt\":\"r1\",\"agent\":{\"id\":\"r1\"},\"selectedTagIds\":[\"r1\"]}");
        assertThat(RuleReferenceGuard.contains(node, "r1", false)).isFalse();
        assertThatCode(() -> guard.requireUnreferenced("unused")).doesNotThrowAnyException();
    }

    @Test void checksLaterPagesAndRejectsMalformedConfigurations() {
        for (int i = 0; i < 201; i++) jdbc.update("INSERT INTO iqc_quality_rule_set VALUES (?,?)", String.format("%04d", i), i == 200 ? "[\"r1\"]" : "[]");
        assertThatThrownBy(() -> guard.requireUnreferenced("r1")).hasMessageContaining("仍被引用");
        jdbc.update("UPDATE iqc_quality_rule_set SET rule_ids_json='broken' WHERE id='0200'");
        assertThatThrownBy(() -> guard.requireUnreferenced("r1")).hasMessageContaining("无法安全删除或停用");
    }
}
