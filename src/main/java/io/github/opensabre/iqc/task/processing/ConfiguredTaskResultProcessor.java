package io.github.opensabre.iqc.task.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;

/** Derives scores and label values from all configured sources, preserving disagreement and missing data. */
@Component
public final class ConfiguredTaskResultProcessor implements TaskResultProcessor {
    private final ObjectMapper mapper;
    public ConfiguredTaskResultProcessor(ObjectMapper mapper) { this.mapper = mapper; }

    /** Does not write or mutate detector results; output references the complete original result set. */
    @Override public ProcessedResult process(EngineExecutionResultSet resultSet, JsonNode configuration) {
        final TaskResultProcessingConfiguration policy;
        try { policy = mapper.treeToValue(configuration, TaskResultProcessingConfiguration.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("结果处理配置无效", exception);
        }
        var output = mapper.createObjectNode().put("schemaVersion", "iqc-task-processed-results-v1")
                .put("inputSnapshotId", resultSet.input().snapshotId());
        var consumed = new HashSet<String>();
        if (policy.inspection() != null) {
            var decisions = new ArrayList<InspectionScoring.Decision>();
            var checks = output.putArray("inspectionItems");
            for (var check : policy.inspection().checks()) {
                var candidates = mapper.createArrayNode();
                for (var mapping : check.engines()) {
                    var candidate = observation(resultSet, Operation.INSPECTION, mapping.engineInstanceId(), check.targetId(), consumed);
                    if ("DETERMINED".equals(candidate.path("status").asText())) candidate.put("mappedValue",
                            candidate.path("hit").asBoolean() ? mapping.onHit().name() : mapping.onMiss().name());
                    candidates.add(candidate);
                }
                var aggregate = aggregate(check.targetId(), candidates);
                checks.add(aggregate);
                String state = aggregate.path("status").asText();
                var status = "KNOWN".equals(state) ? InspectionScoring.ItemStatus.valueOf(aggregate.path("value").asText())
                        : "ERROR".equals(state) ? InspectionScoring.ItemStatus.ERROR
                        : InspectionScoring.ItemStatus.REVIEW_REQUIRED;
                decisions.add(new InspectionScoring.Decision(check.targetId(), status));
            }
            output.set("scoring", mapper.valueToTree(InspectionScoring.evaluate(policy.inspection().scoring(), decisions)));
        }
        var labels = output.putArray("labels");
        for (var label : policy.labeling()) {
            var candidates = mapper.createArrayNode();
            for (var mapping : label.engines()) {
                var candidate = observation(resultSet, Operation.LABELING, mapping.engineInstanceId(), label.targetId(), consumed);
                if ("DETERMINED".equals(candidate.path("status").asText())) candidate.set("mappedValue",
                        candidate.path("hit").asBoolean() ? mapping.onHit() : mapping.onMiss());
                candidates.add(candidate);
            }
            labels.add(aggregate(label.targetId(), candidates).put("valueCode", label.valueCode()).put("valueType", label.valueType()));
        }
        // Never silently leave a recorded engine outside result processing, including failures and skipped engines.
        for (var engine : resultSet.engines()) if (!consumed.contains(engine.engineResultId()))
            throw new IllegalArgumentException("结果处理配置遗漏引擎: " + engine.engineInstanceId());
        return new ProcessedResult("TASK_CONFIGURED", "1.0", resultSet.resultSetId(),
                resultSet.engines().stream().map(EngineResult::engineResultId).toList(), output);
    }

    private ObjectNode observation(EngineExecutionResultSet set, Operation operation, String instance,
                                   String target, java.util.Set<String> consumed) {
        var output = mapper.createObjectNode().put("engineInstanceId", instance).put("targetId", target);
        var matching = set.engines().stream().filter(engine -> engine.operation() == operation && instance.equals(engine.engineInstanceId())).toList();
        if (matching.isEmpty()) return output.put("status", "MISSING");
        var engine = matching.getFirst();
        if (!consumed.add(engine.engineResultId())) throw new IllegalArgumentException("结果处理重复引用引擎: " + instance);
        output.put("sourceEngineResultId", engine.engineResultId());
        if (engine.status() != ExecutionStatus.SUCCEEDED) {
            output.put("status", engine.status().name());
            if (engine.failure() != null) output.set("failure", mapper.valueToTree(engine.failure()));
            return output;
        }
        var observations = engine.observations().stream().filter(value -> target.equals(value.targetId())).toList();
        if (observations.size() != 1) return output.put("status", observations.isEmpty() ? "MISSING" : "INVALID");
        var value = observations.getFirst();
        output.set("evidenceIds", mapper.valueToTree(value.evidenceIds()));
        if (value.reason() != null) output.put("reason", value.reason());
        if (value.status() != ObservationStatus.DETERMINED) return output.put("status", value.status().name());
        if (!value.value().isBoolean()) return output.put("status", "INVALID");
        return output.put("status", "DETERMINED").put("hit", value.value().booleanValue());
    }

    /** REQUIRE_AGREEMENT requires every configured engine to provide the same determined mapped value. */
    private ObjectNode aggregate(String target, ArrayNode candidates) {
        var distinct = new HashSet<JsonNode>();
        boolean uncertain = false, error = false;
        for (var candidate : candidates) {
            if (!"DETERMINED".equals(candidate.path("status").asText()) || !candidate.hasNonNull("mappedValue")) uncertain = true;
            else distinct.add(candidate.get("mappedValue"));
            if (List.of("FAILED", "INVALID").contains(candidate.path("status").asText())) error = true;
        }
        String state = distinct.size() > 1 ? "CONFLICT" : error ? "ERROR" : uncertain || distinct.isEmpty() ? "UNKNOWN" : "KNOWN";
        var result = mapper.createObjectNode().put("targetId", target).put("status", state);
        result.set("candidates", candidates);
        if ("KNOWN".equals(state)) result.set("value", distinct.iterator().next());
        return result;
    }
}
