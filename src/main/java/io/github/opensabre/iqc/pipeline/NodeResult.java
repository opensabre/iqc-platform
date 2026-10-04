package io.github.opensabre.iqc.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.finding.Finding;
import java.util.List;
import java.util.Map;

public record NodeResult(Status status, List<Finding> findings, Map<String,JsonNode> variables,
                         Map<String,JsonNode> metrics, String error) {
    public enum Status { SUCCESS, SKIPPED, ERROR, TIMEOUT, CANCELLED }
    public NodeResult {
        java.util.Objects.requireNonNull(status);
        findings = List.copyOf(findings == null ? List.of() : findings);
        variables = NodeContext.snapshot(variables == null ? Map.of() : variables);
        metrics = NodeContext.snapshot(metrics == null ? Map.of() : metrics);
        if (status != Status.SUCCESS && (!findings.isEmpty() || !variables.isEmpty()))
            throw new IllegalArgumentException("Unsuccessful node cannot publish findings or variables");
    }
    @Override public Map<String,JsonNode> variables() { return NodeContext.snapshot(variables); }
    @Override public Map<String,JsonNode> metrics() { return NodeContext.snapshot(metrics); }
    public static NodeResult failure(Status status,String error) {
        return new NodeResult(status,List.of(),Map.of(),Map.of(),error);
    }
}
