package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.opensabre.iqc.pipeline.PipelineDefinition;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.model.TaskExecution;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Batch orchestration checks; report artifact validation is exercised separately by PipelineEvaluationReportTest. */
class CalibrationBatchReadTest {
    private final InspectionTaskMapper tasks=mock(InspectionTaskMapper.class);
    private final TaskExecutionMapper executions=mock(TaskExecutionMapper.class);
    private final IqcDataScope scope=mock(IqcDataScope.class);
    private final PipelineEvaluationService service=spy(new PipelineEvaluationService(null,null,null,tasks,executions,null,null,null,new ObjectMapper(),scope));
    private final PipelineDefinition target=new PipelineDefinition(PipelineDefinition.SCHEMA,List.of());
    private final List<String> locks=new ArrayList<>();

    private EmpiricalConfidenceCalibration.Reference source(String taskId,String... nodes) {
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(),"batch"),InspectionTask.class);
        when(scope.owner()).thenReturn("actor"); when(scope.canView(any(),any())).thenReturn(true);
        doAnswer(call->{
            var q=(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?>)call.getArgument(0);
            assertThat(q.getSqlSegment()).contains("FOR UPDATE");
            String id=(String)q.getParamNameValuePairs().values().iterator().next();locks.add(id);
            var locked=new InspectionTask();locked.setId(id);locked.setTaskType("PIPELINE_EVALUATION");locked.setCreatedBy("owner");locked.setRuleSnapshotJson("{}");return locked;
        }).when(tasks).selectOne(any());
        var prepared=mock(PipelineEvaluationService.Prepared.class);var release=mock(PipelineRelease.class);
        when(release.pipeline()).thenReturn(target);when(prepared.release()).thenReturn(release);
        doReturn(prepared).when(service).prepare(any(),any(),eq(false));
        var execution=new TaskExecution();execution.setId(taskId+"-execution");execution.setTaskId(taskId);execution.setStatus("SUCCEEDED");
        when(executions.selectById(execution.getId())).thenReturn(execution);
        var report=mock(PipelineEvaluationService.Report.class);when(report.complete()).thenReturn(true);
        when(report.expectedSamples()).thenReturn(1);when(report.successfulSamples()).thenReturn(1);
        var entries=new ArrayList<NodeConfidenceMetrics.Node>();
        for(String node:nodes) {
            long[] counts=new long[10],correct=new long[10];counts[0]=1;correct[0]=1;
            var artifact=EmpiricalConfidenceCalibration.fit(new EmpiricalConfidenceCalibration.Source(taskId,execution.getId(),"a".repeat(64),"b".repeat(64),"c".repeat(64),node,"d".repeat(64),"QUALITY_HIT","e".repeat(64),"deployment-one"),1,1,counts,correct);
            entries.add(new NodeConfidenceMetrics.Node(node,"LLM","1","QUALITY_HIT",null,artifact));
        }
        when(report.nodeConfidenceMetrics()).thenReturn(new NodeConfidenceMetrics.Summary("iqc-node-confidence-diagnostics-v1",entries));
        doReturn(report).when(service).report(taskId,execution.getId());
        return new EmpiricalConfidenceCalibration.Reference(taskId,execution.getId(),nodes[0],entries.getFirst().calibrationCandidate().contentHash());
    }
    @Test void locksSourcesInStableOrderAndComputesEachExecutionReportOnce() {
        var a1=source("a","n1","n2");var z=source("z","n3");
        var a2=new EmpiricalConfidenceCalibration.Reference("a",a1.executionId(),"n2",service.report("a",a1.executionId()).nodeConfidenceMetrics().nodes().get(1).calibrationCandidate().contentHash());
        clearInvocations(service);
        var resolved=service.calibrationsForTask(List.of(z,a2,a1),target);
        assertThat(locks).containsExactly("a","z");
        assertThat(resolved).extracting(value->value.reference().nodeId()).containsExactly("n1","n2","n3");
        verify(service,times(1)).report("a",a1.executionId());verify(service,times(1)).report("z",z.executionId());
    }
    @Test void changedDetectionGraphIsRejectedBeforeExecutionOrReportReads() {
        var reference=source("a","n1");
        var changed=new PipelineDefinition(PipelineDefinition.PARALLEL_SCHEMA,List.of());
        assertThatThrownBy(()->service.calibrationForTask(reference,changed)).hasMessageContaining("目标检测图");
        verifyNoInteractions(executions);verify(service,never()).report(anyString(),anyString());
    }
    @Test void exactReferenceRejectsMissingAndMalformedIdentityOrHash() {
        assertThatThrownBy(()->new EmpiricalConfidenceCalibration.Reference("a","e","n","A".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new EmpiricalConfidenceCalibration.Reference("a","e","n",null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new EmpiricalConfidenceCalibration.Reference("a/other","e","n","a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new EmpiricalConfidenceCalibration.Reference("a"," ","n","a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(tasks,executions);
    }
}
