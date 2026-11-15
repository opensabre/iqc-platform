package io.github.opensabre.iqc.task.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;

/** Explicit result-processing extension point until business action policies are confirmed. */
@Component
public final class UnifiedTaskResultProcessor implements TaskResultProcessor {
    private final ObjectMapper mapper;
    public UnifiedTaskResultProcessor(ObjectMapper mapper) { this.mapper = mapper; }
    /** Consume the complete context and preserve actions without inferring final tags or deductions. */
    @Override public ProcessedResult process(EngineExecutionResultSet set, JsonNode configuration) {
        var output = mapper.createObjectNode().put("schemaVersion", "iqc-unified-processing-v1")
                .put("processingStatus", "PENDING_POLICY").put("reason", "匹配已保存；正式标签与扣分策略待确认");
        output.putArray("labels");
        var tagSnapshot = set.taskConfiguration().path("unifiedLabels");
        output.set("tagSnapshot", tagSnapshot);
        var statuses = output.putArray("actionStatuses");
        for (var tag : tagSnapshot.path("labels")) for (var action : tag.path("actions")) {
            var entry = statuses.addObject().put("tagId", tag.path("id").asText()).put("status", "PENDING_POLICY")
                    .put("reason", "动作未执行；最终判定与动作触发策略待确认");
            entry.set("action", action);
        }
        return new TaskResultProcessor.ProcessedResult("UNIFIED_TAG_PENDING", "1.0", set.resultSetId(),
                set.engines().stream().map(EngineResult::engineResultId).toList(), output);
    }
}
