package io.github.opensabre.iqc.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** Frozen sequential/conditional pipeline; JSON pointers address input, variables and upstream. */
public record PipelineDefinition(String schemaVersion, List<Node> nodes) {
    public static final String SCHEMA = "iqc-pipeline-v1";
    public enum OnError { FAIL_PIPELINE, CONTINUE, MARK_REVIEW, FALLBACK }
    public enum Operator { EXISTS, EQUALS, NOT_EQUALS, GREATER_OR_EQUAL, LESS_OR_EQUAL }
    public record Condition(String pointer, Operator operator, JsonNode value) {
        public Condition { value = value == null ? null : value.deepCopy(); }
        @Override public JsonNode value() { return value == null ? null : value.deepCopy(); }
    }
    public record Node(String nodeId, String type, String name, String version,
                       List<String> dependsOn, Map<String,String> inputMapping, JsonNode config,
                       Condition condition, long timeoutMs, int retries, OnError onError,
                       String fallbackNodeId, JsonNode outputSchema, boolean enabled) {
        public Node {
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
            inputMapping = inputMapping == null ? Map.of() : Map.copyOf(inputMapping);
            config = config == null ? null : config.deepCopy();
            outputSchema = outputSchema == null ? null : outputSchema.deepCopy();
        }
        @Override public JsonNode config() { return config == null ? null : config.deepCopy(); }
        @Override public JsonNode outputSchema() { return outputSchema == null ? null : outputSchema.deepCopy(); }
    }
    public PipelineDefinition { nodes = List.copyOf(nodes); }
}
