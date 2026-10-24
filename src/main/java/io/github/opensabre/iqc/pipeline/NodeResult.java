package io.github.opensabre.iqc.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.finding.Finding;
import java.util.List;
import java.util.Map;

public record NodeResult(Status status, List<Finding> findings, Map<String,JsonNode> variables,
                         @com.fasterxml.jackson.databind.annotation.JsonDeserialize(contentUsing=MetricCodec.class)
                         Map<String,JsonNode> metrics, String error) {
    /** Only new model-confidence protocols opt into exact metrics on legacy releases. */
    public static final class MetricCodec extends com.fasterxml.jackson.databind.JsonDeserializer<JsonNode> {
        private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
        @Override public JsonNode deserialize(com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            String key=parser.currentName();
            if("llmStructuredOutput".equals(key)||"iqcConfidenceCalibration".equals(key))
                return JSON.readerFor(JsonNode.class)
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                        .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(parser);
            return parser.getCodec().readTree(parser);
        }
    }
    public enum Status { SUCCESS, SKIPPED, ERROR, TIMEOUT, CANCELLED }
    public NodeResult {
        java.util.Objects.requireNonNull(status);
        findings = List.copyOf(findings == null ? List.of() : findings);
        variables = NodeContext.snapshot(variables == null ? Map.of() : variables);
        metrics = NodeContext.snapshot(metrics == null ? Map.of() : metrics);
        if (variables.keySet().stream().anyMatch(key -> key.startsWith("__")))
            throw new IllegalArgumentException("Node outputs cannot overwrite trusted execution metadata");
        if (status != Status.SUCCESS && (!findings.isEmpty() || !variables.isEmpty()))
            throw new IllegalArgumentException("Unsuccessful node cannot publish findings or variables");
    }
    @Override public Map<String,JsonNode> variables() { return NodeContext.snapshot(variables); }
    @Override public Map<String,JsonNode> metrics() { return NodeContext.snapshot(metrics); }
    public static NodeResult failure(Status status,String error) {
        return new NodeResult(status,List.of(),Map.of(),Map.of(),error);
    }
    public static NodeResult failure(Status status,String error,Map<String,JsonNode> metrics) {
        return new NodeResult(status,List.of(),Map.of(),metrics,error);
    }
}
