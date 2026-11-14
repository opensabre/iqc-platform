package io.github.opensabre.iqc.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Checks existing IQC references without duplicating their persistence or exposing scoped asset details. */
@Component
@RequiredArgsConstructor
public class RuleReferenceGuard {
    private static final int PAGE_SIZE = 200;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** Caller must hold the current rule lock; READ_COMMITTED prevents a pre-lock stale snapshot. */
    public void requireUnreferenced(String ruleId) {
        Long bindings = jdbc.queryForObject("SELECT COUNT(*) FROM iqc_label_rule_binding WHERE rule_id = ?", Long.class, ruleId);
        if (bindings != null && bindings > 0) referenced();
        for (var source : List.of(
                new Source("iqc_quality_rule_set", List.of("rule_ids_json")),
                new Source("iqc_quality_rule_set_version", List.of("rule_ids_json")),
                new Source("iqc_inspection_scheme", List.of("draft_config_json")),
                new Source("iqc_inspection_scheme_version", List.of("snapshot_json")),
                new Source("iqc_inspection_task", List.of("rule_ids_json", "rule_snapshot_json", "label_scope_snapshot_json")),
                new Source("iqc_task_execution", List.of("configuration_snapshot_json")))) {
            String cursor = "";
            while (true) {
                // Identifiers come exclusively from the fixed source list, values remain bound parameters.
                var page = jdbc.queryForList("SELECT id," + String.join(",", source.columns()) + " FROM " + source.table()
                        + " WHERE id > ? ORDER BY id LIMIT " + PAGE_SIZE, cursor);
                for (var row : page) for (var column : source.columns()) {
                    Object value = row.get(column);
                    if (value == null || value.toString().isBlank()) continue;
                    try {
                        JsonNode json = mapper.readTree(value.toString());
                        if (json == null || json.isNull()) throw IqcException.invalidState("规则引用配置无效，无法安全删除或停用");
                        if (contains(json, ruleId, column.equals("rule_ids_json") || json.isArray())) referenced();
                    } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                        throw IqcException.invalidState("规则引用配置无效，无法安全删除或停用");
                    }
                }
                if (page.size() < PAGE_SIZE) break;
                cursor = page.getLast().get("id").toString();
            }
        }
    }

    /** Only structural reference fields count; prompt text, names and substrings never establish a reference. */
    static boolean contains(JsonNode node, String ruleId, boolean ruleList) {
        if (node.isArray()) {
            for (var child : node) {
                if (ruleList && (child.isTextual() && ruleId.equals(child.asText())
                        || child.isObject() && ruleId.equals(child.path("id").asText()))) return true;
                if (contains(child, ruleId, false)) return true;
            }
        } else if (node.isObject()) {
            if (ruleId.equals(node.path("ruleId").asText())) return true;
            for (var key : List.of("rule", "appliesWhen", "prefilter", "candidate"))
                if (ruleId.equals(node.path(key).path("id").asText())) return true;
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (contains(field.getValue(), ruleId, List.of("rules", "ruleIds").contains(field.getKey()))) return true;
            }
        }
        return false;
    }

    private static void referenced() { throw IqcException.invalidState("规则仍被引用，不能删除或停用"); }
    private record Source(String table, List<String> columns) { }
}
