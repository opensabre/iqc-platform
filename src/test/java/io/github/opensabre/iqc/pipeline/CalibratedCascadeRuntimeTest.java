package io.github.opensabre.iqc.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.quality.evaluation.CalibrationRuntime;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises actual scheduling and adapter calls across all supported graph schemas. */
class CalibratedCascadeRuntimeTest {
    private final ObjectMapper json = new ObjectMapper();
    private PipelineDefinition.Node node(String id, List<String> dependencies, PipelineDefinition.Condition condition) {
        return new PipelineDefinition.Node(id, "SMALL_MODEL", id, "1", dependencies, Map.of(), null,
                condition, 5000, 0, PipelineDefinition.OnError.FAIL_PIPELINE, null,
                json.createObjectNode().put("type", "object").put("additionalProperties", false)
                        .set("properties", json.createObjectNode()), true);
    }
    private PipelineDefinition.Condition condition(String pointer, JsonNode threshold) {
        return new PipelineDefinition.Condition(pointer, PipelineDefinition.Operator.CALIBRATED_CONFIDENCE_BELOW, threshold);
    }
    private void execute(String schema, String scenario, String confidence, boolean escalates) {
        var calls = new AtomicInteger();
        var fact = new Finding("finding", "QUALITY_HIT", "quality", Finding.ValueType.BOOLEAN,
                scenario.equals("unknown") ? null : BooleanNode.TRUE,
                scenario.equals("unknown") ? Finding.State.UNKNOWN : Finding.State.KNOWN,
                confidence == null ? null : new BigDecimal(confidence), Finding.Severity.NONE, "measured", List.of(),
                new Finding.Source("cheap", "SMALL_MODEL", "1", 1, "a".repeat(64)));
        var proof = json.createObjectNode().put("schemaVersion", CalibrationRuntime.SCHEMA)
                .put("artifactHash", "b".repeat(64)).put("findingId", "finding").put("supported", true)
                .put("rawConfidence", new BigDecimal("0.9"));
        if (confidence == null) proof.putNull("calibratedConfidence");
        else proof.put("calibratedConfidence", new BigDecimal(confidence));
        if (scenario.equals("unsupported")) proof.put("supported", false);
        if (scenario.equals("wrong-fact")) proof.put("findingId", "other");
        if (scenario.equals("wrong-value")) proof.put("calibratedConfidence", new BigDecimal("0.1"));
        if (scenario.equals("extra-field")) proof.put("untrusted", true);
        EngineAdapter adapter = new EngineAdapter() {
            public String type() { return "SMALL_MODEL"; }
            public NodeResult execute(NodeContext context) {
                if (context.node().nodeId().equals("expensive")) {
                    calls.incrementAndGet();
                    return new NodeResult(NodeResult.Status.SUCCESS, List.of(), Map.of(), Map.of(), null);
                }
                return new NodeResult(NodeResult.Status.SUCCESS, List.of(fact), Map.of(),
                        scenario.equals("raw") ? Map.of() : Map.of(CalibrationRuntime.KEY, proof), null);
            }
        };
        var graph = new PipelineDefinition(schema, List.of(node("cheap", List.of(), null),
                node("expensive", List.of("cheap"), condition("/upstream/cheap", json.getNodeFactory().numberNode(new BigDecimal("0.8"))))),
                schema.equals(PipelineDefinition.PARALLEL_POLICY_SCHEMA)
                        ? new PipelineDefinition.CompletionPolicy(List.of("expensive"), null) : null);
        try (var runtime = new PipelineRuntime(List.of(adapter), 2, 4)) {
            var result = runtime.execute(graph, json.createObjectNode(), Map.of(), PipelineRuntime.Mode.PRODUCTION);
            assertEquals(PipelineRuntime.Status.SUCCESS, result.status(), schema + ":" + scenario);
            assertEquals(escalates ? 1 : 0, calls.get(), schema + ":" + scenario + ":" + confidence);
            var trace = result.trace().stream().filter(value -> value.nodeId().equals("expensive")).findFirst().orElseThrow();
            assertEquals(escalates ? NodeResult.Status.SUCCESS : NodeResult.Status.SKIPPED, trace.result().status());
        }
    }
    @Test void supportedHighAndEqualConfidenceSkipActualUpgradeCalls() {
        for (var schema : List.of(PipelineDefinition.SCHEMA, PipelineDefinition.PARALLEL_SCHEMA, PipelineDefinition.PARALLEL_POLICY_SCHEMA)) {
            execute(schema, "supported", "0.9", false);
            execute(schema, "supported", "0.8", false);
        }
    }
    @Test void lowZeroUnknownMissingAndInvalidEvidenceActuallyUpgrade() {
        for (var schema : List.of(PipelineDefinition.SCHEMA, PipelineDefinition.PARALLEL_SCHEMA, PipelineDefinition.PARALLEL_POLICY_SCHEMA)) {
            execute(schema, "supported", "0.79", true);
            execute(schema, "supported", "0", true);
            execute(schema, "supported", null, true);
            for (var scenario : List.of("unknown", "unsupported", "raw", "wrong-fact", "wrong-value", "extra-field"))
                execute(schema, scenario, "0.95", true);
        }
    }
    @Test void rejectsUntrustedPointersThresholdsAndMissingExplicitDependency() {
        for (String pointer : List.of("/input/cheap", "/variables/cheap", "/upstream", "/upstream/cheap/findings"))
            assertThrows(IllegalArgumentException.class, () -> condition(pointer, json.getNodeFactory().numberNode(1)));
        for (JsonNode threshold : List.of(json.getNodeFactory().numberNode(-0.1), json.getNodeFactory().numberNode(1.1), json.getNodeFactory().textNode("0.8")))
            assertThrows(IllegalArgumentException.class, () -> condition("/upstream/cheap", threshold));
        try (var runtime = new PipelineRuntime(List.of(), 1, 1)) {
            var graph = new PipelineDefinition(PipelineDefinition.SCHEMA, List.of(node("cheap", List.of(), null),
                    node("expensive", List.of(), condition("/upstream/cheap", json.getNodeFactory().numberNode(1)))));
            assertThrows(IllegalArgumentException.class, () -> runtime.validateStructure(graph));
        }
    }
}
