package io.github.opensabre.iqc.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** Frozen sequential/conditional or explicit parallel graph; JSON pointers address input, variables and upstream. */
public record PipelineDefinition(String schemaVersion, List<Node> nodes,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        CompletionPolicy completionPolicy) {
    public PipelineDefinition(String schemaVersion, List<Node> nodes) { this(schemaVersion, nodes, null); }
    /** Explicit opt-in; historical parallel-v1 keeps ALL and its serialized representation. */
    public static final String PARALLEL_POLICY_SCHEMA = "iqc-pipeline-parallel-v2";
    public record CompletionPolicy(List<String> requiredNodes, Condition condition) {
        public CompletionPolicy { requiredNodes = List.copyOf(requiredNodes == null ? List.of() : requiredNodes); }
    }
    public static final String SCHEMA = "iqc-pipeline-v1";
    public static final String PARALLEL_SCHEMA = "iqc-pipeline-parallel-v1";
    public enum OnError { FAIL_PIPELINE, CONTINUE, MARK_REVIEW, FALLBACK }
    public enum Operator { EXISTS, EQUALS, NOT_EQUALS, GREATER_OR_EQUAL, LESS_OR_EQUAL, GREATER_THAN, LESS_THAN, CALIBRATED_CONFIDENCE_BELOW }
    public record Condition(String pointer, Operator operator, @tools.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.HttpExactValueDeserializer.class)
            @tools.jackson.databind.annotation.JsonSerialize(using=io.github.opensabre.iqc.quality.evaluation.BenchmarkService.HttpTreeSerializer.class)
            JsonNode value) {
        public Condition {
            value = value == null ? null : value.deepCopy();
            if(operator==Operator.CALIBRATED_CONFIDENCE_BELOW){
                if(pointer==null||value==null||!value.isNumber()||!Double.isFinite(value.doubleValue())
                        ||value.decimalValue().signum()<0||value.decimalValue().compareTo(java.math.BigDecimal.ONE)>0)
                    throw new IllegalArgumentException("Calibrated cascade requires a threshold between 0 and 1");
                var path=com.fasterxml.jackson.core.JsonPointer.compile(pointer);
                if(!"upstream".equals(path.getMatchingProperty())||path.tail().getMatchingProperty()==null
                        ||path.tail().getMatchingProperty().isBlank()||!path.tail().tail().matches())
                    throw new IllegalArgumentException("Calibrated cascade must address one upstream node result");
            }
        }
        @Override public JsonNode value() { return value == null ? null : value.deepCopy(); }
    }
    public record Node(String nodeId, String type, String name, String version,
                       List<String> dependsOn, Map<String,String> inputMapping, @tools.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.HttpExactValueDeserializer.class)
            @tools.jackson.databind.annotation.JsonSerialize(using=io.github.opensabre.iqc.quality.evaluation.BenchmarkService.HttpTreeSerializer.class)
            JsonNode config,
                       Condition condition, long timeoutMs, int retries, OnError onError,
                       String fallbackNodeId, @tools.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.HttpExactValueDeserializer.class)
            @tools.jackson.databind.annotation.JsonSerialize(using=io.github.opensabre.iqc.quality.evaluation.BenchmarkService.HttpTreeSerializer.class)
            JsonNode outputSchema, boolean enabled) {
        public Node {
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
            inputMapping = inputMapping == null ? Map.of() : Map.copyOf(inputMapping);
            config = config == null ? null : config.deepCopy();
            // JSON null and an absent optional schema represent the same frozen contract.
            outputSchema = outputSchema == null || outputSchema.isNull() ? null : outputSchema.deepCopy();
        }
        @Override public JsonNode config() { return config == null ? null : config.deepCopy(); }
        @Override public JsonNode outputSchema() { return outputSchema == null ? null : outputSchema.deepCopy(); }
    }
    public PipelineDefinition { nodes = List.copyOf(nodes); }
}
