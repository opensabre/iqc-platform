package io.github.opensabre.iqc.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.fusion.*;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import io.github.opensabre.iqc.pipelinebridge.*;
import io.github.opensabre.iqc.scheme.*;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Exercises the business review entry point after the actual Jackson2 trace storage boundary. */
class StoredPipelineReviewReplayTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void storedKnownAndIncompleteFactsCanBeReviewedWithoutChangingMachineTrace() throws Exception {
        for (var method : List.of(FusionPolicy.Method.PRIORITY, FusionPolicy.Method.CONFIDENCE))
        for (boolean legacy : List.of(false, true))
        for (var state : List.of(Finding.State.KNOWN, Finding.State.UNKNOWN, Finding.State.NOT_EVALUATED)) {
            var config = json.createObjectNode();
            var definition = config.putObject("findingDefinition");
            definition.put("code", "COMPLIANCE").put("category", "Quality").put("valueType", "BOOLEAN");
            var rule = config.putObject("ruleSnapshot");
            rule.put("id", "rule-1").put("versionNo", 1);
            var node = new PipelineDefinition.Node("a", "RULE", "Rule", "rule-v1", List.of(), Map.of(),
                    config, null, 1000, 0, PipelineDefinition.OnError.FAIL_PIPELINE, null, null, true);
            var policy = new FusionPolicy("p", 1, method, Map.of(), Map.of(),
                    method == FusionPolicy.Method.CONFIDENCE ? "calibration-v1" : null, Set.of("a"));
            var scoring = new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION, InspectionScoring.Mode.DEDUCTION,
                    100, 60, List.of(new InspectionScoring.Item("item", 20, false)));
            var release = new PipelineRelease(PipelineRelease.SCHEMA,
                    new PipelineDefinition(PipelineDefinition.SCHEMA, List.of(node)), policy, scoring,
                    List.of(new PipelineRelease.ItemMapping("item", "COMPLIANCE", SchemeDefinition.HitMeaning.COMPLIANCE)));
            var fact = new Finding("f", "COMPLIANCE", "Quality", Finding.ValueType.BOOLEAN,
                    state == Finding.State.KNOWN ? json.getNodeFactory().booleanNode(false) : null,
                    state, state == Finding.State.KNOWN ? new BigDecimal("0.9") : null, Finding.Severity.LOW, "processed conversation", List.of(),
                    new Finding.Source("a", "RULE", "rule-v1", 1, "input"));
            var fused = FindingFusion.fuse(List.of(fact), policy);
            var status = PipelineDecisionEvaluator.decisions(release.items(), fused).getFirst().status();
            var items = List.of(new SchemeResultEvaluator.ItemResult("item", "item", "rule-1", 1, status, List.of()));
            var score = PipelineDecisionEvaluator.evaluate(scoring, release.items(), fused);
            var trace = new PipelineConversationEvaluator.Evaluation("iqc-pipeline-execution-v1", "input",
                    new PipelineRuntime.Run(PipelineRuntime.Status.SUCCESS, List.of(), List.of(fact), Map.of(), false),
                    fused, items, score, "NONE", PipelineRuntime.Mode.PRODUCTION);
            String stored = json.writeValueAsString(trace);
            // Preserve the real stored decimal representation; remove only the new optional field.
            if (legacy) stored = stored.replace(",\"finalConfidence\":0.9", "");
            var restored = json.readValue(stored, PipelineConversationEvaluator.Evaluation.class);
            var decision = new BusinessItemReviewEvaluator.Decision("result", "item", status,
                    InspectionScoring.ItemStatus.PASS, "核对处理后的会话", List.of());
            var projection = BusinessItemReviewEvaluator.evaluatePipeline("result", release, restored,
                    restored.items(), List.of(decision), Set.of());
            assertThat(projection.original().scoring().scoreStatus()).as(state.name()).isEqualTo(score.scoreStatus());
            assertThat(projection.reviewed().scoring().finalScore()).isEqualByComparingTo(BigDecimal.valueOf(100));
            assertThat(json.writeValueAsString(restored)).isEqualTo(stored);
            if (state == Finding.State.KNOWN) {
                var altered = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(stored);
                ((com.fasterxml.jackson.databind.node.ObjectNode) altered.path("fusion").get(0)).put("value", true);
                var tampered = json.treeToValue(altered, PipelineConversationEvaluator.Evaluation.class);
                assertThatThrownBy(() -> BusinessItemReviewEvaluator.evaluatePipeline("result", release, tampered,
                        tampered.items(), List.of(decision), Set.of())).hasMessageContaining("融合事实");
            }
        }
    }
}
