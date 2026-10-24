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
    private InspectionTask draft(String sourceGroup) {
        var config=json.createObjectNode().put("threshold",new BigDecimal("0.900000000000000001"));
        var node=new PipelineDefinition.Node("node","SMALL_MODEL","Model","1",List.of(),Map.of(),config,null,1000,0,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
        var graph=new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(node));
        long[] counts=new long[10],correct=new long[10];counts[9]=20;correct[9]=7;
        var artifact=EmpiricalConfidenceCalibration.fit(new EmpiricalConfidenceCalibration.Source("evaluation","execution","a".repeat(64),"b".repeat(64),"c".repeat(64),"node",SelectorAssetService.hash(json.valueToTree(node).toString()),"QUALITY_HIT"),20,20,counts,correct);
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
            assertThatThrownBy(()->CalibrationTaskSnapshotService.requireRunnable(task,json)).hasMessageContaining("运行转换尚未接入");
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
}
