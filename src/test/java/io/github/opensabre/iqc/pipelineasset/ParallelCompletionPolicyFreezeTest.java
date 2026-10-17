package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.finding.*;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelinebridge.TranscriptMetricEngine;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs the owning asset service's freeze path with a published metric definition and real validation. */
class ParallelCompletionPolicyFreezeTest {
    @Test void freezeRetainsPolicyAndItsHashContributionWhileResolvingPublishedDefinition() throws Exception {
        var json=new ObjectMapper();
        var findings=mock(FindingCatalogService.class);
        var definition=new FindingDefinition(FindingDefinition.SCHEMA,"GAP_MS","Gap","TIMING",null,
                Finding.ValueType.NUMBER,List.of(),Finding.Severity.LOW,true,List.of(Finding.EvidenceType.METRIC));
        when(findings.publishedDefinition("fact",1)).thenReturn(definition);
        var config=json.createObjectNode().put("metric","TRANSCRIPT_GAP_MS").put("agentRole","agent").put("customerRole","customer");
        config.putObject("findingReference").put("id","fact").put("versionNo",1);
        var schema=json.createObjectNode().put("type","object").put("additionalProperties",false);
        var properties=schema.putObject("properties");
        properties.putObject("timing_state").put("type","string");
        properties.putObject("timing_value").put("type","number");
        var node=new PipelineDefinition.Node("timing","METRIC","Timing",TranscriptMetricEngine.VERSION,
                List.of(),Map.of(),config,null,3000,0,PipelineDefinition.OnError.FAIL_PIPELINE,null,schema,true);
        var condition=new PipelineDefinition.Condition("/variables/timing_value",PipelineDefinition.Operator.LESS_OR_EQUAL,
                json.getNodeFactory().numberNode(100));
        var completion=new PipelineDefinition.CompletionPolicy(List.of("timing"),condition);
        var pipeline=new PipelineDefinition(PipelineDefinition.PARALLEL_POLICY_SCHEMA,List.of(node),completion);
        var input=new PipelineRelease(PipelineRelease.SCHEMA,pipeline,
                new FusionPolicy("priority",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),
                new InspectionScoring.Policy("iqc-score-v2",InspectionScoring.Mode.DEDUCTION,100,60,List.of()),List.of());
        String original=json.writeValueAsString(input);
        try(var runtime=new PipelineRuntime(List.of(new TranscriptMetricEngine(json)),1,4)){
            var service=new PipelineAssetService(null,null,null,null,null,null,null,findings,json,null,runtime,null,null,null);
            PipelineRelease frozen=ReflectionTestUtils.invokeMethod(service,"freeze",input);
            assertNotNull(frozen);
            assertEquals(PipelineDefinition.PARALLEL_POLICY_SCHEMA,frozen.pipeline().schemaVersion());
            assertEquals(completion,frozen.pipeline().completionPolicy());
            assertEquals("GAP_MS",frozen.pipeline().nodes().getFirst().config().path("findingDefinition").path("code").asText());
            assertEquals(original,json.writeValueAsString(input));
            var restored=json.readValue(json.writeValueAsString(frozen),PipelineRelease.class);
            assertEquals(completion,restored.pipeline().completionPolicy());
            var changed=new PipelineDefinition(PipelineDefinition.PARALLEL_POLICY_SCHEMA,frozen.pipeline().nodes(),
                    new PipelineDefinition.CompletionPolicy(List.of("timing"),null));
            assertNotEquals(PipelineAssetService.hash(json.writeValueAsString(frozen.pipeline())),
                    PipelineAssetService.hash(json.writeValueAsString(changed)));
            verify(findings).publishedDefinition("fact",1);
        }
    }
}
