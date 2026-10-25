package io.github.opensabre.iqc.pipelinebridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.result.InspectionExecutionService;
import io.github.opensabre.iqc.result.model.InspectionResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Failed detectors retain raw citation diagnostics before the bridge returns their error verdict. */
class FrozenDetectorCitationMetricsTest {
    private final ObjectMapper json=new ObjectMapper();
    private final InspectionExecutionService executor=mock(InspectionExecutionService.class);
    private NodeResult execute(String raw,String type) throws Exception {
        var outcome=new InspectionResult();outcome.setResultStatus("ERROR");outcome.setFindingJson(raw);
        when(executor.evaluateFrozenPipelineNode(any(),any(),any(),anyList(),any(),anyString(),anyString(),anyString(),anyInt())).thenReturn(outcome);
        ObjectProvider<InspectionExecutionService> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(executor);
        var config=json.createObjectNode();config.putObject("ruleSnapshot").put("targetRole","all");
        var node=new PipelineDefinition.Node("llm",type,"Model","1",List.of(),Map.of(),config,null,1000,0,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
        var messages=json.createArrayNode();messages.addObject().put("id","m").put("conversationId","c").put("speakerRole","agent").put("content","processed text");
        var task=json.createObjectNode().put("id","task").put("currentExecutionId","run");
        var result=new FrozenDetectorEngine(type,json,provider).execute(new NodeContext(node,json.createObjectNode(),
                Map.of("__task",task,"__messages",messages),Map.of(),PipelineRuntime.Mode.EVALUATION,1));
        assertThat(outcome.getFindingJson()).isEqualTo(raw);assertThat(outcome.getResultStatus()).isEqualTo("ERROR");
        return result;
    }
    @Test void errorVerdictKeepsInvalidRawQuoteCountsWithoutPublishingFindings() throws Exception {
        var result=execute("{\"hit\":true,\"reason\":\"invalid quote\",\"evidence\":[{\"messageId\":\"missing\",\"text\":\"invented\"}]}","LLM");
        assertThat(result.status()).isEqualTo(NodeResult.Status.ERROR);assertThat(result.findings()).isEmpty();
        var metric=result.metrics().get("llmCitationGrounding");assertThat(metric.path("state").asText()).isEqualTo("MEASURED");
        assertThat(metric.path("claimedQuotes").longValue()).isEqualTo(1);assertThat(metric.path("invalidQuotes").longValue()).isEqualTo(1);
        assertThat(metric.toString()).doesNotContain("invented","missing","processed text");
    }
    @Test void unparsableObservationCannotChangeTheErrorVerdict() throws Exception {
        var result=execute("{unparsable","LLM");assertThat(result.status()).isEqualTo(NodeResult.Status.ERROR);assertThat(result.metrics()).doesNotContainKey("llmCitationGrounding");
    }
    @Test void ruleDetectorsDoNotInventLlmCitationMeasurements() throws Exception {
        var result=execute("{}","RULE");assertThat(result.status()).isEqualTo(NodeResult.Status.ERROR);assertThat(result.metrics()).doesNotContainKey("llmCitationGrounding");
    }
    @Test void llmReportedConfidenceReachesFusionOnlyThroughFrozenCalibration() throws Exception {
        for(boolean measured:List.of(true,false)) {
            var outcome=new InspectionResult();outcome.setResultStatus("HIT");outcome.setReason("detected");outcome.setEvidenceJson("[]");
            var metrics=json.createObjectNode().put("schemaVersion","iqc-llm-structured-metrics-v1").put("logicalInvocations",1).put("validOutputs",1).put("invalidOrFailedOutputs",0);
            metrics.putArray("invocationFingerprints").addObject().put("schemaVersion","iqc-llm-invocation-fingerprint-v1")
                    .put("modelKey","a".repeat(64)).put("inputHash","d".repeat(64)).put("deploymentRef","deployment-v1");
            if(measured)metrics.put("reportedConfidence",new java.math.BigDecimal("0.75"));
            outcome.setDetectorMetricsJson(metrics.toString());
            when(executor.evaluateFrozenPipelineNode(any(),any(),any(),anyList(),any(),anyString(),anyString(),anyString(),anyInt())).thenReturn(outcome);
            ObjectProvider<InspectionExecutionService> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(executor);
            var config=json.createObjectNode();config.putObject("ruleSnapshot").put("id","rule").put("targetRole","all").put("riskLevel","LOW");
            config.set("findingDefinition",json.valueToTree(new io.github.opensabre.iqc.finding.FindingDefinition(
                    io.github.opensabre.iqc.finding.FindingDefinition.SCHEMA,"QUALITY_HIT","Quality","quality",null,
                    io.github.opensabre.iqc.finding.Finding.ValueType.BOOLEAN,List.of(),io.github.opensabre.iqc.finding.Finding.Severity.LOW,false,List.of())));
            var node=new PipelineDefinition.Node("llm","LLM","Model","1",List.of(),Map.of(),config,null,1000,0,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
            var task=io.github.opensabre.iqc.quality.evaluation.CalibrationBridgeFixture.freeze(json,node);
            var messages=json.createArrayNode();messages.addObject().put("id","message").put("conversationId","conversation").put("speakerRole","agent").put("sequenceNo",0).put("content","processed conversation");
            try(var runtime=new PipelineRuntime(List.of(new FrozenDetectorEngine("LLM",json,provider)),1,2)) {
                var run=runtime.execute(new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(node)),json.createObjectNode(),
                        Map.of("__task",json.valueToTree(task),"__messages",messages),PipelineRuntime.Mode.EVALUATION);
                assertThat(run.status()).isEqualTo(PipelineRuntime.Status.SUCCESS);
                assertThat(run.findings()).hasSize(1);
                var fact=run.findings().getFirst();
                if(measured)assertThat(fact.confidence()).isEqualByComparingTo("0.5");else assertThat(fact.confidence()).isNull();
                var proof=run.trace().getFirst().result().metrics().get(io.github.opensabre.iqc.quality.evaluation.CalibrationRuntime.KEY);
                assertThat(proof.path("supported").booleanValue()).isEqualTo(measured);
                if(measured)assertThat(proof.path("rawConfidence").decimalValue()).isEqualByComparingTo("0.75");else assertThat(proof.path("rawConfidence").isNull()).isTrue();
                io.github.opensabre.iqc.quality.evaluation.CalibrationRuntime.verify(task,run,json);
                var root=io.github.opensabre.iqc.pipelineasset.PipelineAssetService.readFrozenTree(json,task.getRuleSnapshotJson());
                var policy=json.treeToValue(root.path("pipelineSnapshot").path("release").path("fusion"),io.github.opensabre.iqc.fusion.FusionPolicy.class);
                var fused=io.github.opensabre.iqc.fusion.FindingFusion.fuse(run.findings(),policy).getFirst();
                if(measured)assertThat(fused.finalConfidence()).isEqualByComparingTo("0.5");else assertThat(fused.finalConfidence()).isNull();
            }
        }
    }

}
