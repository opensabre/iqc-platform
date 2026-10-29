package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.pipeline.PipelineDefinition;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.quality.evaluation.BenchmarkVariableComparator;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.task.model.InspectionTask;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real request identity/evidence/replay logic, with task materialization and DAO authorization separately mocked. */
class PipelineRolloutTaskReplayTest {
 private final ObjectMapper json=new ObjectMapper();
 private final PipelineAssetService pipelines=mock(PipelineAssetService.class);
 private final PipelineRolloutService rollouts=mock(PipelineRolloutService.class);
 private final InspectionTaskService tasks=mock(InspectionTaskService.class);
 private final IqcDataScope scope=mock(IqcDataScope.class);
 private final PipelineRolloutTaskService service=new PipelineRolloutTaskService(pipelines,rollouts,tasks,json,scope);
 private PipelineAssetService.Prepared baseline,candidate;
 private PipelineRolloutService.Revision original;
 private InspectionTask stored;
 private PipelineRelease release(int retries){
  var node=new PipelineDefinition.Node("rule","RULE","Rule","rule-v1",List.of(),Map.of(),json.createObjectNode(),null,3000,retries,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
  return new PipelineRelease(PipelineRelease.SCHEMA,new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(node)),new FusionPolicy("fusion",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION,InspectionScoring.Mode.DEDUCTION,100,60,List.of()),List.of());
 }
 private PipelineAssetService.Prepared source(int version){var release=release(version-1);return new PipelineAssetService.Prepared("pipe-1",version,null,release,PipelineAssetService.hash(json.valueToTree(release).toString()));}
 private PipelineRolloutService.Revision revision(int number,int points,PipelineRolloutPolicy.State state,String salt){
  var policy=new PipelineRolloutPolicy(PipelineRolloutPolicy.SCHEMA,"pipe-1",1,2,points,salt,state,new BenchmarkVariableComparator.Selection(BenchmarkVariableComparator.Dimension.PIPELINE,null,null));
  var snapshot=new PipelineRolloutService.Snapshot("iqc-pipeline-rollout-revision-v1","pipe-1",number,number==1?null:"c".repeat(64),policy,PipelineAssetService.hash(json.valueToTree(policy).toString()),baseline.contentHash(),candidate.contentHash(),number==1?PipelineRolloutService.Change.ACTIVATE:state==PipelineRolloutPolicy.State.ROLLED_BACK?PipelineRolloutService.Change.ROLLBACK:PipelineRolloutService.Change.EXPAND);
  return new PipelineRolloutService.Revision("revision-"+number,number,PipelineAssetService.hash(json.valueToTree(snapshot).toString()),snapshot,null);
 }
 @BeforeEach void fixture(){
  baseline=source(1);candidate=source(2);original=revision(1,5000,PipelineRolloutPolicy.State.ACTIVE,"salt_0001");when(scope.actorUserId()).thenReturn("actor-1");
  when(rollouts.currentForTask("pipe-1")).thenReturn(original);when(rollouts.revisionForTask("pipe-1",1)).thenReturn(original);
  when(pipelines.rolloutSourcesForTask(eq("pipe-1"),any())).thenReturn(new PipelineAssetService.RolloutSources(baseline,candidate));
  when(tasks.findRolloutRequest(anyString())).thenAnswer(call->stored!=null&&stored.getId().equals(call.getArgument(0))?stored:null);
  when(tasks.createUsingPipelineRollout(any(),anyString(),anyList(),any(),anyString(),any())).thenAnswer(call->{
   var selected=call.getArgument(0,PipelineAssetService.Prepared.class);var evidence=call.getArgument(5,PipelineRolloutTaskEvidence.class);
   evidence.verify("actor-1","pipe-1",call.getArgument(4),PipelineRolloutTaskEvidence.requestFingerprint(call.getArgument(1),call.getArgument(2),call.getArgument(3),json),json.valueToTree(selected),json);
   stored=new InspectionTask();stored.setId(evidence.taskId());stored.setTaskType("PIPELINE");stored.setStatus("QUEUED");
   var root=json.createObjectNode();root.set("pipelineSnapshot",json.valueToTree(selected));root.set("rolloutSnapshot",evidence.envelope(json));stored.setRuleSnapshotJson(root.toString());return stored;
  });
 }
 private InspectionTask create(){return service.create("pipe-1","会话质检",List.of("c2","c1"),null,"request-1");}
 private ObjectNode root()throws Exception{return (ObjectNode)json.readTree(stored.getRuleSnapshotJson());}
 private void assertInvalidReplay(){assertThatThrownBy(this::create).hasMessageContaining("原灰度任务请求内容不同或冻结证据无效");}
 @Test void firstRequestFreezesExactSelectedSourceWithoutRawIdentity()throws Exception{
  var task=create();var evidence=PipelineRolloutTaskEvidence.read(root().path("rolloutSnapshot"),json);
  assertThat(task.getId()).isEqualTo(PipelineRolloutTaskEvidence.taskId("actor-1","pipe-1","request-1",json));
  assertThat(evidence.revision()).isEqualTo(original);assertThat(evidence.decision().selectedReleaseHash()).isEqualTo(root().path("pipelineSnapshot").path("contentHash").asText());
  assertThat(task.getRuleSnapshotJson()).doesNotContain("actor-1","request-1");verify(pipelines).lockForRolloutRequest("pipe-1");verify(tasks,times(1)).createUsingPipelineRollout(any(),anyString(),anyList(),any(),anyString(),any());
 }
 @Test void originalRequestIgnoresExpansionRollbackAndReplacementAndNeverMaterializesAgain()throws Exception{
  var task=create();String snapshot=task.getRuleSnapshotJson();task.setStatus("SUCCESS");clearInvocations(rollouts,pipelines,tasks);
  for(var current:List.of(revision(2,10000,PipelineRolloutPolicy.State.ACTIVE,"salt_0001"),revision(3,5000,PipelineRolloutPolicy.State.ROLLED_BACK,"salt_0001"),revision(4,5000,PipelineRolloutPolicy.State.ACTIVE,"salt_0002"))){
   when(rollouts.currentForTask("pipe-1")).thenReturn(current);assertThat(create()).isSameAs(task);assertThat(task.getStatus()).isEqualTo("SUCCESS");assertThat(task.getRuleSnapshotJson()).isEqualTo(snapshot);
  }
  verify(rollouts,never()).currentForTask(anyString());verify(pipelines,never()).rolloutSourcesForTask(anyString(),any());verify(tasks,never()).createUsingPipelineRollout(any(),anyString(),anyList(),any(),anyString(),any());verify(rollouts,times(3)).revisionForTask("pipe-1",1);
 }
 @Test void equivalentSortedDeduplicatedIdsAndDefaultConcurrencyReplayButChangedParametersFail(){
  var task=create();assertThat(service.create("pipe-1","会话质检",List.of("c1","c2","c1"),1,"request-1")).isSameAs(task);
  assertThatThrownBy(()->service.create("pipe-1","改名",List.of("c1","c2"),1,"request-1")).hasMessageContaining("原灰度任务");
  assertThatThrownBy(()->service.create("pipe-1","会话质检",List.of("c1"),1,"request-1")).hasMessageContaining("原灰度任务");
  assertThatThrownBy(()->service.create("pipe-1","会话质检",List.of("c1","c2"),2,"request-1")).hasMessageContaining("原灰度任务");
 }
 @Test void oldReplayDoesNotRequireCurrentPolicyOrAvailableSources(){var task=create();when(rollouts.currentForTask(anyString())).thenThrow(new IllegalStateException("disabled"));when(pipelines.rolloutSourcesForTask(anyString(),any())).thenThrow(new IllegalStateException("disabled"));assertThat(create()).isSameAs(task);}
 @Test void mismatchedOriginalRevisionIdOrHashIsRejected(){
  create();when(rollouts.revisionForTask("pipe-1",1)).thenReturn(new PipelineRolloutService.Revision("wrong-id",1,original.contentHash(),original.snapshot(),null));assertInvalidReplay();
  when(rollouts.revisionForTask("pipe-1",1)).thenReturn(new PipelineRolloutService.Revision(original.id(),1,"d".repeat(64),original.snapshot(),null));assertInvalidReplay();when(rollouts.revisionForTask("pipe-1",1)).thenReturn(null);assertInvalidReplay();
 }
 @Test void missingEnvelopeAndWrongTaskKindAreRejected()throws Exception{
  create();var originalJson=stored.getRuleSnapshotJson();var root=root();root.remove("rolloutSnapshot");stored.setRuleSnapshotJson(root.toString());assertInvalidReplay();stored.setRuleSnapshotJson(originalJson);stored.setTaskType("SCHEME");assertInvalidReplay();
 }
 @Test void corruptEnvelopeOrRehashedWrongChoiceStillFails()throws Exception{
  create();String originalJson=stored.getRuleSnapshotJson();var root=root();((ObjectNode)root.path("rolloutSnapshot").path("evidence").path("decision")).put("bucket",-1);stored.setRuleSnapshotJson(root.toString());assertInvalidReplay();
  var envelope=(ObjectNode)root.path("rolloutSnapshot");envelope.put("evidenceHash",PipelineAssetService.hash(envelope.path("evidence").toString()));stored.setRuleSnapshotJson(root.toString());assertInvalidReplay();
  stored.setRuleSnapshotJson(originalJson);root=root();((ObjectNode)root.path("pipelineSnapshot").path("release").path("pipeline").path("nodes").get(0)).put("retries",99);stored.setRuleSnapshotJson(root.toString());assertInvalidReplay();
 }
 @Test void changedSourceHashesBlockFirstMaterialization(){
  var wrong=new PipelineAssetService.Prepared("pipe-1",2,null,candidate.release(),"d".repeat(64));when(pipelines.rolloutSourcesForTask(eq("pipe-1"),any())).thenReturn(new PipelineAssetService.RolloutSources(baseline,wrong));assertThatThrownBy(this::create).hasMessageContaining("来源摘要已变化");verify(tasks,never()).createUsingPipelineRollout(any(),anyString(),anyList(),any(),anyString(),any());
 }
 @Test void invalidRequestStopsBeforeLockAndCrossActorUsesDifferentTaskId(){
  assertThatThrownBy(()->service.create("pipe-1","会话质检",List.of(),1,"request-1")).hasMessageContaining("有效名称");verify(pipelines,never()).lockForRolloutRequest(anyString());
  var task=create();String id=task.getId();when(scope.actorUserId()).thenReturn("actor-2");assertThat(PipelineRolloutTaskEvidence.taskId("actor-2","pipe-1","request-1",json)).isNotEqualTo(id);
 }
}
