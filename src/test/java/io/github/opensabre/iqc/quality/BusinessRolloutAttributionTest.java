package io.github.opensabre.iqc.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.pipeline.PipelineDefinition;
import io.github.opensabre.iqc.pipelineasset.*;
import io.github.opensabre.iqc.quality.evaluation.BenchmarkVariableComparator;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.*;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real report grouping and frozen evidence codec; current source admission is isolated by a guard mock. */
class BusinessRolloutAttributionTest {
    @Test void separatesRevisionsAndOrdinaryTasksEvenWhenTheSelectedReleaseIsIdentical() throws Exception {
        var json=new ObjectMapper();var tasks=mock(InspectionTaskMapper.class);var results=mock(ConversationInspectionResultMapper.class);
        var reviews=mock(BusinessItemReviewService.class);var scope=mock(IqcDataScope.class);var guard=mock(PipelineRolloutExecutionGuard.class);
        when(scope.canView(any(),any())).thenReturn(true);
        var release=new PipelineRelease(PipelineRelease.SCHEMA,new PipelineDefinition(PipelineDefinition.SCHEMA,List.of()),
                new FusionPolicy("fusion",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),
                new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION,InspectionScoring.Mode.DEDUCTION,100,60,List.of()),List.of());
        when(reviews.pipelineRelease(any())).thenReturn(release);
        var baseline=new PipelineAssetService.Prepared("pipe",1,null,release,PipelineAssetService.hash(json.valueToTree(release).toString()));
        var candidateNode=new PipelineDefinition.Node("rule","RULE","Candidate rule","1",List.of(),Map.of(),json.createObjectNode(),null,1000,0,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
        var candidateRelease=new PipelineRelease(release.schemaVersion(),new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(candidateNode)),release.fusion(),release.scoring(),release.items());
        var candidate=new PipelineAssetService.Prepared("pipe",2,null,candidateRelease,PipelineAssetService.hash(json.valueToTree(candidateRelease).toString()));
        var ids=new ArrayList<String>();
        for(int revisionNo=1;revisionNo<=3;revisionNo++) {
            String id=revisionNo==3?"ordinary":PipelineRolloutTaskEvidence.taskId("actor","pipe","request"+revisionNo,json);
            ids.add(id);var task=new InspectionTask();task.setId(id);task.setCreatedBy("owner");task.setOwnerGroupId("group");task.setConversationIdsJson("[\"conversation\"]");
            var root=json.createObjectNode();root.set("pipelineSnapshot",json.valueToTree(baseline));
            if(revisionNo<3) {
                var policy=new PipelineRolloutPolicy(PipelineRolloutPolicy.SCHEMA,"pipe",1,2,0,"salt_0001",PipelineRolloutPolicy.State.ACTIVE,
                        new BenchmarkVariableComparator.Selection(BenchmarkVariableComparator.Dimension.PIPELINE,null,null));
                var snapshot=new PipelineRolloutService.Snapshot("iqc-pipeline-rollout-revision-v1","pipe",revisionNo,revisionNo==1?null:"c".repeat(64),policy,
                        PipelineAssetService.hash(json.valueToTree(policy).toString()),baseline.contentHash(),candidate.contentHash(),
                        revisionNo==1?PipelineRolloutService.Change.ACTIVATE:PipelineRolloutService.Change.EXPAND);
                var revision=new PipelineRolloutService.Revision("revision-"+revisionNo,revisionNo,PipelineAssetService.hash(json.valueToTree(snapshot).toString()),snapshot,null);
                var decision=policy.preview("actor","request"+revisionNo,baseline,candidate,json);
                var evidence=new PipelineRolloutTaskEvidence(PipelineRolloutTaskEvidence.SCHEMA,id,"a".repeat(64),revision,decision);
                evidence.verifyFrozen(id,json.valueToTree(baseline),json);
                root.set("rolloutSnapshot",evidence.envelope(json));
            }
            task.setRuleSnapshotJson(root.toString());when(tasks.selectById(id)).thenReturn(task);
        }
        var service=new BusinessQualityReportService(tasks,results,reviews,scope,json,mock(TaskExecutionMapper.class),guard);
        var report=service.report(ids);
        assertThat(report.groups()).hasSize(3);
        assertThat(report.groups()).extracting(BusinessQualityReportService.PolicyGroup::groupKey).doesNotHaveDuplicates();
        var gray=report.groups().stream().map(BusinessQualityReportService.PolicyGroup::rollout).filter(Objects::nonNull).toList();
        assertThat(gray).extracting(BusinessQualityReportService.RolloutAttribution::revisionId).containsExactlyInAnyOrder("revision-1","revision-2");
        assertThat(gray).allSatisfy(value->{assertThat(value.side()).isEqualTo("BASELINE");assertThat(value.selectedVersionNo()).isEqualTo(1);});
        assertThat(report.groups()).allSatisfy(group->{assertThat(group.conversationCount()).isOne();assertThat(group.missingResultCount()).isOne();assertThat(group.machine().averageScore()).isNull();});
        verify(guard,times(2)).validate(any(),anyString());
        String encoded=json.writeValueAsString(report);
        assertThat(encoded).doesNotContain("cohortHash","request1","request2","salt_0001");
    }
}
