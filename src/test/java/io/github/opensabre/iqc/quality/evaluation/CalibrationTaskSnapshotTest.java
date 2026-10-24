package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import io.github.opensabre.iqc.pipeline.PipelineDefinition;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import io.github.opensabre.iqc.privacy.SensitiveReadAccessService;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Frozen task coefficients are private evidence; historical verification never reloads current evaluation data. */
class CalibrationTaskSnapshotTest {
    private final ObjectMapper json=new ObjectMapper();
    private final PipelineEvaluationService evaluations=mock(PipelineEvaluationService.class);
    private final ObjectProvider<PipelineEvaluationService> provider=mock(ObjectProvider.class);
    private final SensitiveReadAccessService access=mock(SensitiveReadAccessService.class);
    private final IqcDataScope scope=mock(IqcDataScope.class);
    private final CalibrationTaskSnapshotService service=new CalibrationTaskSnapshotService(provider,access,scope,json);
    private InspectionTask draft(String sourceGroup) { return draft(sourceGroup,20,7); }
    private InspectionTask draft(String sourceGroup,int samples,int successes) {
        var config=json.createObjectNode().put("threshold",new BigDecimal("0.900000000000000001"));
        var node=new PipelineDefinition.Node("node","SMALL_MODEL","Model","1",List.of(),Map.of(),config,null,1000,0,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
        var graph=new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(node));
        long[] counts=new long[10],correct=new long[10];counts[9]=samples;correct[9]=successes;
        var artifact=EmpiricalConfidenceCalibration.fit(new EmpiricalConfidenceCalibration.Source("evaluation","execution","a".repeat(64),"b".repeat(64),"c".repeat(64),"node",SelectorAssetService.hash(json.valueToTree(node).toString()),"QUALITY_HIT"),samples,samples,counts,correct);
        var reference=new EmpiricalConfidenceCalibration.Reference("evaluation","execution","node",artifact.contentHash());
        var policy=new FusionPolicy("fusion",1,FusionPolicy.Method.CONFIDENCE,Map.of(),Map.of(),"calibration",Set.of("node"),null,null,List.of(reference));
        var root=json.createObjectNode().put("businessAmount",new BigDecimal("0.900000000000000002"));
        var release=root.putObject("pipelineSnapshot").putObject("release");release.put("schemaVersion",PipelineRelease.EXACT_SCHEMA);
        release.set("pipeline",json.valueToTree(graph));release.set("fusion",json.valueToTree(policy));
        var task=new InspectionTask();task.setId("task");task.setCreatedBy("owner");task.setOwnerGroupId("group");task.setRuleSnapshotJson(root.toString());
        when(provider.getObject()).thenReturn(evaluations);when(scope.owner()).thenReturn("owner");
        when(evaluations.calibrationsForTask(List.of(reference),graph)).thenReturn(List.of(new PipelineEvaluationService.PreparedCalibration(reference,artifact,"source-owner",sourceGroup)));
        return task;
    }
    @Test void freezesExactCoefficientsAndNarrowsSourceScopeWithoutRequeryDuringReplay() {
        for(String group:Arrays.asList("group",null,"other")) {
            var task=draft(group);service.freeze(task);
            assertThat(task.getOwnerGroupId()).isEqualTo("group".equals(group)?"group":null);
            clearInvocations(provider,evaluations,access,scope);
            var sources=CalibrationTaskSnapshotService.verifiedSources(task,json);
            assertThat(sources).hasSize(1);assertThat(sources.getFirst().artifact().apply(new BigDecimal("0.95"))).isEqualByComparingTo("0.35");
            assertThatThrownBy(sources::clear).isInstanceOf(UnsupportedOperationException.class);
            assertThat(task.getRuleSnapshotJson()).contains("0.900000000000000001","0.900000000000000002");
            verifyNoInteractions(provider,evaluations,access,scope);
            assertThatCode(()->CalibrationTaskSnapshotService.requireRunnable(task,json)).doesNotThrowAnyException();
        }
    }
    @Test void rejectsRehashedMissingCoverageChangedOwnershipAndScopeWidening() throws Exception {
        var task=draft("group");service.freeze(task);String original=task.getRuleSnapshotJson();
        var root=(ObjectNode)PipelineAssetService.readFrozenTree(json,original);var envelope=(ObjectNode)root.path("calibrationInputs");var body=(ObjectNode)envelope.path("body");
        body.set("sources",json.createArrayNode());envelope.put("contentHash",SelectorAssetService.hash(body.toString()));
        task.setRuleSnapshotJson(root.toString());assertThatThrownBy(()->CalibrationTaskSnapshotService.verifiedSources(task,json)).hasMessageContaining("来源摘要无效");
        task.setRuleSnapshotJson(original);task.setCreatedBy("outsider");assertThatThrownBy(()->CalibrationTaskSnapshotService.verifiedSources(task,json)).hasMessageContaining("归属");
        task.setCreatedBy("owner");task.setOwnerGroupId(null);assertThat(CalibrationTaskSnapshotService.verifiedSources(task,json)).hasSize(1);
        task.setOwnerGroupId("other");assertThatThrownBy(()->CalibrationTaskSnapshotService.verifiedSources(task,json)).hasMessageContaining("归属");
    }
    @Test void rechecksCurrentResourceBeforeSourceReadAndPreservesLegacySnapshot() {
        var task=draft("group");String original=task.getRuleSnapshotJson();
        doThrow(io.github.opensabre.iqc.governance.IqcException.accessDenied("revoked")).when(access).current(Set.of("iqc:evaluation:report:view"));
        assertThatThrownBy(()->service.freeze(task)).hasMessageContaining("revoked");
        verifyNoInteractions(provider,evaluations);assertThat(task.getRuleSnapshotJson()).isEqualTo(original);
        var legacy=new InspectionTask();legacy.setRuleSnapshotJson("{}");service.freeze(legacy);
        assertThat(legacy.getRuleSnapshotJson()).isEqualTo("{}");assertThat(CalibrationTaskSnapshotService.verifiedSources(legacy,json)).isEmpty();
    }
    @Test void recognizesEscapedReferenceKeysAndRejectsAmbiguousJson() {
        var task=draft("group");
        String escaped="\\"+"u0063alibrationReferences";
        task.setRuleSnapshotJson(task.getRuleSnapshotJson().replace("calibrationReferences",escaped));
        assertThat(CalibrationTaskSnapshotService.requiresSnapshot(task,json)).isTrue();
        service.freeze(task);
        String frozen=task.getRuleSnapshotJson();
        assertThat(CalibrationTaskSnapshotService.verifiedSources(task,json)).hasSize(1);
        task.setRuleSnapshotJson(frozen.replace("\"calibrationInputs\":", "\"calibrationInputs\":null,\"calibrationInputs\":"));
        assertThatThrownBy(()->CalibrationTaskSnapshotService.verifiedSources(task,json)).hasMessageContaining("JSON无效");
        task.setRuleSnapshotJson(frozen+" {}");
        assertThatThrownBy(()->CalibrationTaskSnapshotService.verifiedSources(task,json)).hasMessageContaining("JSON无效");
    }
    @Test void rejectsRehashedEnvelopeWithUnknownFieldsFractionalCountsOrChangedProbability() throws Exception {
        var task=draft("group");service.freeze(task);String frozen=task.getRuleSnapshotJson();
        for(String mutation:List.of("extra","fractional","probability")) {
            var root=(ObjectNode)PipelineAssetService.readFrozenTree(json,frozen);
            var envelope=(ObjectNode)root.path("calibrationInputs");var body=(ObjectNode)envelope.path("body");
            var source=(ObjectNode)body.path("sources").get(0);
            var artifact=(ObjectNode)source.path("artifact");
            var bin=(ObjectNode)artifact.path("bins").get(9);
            switch(mutation) {
                case "extra" -> source.put("clientApproved",true);
                case "fractional" -> bin.put("samples",new BigDecimal("20.5"));
                case "probability" -> bin.put("calibratedProbability",new BigDecimal("0.99"));
                default -> throw new AssertionError(mutation);
            }
            envelope.put("contentHash",SelectorAssetService.hash(body.toString()));
            task.setRuleSnapshotJson(root.toString());
            assertThatThrownBy(()->CalibrationTaskSnapshotService.verifiedSources(task,json))
                    .as(mutation).hasMessageContaining("来源摘要无效");
        }
        task.setRuleSnapshotJson(frozen);
        assertThat(CalibrationTaskSnapshotService.verifiedSources(task,json)).hasSize(1);
    }

