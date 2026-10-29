package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fitting may pool different conversations only when measured deployment provenance is consistent. */
class CalibrationIdentityAggregationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final GoldConversation gold = mock(GoldConversation.class);

    private EmpiricalConfidenceCalibration.Artifact fit(String defect) {
        var release = mock(PipelineRelease.class);
        var config = json.createObjectNode();
        config.putObject("findingDefinition").put("code", "QUALITY_HIT");
        var node = new PipelineDefinition.Node("node", "SMALL_MODEL", "classifier", "1", List.of(),
                Map.of(), config, null, 1000, 0, PipelineDefinition.OnError.FAIL_PIPELINE, null, null, true);
        when(release.pipeline()).thenReturn(new PipelineDefinition(PipelineDefinition.SCHEMA, List.of(node)));
        when(release.fusion()).thenReturn(new FusionPolicy("fusion", 1, FusionPolicy.Method.PRIORITY,
                Map.of(), Map.of(), null, Set.of()));
        when(gold.findings()).thenReturn(List.of(new GoldFinding("QUALITY_HIT", Finding.ValueType.BOOLEAN,
                Finding.State.KNOWN, BooleanNode.TRUE, List.of())));
        var metrics = new NodeConfidenceMetrics(release, json);
        for (int i = 0; i < 20; i++) {
            String inputHash = String.format("%064x", i + 1);
            var fact = new Finding("finding-" + i, "QUALITY_HIT", "quality", Finding.ValueType.BOOLEAN,
                    BooleanNode.TRUE, Finding.State.KNOWN, new BigDecimal("0.75"), Finding.Severity.NONE,
                    "measured", List.of(), new Finding.Source("node", "SMALL_MODEL", "1", 1, inputHash));
            var identity = json.createObjectNode().put("schemaVersion", "iqc-llm-invocation-fingerprint-v1")
                    .put("modelKey", "a".repeat(64)).put("deploymentRef", "classifier-v1").put("inputHash", inputHash);
            boolean changed = i == 7;
            if (changed && defect.equals("model")) identity.put("modelKey", "b".repeat(64));
            if (changed && defect.equals("deployment")) identity.put("deploymentRef", "classifier-v2");
            if (changed && defect.equals("input")) identity.put("inputHash", "f".repeat(64));
            Map<String, com.fasterxml.jackson.databind.JsonNode> evidence = changed && defect.equals("missing")
                    ? Map.of() : Map.of("modelInvocation", identity);
            var result = new NodeResult(NodeResult.Status.SUCCESS, List.of(fact), Map.of(), evidence, null);
            metrics.add(gold, List.of(fact), List.of(new PipelineRuntime.Trace("node", 1, Instant.EPOCH, 1, result)));
        }
        var candidate = metrics.summary("task", "execution", "c".repeat(64), "d".repeat(64), "e".repeat(64))
                .nodes().getFirst().calibrationCandidate();
        assertNotNull(candidate);
        assertEquals(20, candidate.eligibleKnownGold());
        assertEquals(20, candidate.scored());
        assertEquals(0, BigDecimal.ONE.compareTo(candidate.apply(new BigDecimal("0.75"))));
        return candidate;
    }

    @Test void sameDeploymentPoolsDifferentConversationInputs() {
        var candidate = fit("none");
        assertEquals("a".repeat(64), candidate.source().modelKey());
        assertEquals("classifier-v1", candidate.source().deploymentRef());
    }

    @Test void oneUnboundSamplePermanentlyRemovesExecutableIdentityWithoutDiscardingDiagnostics() {
        for (String defect : List.of("model", "deployment", "input", "missing")) {
            var candidate = fit(defect);
            assertNull(candidate.source().modelKey(), defect);
            assertNull(candidate.source().deploymentRef(), defect);
            assertNotEquals(fit("none").contentHash(), candidate.contentHash(), defect);
        }
    }
}
