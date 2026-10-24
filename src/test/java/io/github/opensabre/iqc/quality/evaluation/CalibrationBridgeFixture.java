package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import io.github.opensabre.iqc.pipeline.PipelineDefinition;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import io.github.opensabre.iqc.privacy.SensitiveReadAccessService;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static org.mockito.Mockito.*;

/** Real private freeze with a controlled authorized source for bridge tests. */
public final class CalibrationBridgeFixture {
    private CalibrationBridgeFixture() { }
    public static InspectionTask freeze(ObjectMapper json,PipelineDefinition.Node node) {
        long[] counts=new long[10],correct=new long[10];counts[7]=20;correct[7]=10;
        var artifact=EmpiricalConfidenceCalibration.fit(new EmpiricalConfidenceCalibration.Source("source","source-run","a".repeat(64),"b".repeat(64),"c".repeat(64),node.nodeId(),SelectorAssetService.hash(json.valueToTree(node).toString()),node.config().path("findingDefinition").path("code").asText()),20,20,counts,correct);
        var reference=new EmpiricalConfidenceCalibration.Reference("source","source-run",node.nodeId(),artifact.contentHash());
        var graph=new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(node));
        var fusion=new FusionPolicy("fusion",1,FusionPolicy.Method.CONFIDENCE,Map.of(),Map.of(),"calibration",Set.of(node.nodeId()),null,null,List.of(reference));
        var root=json.createObjectNode();var release=root.putObject("pipelineSnapshot").putObject("release");
        release.put("schemaVersion",PipelineRelease.EXACT_SCHEMA);release.set("pipeline",json.valueToTree(graph));release.set("fusion",json.valueToTree(fusion));
        var task=new InspectionTask();task.setId("task");task.setCurrentExecutionId("execution");task.setCreatedBy("owner");task.setRuleSnapshotJson(root.toString());
        var evaluations=mock(PipelineEvaluationService.class);ObjectProvider<PipelineEvaluationService> provider=mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(evaluations);var scope=mock(IqcDataScope.class);when(scope.owner()).thenReturn("owner");
        when(evaluations.calibrationsForTask(List.of(reference),graph)).thenReturn(List.of(new PipelineEvaluationService.PreparedCalibration(reference,artifact,"source-owner",null)));
        new CalibrationTaskSnapshotService(provider,mock(SensitiveReadAccessService.class),scope,json).freeze(task);
        return task;
    }
}