    private PipelineDefinition.Node frozenNode(InspectionTask task) throws Exception {
        return json.treeToValue(PipelineAssetService.readFrozenTree(json,task.getRuleSnapshotJson())
                .path("pipelineSnapshot").path("release").path("pipeline"),PipelineDefinition.class).nodes().getFirst();
    }
    private io.github.opensabre.iqc.pipeline.NodeResult raw(PipelineDefinition.Node node) {
        var fact=new io.github.opensabre.iqc.finding.Finding("finding","QUALITY_HIT","quality",
                io.github.opensabre.iqc.finding.Finding.ValueType.BOOLEAN,json.getNodeFactory().booleanNode(true),
                io.github.opensabre.iqc.finding.Finding.State.KNOWN,new BigDecimal("0.95"),
                io.github.opensabre.iqc.finding.Finding.Severity.NONE,"original",List.of(),
                new io.github.opensabre.iqc.finding.Finding.Source(node.nodeId(),node.type(),node.version(),1,"input"));
        return new io.github.opensabre.iqc.pipeline.NodeResult(io.github.opensabre.iqc.pipeline.NodeResult.Status.SUCCESS,List.of(fact),Map.of(),Map.of(),null);
    }
    private io.github.opensabre.iqc.pipeline.PipelineRuntime.Run run(io.github.opensabre.iqc.pipeline.NodeResult result,int attempt) {
        return new io.github.opensabre.iqc.pipeline.PipelineRuntime.Run(io.github.opensabre.iqc.pipeline.PipelineRuntime.Status.SUCCESS,
                List.of(new io.github.opensabre.iqc.pipeline.PipelineRuntime.Trace("node",attempt,java.time.Instant.EPOCH,1,result)),result.findings(),Map.of(),false);
    }
    @Test void appliesSupportedZeroAndUnknownBinsWithoutChangingFactOrOriginalDiagnosticSignal() throws Exception {
        for(int samples:List.of(19,20))for(int correct:List.of(0,7)) {
            var task=draft("group",samples,correct);service.freeze(task);var node=frozenNode(task);var original=raw(node);
            var transformed=CalibrationRuntime.apply(task,node,original,json);
            var probability=samples<20?null:BigDecimal.valueOf(correct).divide(BigDecimal.valueOf(samples));
            assertThat(transformed.findings().getFirst().confidence()).isEqualTo(probability);
            var before=(ObjectNode)json.valueToTree(original.findings().getFirst());before.remove("confidence");
            var after=(ObjectNode)json.valueToTree(transformed.findings().getFirst());after.remove("confidence");assertThat(after).isEqualTo(before);
            assertThat(original.findings().getFirst().confidence()).isEqualByComparingTo("0.95");
            assertThat(CalibrationRuntime.rawConfidence(transformed.findings().getFirst(),transformed,true)).isEqualByComparingTo("0.95");
            assertThat(transformed.metrics().get(CalibrationRuntime.KEY).path("supported").booleanValue()).isEqualTo(samples>=20);
            CalibrationRuntime.verify(task,run(transformed,1),json);
            assertThatThrownBy(()->CalibrationRuntime.apply(task,node,transformed,json)).hasMessageContaining("重复应用");
        }
    }
    @Test void replayRejectsChangedProofMissingTraceAndWrongAttempt() throws Exception {
        var task=draft("group");service.freeze(task);var node=frozenNode(task);
        var transformed=CalibrationRuntime.apply(task,node,raw(node),json);
        var metrics=new HashMap<>(transformed.metrics());
        ((ObjectNode)metrics.get(CalibrationRuntime.KEY)).put("calibratedConfidence",new BigDecimal("0.99"));
        var changed=new io.github.opensabre.iqc.pipeline.NodeResult(transformed.status(),transformed.findings(),transformed.variables(),metrics,null);
        assertThatThrownBy(()->CalibrationRuntime.verify(task,run(changed,1),json)).hasMessageContaining("不符合");
        assertThatThrownBy(()->CalibrationRuntime.verify(task,run(transformed,2),json)).hasMessageContaining("尝试轮次");
        var missing=new io.github.opensabre.iqc.pipeline.PipelineRuntime.Run(io.github.opensabre.iqc.pipeline.PipelineRuntime.Status.SUCCESS,List.of(),transformed.findings(),Map.of(),false);
        assertThatThrownBy(()->CalibrationRuntime.verify(task,missing,json)).hasMessageContaining("运行事实与校准轨迹");
        CalibrationRuntime.verify(task,run(transformed,1),json);
    }

}
