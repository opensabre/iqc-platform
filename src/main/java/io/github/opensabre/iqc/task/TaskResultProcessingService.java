package io.github.opensabre.iqc.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.LabelResolutionService;
import io.github.opensabre.iqc.task.processing.TaskResultProcessingConfiguration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.Operation;

/** Freezes result policy only after matching all engine targets and existing published label value definitions. */
@Service
public class TaskResultProcessingService {
    private final LabelResolutionService labels;
    private final ObjectMapper mapper;
    public TaskResultProcessingService(LabelResolutionService labels, ObjectMapper mapper) { this.labels = labels; this.mapper = mapper; }

    /** Reuses the published taxonomy resolver and its data-scope checks; never creates labels or rules. */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public FrozenProcessing freeze(TaskResultProcessingConfiguration configuration, TaskEnginePlanService.FrozenPlan plan) {
        if (configuration == null || plan == null) throw IqcException.invalidArgument("引擎计划与结果处理配置不能为空");
        var expected = new HashMap<String, String>();
        for (var engine : plan.engines()) expected.put(key(engine.operation(), engine.instanceId()), engine.configuration().path("targetId").asText());
        var mapped = new HashSet<String>();
        if (configuration.inspection() != null) for (var check : configuration.inspection().checks())
            for (var engine : check.engines()) consume(expected, mapped, Operation.INSPECTION, engine.engineInstanceId(), check.targetId());
        for (var label : configuration.labeling()) for (var engine : label.engines())
            consume(expected, mapped, Operation.LABELING, engine.engineInstanceId(), label.targetId());
        if (!mapped.equals(expected.keySet())) throw IqcException.invalidArgument("每个引擎必须且只能配置一次对应的结果处理");
        JsonNode snapshot = mapper.getNodeFactory().nullNode();
        if (!configuration.labeling().isEmpty()) {
            var resolved = labels.resolve(new LabelResolutionService.LabelSelection(null, null,
                    configuration.labeling().stream().map(TaskResultProcessingConfiguration.Label::targetId).toList(), null));
            for (var requested : configuration.labeling()) {
                var published = resolved.labels().stream().filter(label -> requested.targetId().equals(label.id())).findFirst()
                        .orElseThrow(() -> IqcException.invalidArgument("标签目标不属于已发布标签"));
                var definitions = published.values().stream().filter(value -> requested.valueCode().equals(value.getValueCode())).toList();
                if (definitions.size() != 1 || !requested.valueType().equals(definitions.getFirst().getValueType()))
                    throw IqcException.invalidArgument("标签值编码或类型与已发布定义不一致");
            }
            snapshot = mapper.valueToTree(resolved);
        }
        return new FrozenProcessing(configuration, snapshot);
    }
    private static void consume(Map<String, String> expected, Set<String> mapped, Operation operation, String instance, String target) {
        String key = key(operation, instance);
        if (!target.equals(expected.get(key)) || !mapped.add(key))
            throw IqcException.invalidArgument("结果处理引用了重复、未知或目标不一致的引擎: " + instance);
    }
    private static String key(Operation operation, String instance) { return operation + ":" + instance; }

    /** Stored with the existing task snapshot; label definitions remain immutable for later result projection. */
    public record FrozenProcessing(TaskResultProcessingConfiguration configuration, JsonNode labelSnapshot) {
        public FrozenProcessing { java.util.Objects.requireNonNull(configuration); labelSnapshot = java.util.Objects.requireNonNull(labelSnapshot).deepCopy(); }
        @Override public JsonNode labelSnapshot() { return labelSnapshot.deepCopy(); }
    }
}
