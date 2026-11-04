package io.github.opensabre.iqc.task.processing;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** IQC business processors consume the entire evidence set without changing engine records. */
public interface TaskResultProcessor {
    /** Processes a completed conversation result set using the task's explicit scoring/label rules. */
    ProcessedResult process(EngineExecutionResultSet resultSet, JsonNode processingConfiguration);

    /** Derived output has its own identity and references every engine result it used. */
    record ProcessedResult(String processorCode, String processorVersion, String sourceResultSetId,
                           List<String> sourceEngineResultIds, JsonNode output) {
        public ProcessedResult {
            if (processorCode == null || processorCode.isBlank() || processorVersion == null || processorVersion.isBlank()
                    || sourceResultSetId == null || sourceResultSetId.isBlank())
                throw new IllegalArgumentException("处理器、版本与来源结果集不能为空");
            sourceEngineResultIds = List.copyOf(sourceEngineResultIds);
            output = java.util.Objects.requireNonNull(output).deepCopy();
        }
        @Override public JsonNode output() { return output.deepCopy(); }
    }
}
