package io.github.opensabre.iqc.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.LinkedHashMap;

public record NodeContext(PipelineDefinition.Node node, JsonNode input, Map<String,JsonNode> variables,
                          Map<String,NodeResult> upstream, PipelineRuntime.Mode mode, int attempt) {
    public NodeContext {
        input = input.deepCopy(); variables = snapshot(variables); upstream = Map.copyOf(upstream);
    }
    @Override public JsonNode input() { return input.deepCopy(); }
    @Override public Map<String,JsonNode> variables() { return snapshot(variables); }
    static Map<String,JsonNode> snapshot(Map<String,JsonNode> source) {
        var copy = new LinkedHashMap<String,JsonNode>();
        source.forEach((key,value) -> copy.put(key,value.deepCopy()));
        return java.util.Collections.unmodifiableMap(copy);
    }
}
