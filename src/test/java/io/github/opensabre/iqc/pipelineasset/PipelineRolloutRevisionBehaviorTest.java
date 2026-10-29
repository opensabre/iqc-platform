package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.pipeline.PipelineDefinition;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.pipelineasset.dao.PipelineRolloutRevisionMapper;
import io.github.opensabre.iqc.pipelineasset.model.*;
import io.github.opensabre.iqc.quality.evaluation.BenchmarkVariableComparator;
import io.github.opensabre.iqc.shared.IqcDataScope;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real append/validation transitions with isolated mapper storage; SQL locks and transactions are separate IT gates. */
class PipelineRolloutRevisionBehaviorTest {
 private final ObjectMapper json=new ObjectMapper();
 private final PipelineAssetService pipelines=mock(PipelineAssetService.class);
 private final PipelineRolloutRevisionMapper mapper=mock(PipelineRolloutRevisionMapper.class);
 private final IqcDataScope scope=mock(IqcDataScope.class);
 private final PipelineRolloutService service=new PipelineRolloutService(pipelines,mapper,json,scope);
 private final List<PipelineRolloutRevision> rows=new ArrayList<>();
 private PipelineRelease release(int retries){
  var node=new PipelineDefinition.Node("rule","RULE","Rule","rule-v1",List.of(),Map.of(),json.createObjectNode(),null,3000,retries,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
  return new PipelineRelease(PipelineRelease.SCHEMA,new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(node)),new FusionPolicy("fusion",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION,InspectionScoring.Mode.DEDUCTION,100,60,List.of()),List.of());
 }
 private PipelineRolloutPolicy policy(int points,PipelineRolloutPolicy.State state,String salt){return new PipelineRolloutPolicy(PipelineRolloutPolicy.SCHEMA,"pipe-1",1,2,points,salt,state,new BenchmarkVariableComparator.Selection(BenchmarkVariableComparator.Dimension.PIPELINE,null,null));}
 private PipelineRolloutPolicy active(int points){return policy(points,PipelineRolloutPolicy.State.ACTIVE,"salt_0001");}
 @BeforeEach void fixture(){
  com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"rollout"),PipelineRolloutRevision.class);
  var asset=new PipelineAsset();asset.setId("pipe-1");when(pipelines.get("pipe-1")).thenReturn(asset);when(scope.actorUserId()).thenReturn("actor-1");
  when(pipelines.rolloutSourcesForTask(eq("pipe-1"),any())).thenReturn(new PipelineAssetService.RolloutSources(new PipelineAssetService.Prepared("pipe-1",1,null,release(0),"a".repeat(64)),new PipelineAssetService.Prepared("pipe-1",2,null,release(1),"b".repeat(64))));
  when(mapper.selectOne(any())).thenAnswer(call->rows.isEmpty()?null:rows.getLast());
  when(mapper.insert(any(PipelineRolloutRevision.class))).thenAnswer(call->{var row=call.getArgument(0,PipelineRolloutRevision.class);row.setId("revision-"+row.getRevisionNo());rows.add(row);return 1;});
 }
 @Test void firstAppendFreezesSourcesAndCurrentWithNoOriginalActor() throws Exception {
  assertThat(service.current("pipe-1")).isEqualTo(new PipelineRolloutService.Current(0,null));var first=service.append("pipe-1",0,active(1000));
  assertThat(first.revisionNo()).isEqualTo(1);assertThat(first.snapshot().change()).isEqualTo(PipelineRolloutService.Change.ACTIVATE);assertThat(first.snapshot().previousContentHash()).isNull();
  assertThat(first.snapshot().baselineReleaseHash()).isEqualTo("a".repeat(64));assertThat(first.snapshot().candidateReleaseHash()).isEqualTo("b".repeat(64));assertThat(service.current("pipe-1").revision()).isEqualTo(first);
  assertThat(json.writeValueAsString(first)).doesNotContain("actor-1");verify(pipelines).lockForRollout("pipe-1");
 }
 @Test void expansionReductionRollbackResumeAndReplacementAppendLinkedHistory(){
  var first=service.append("pipe-1",0,active(1000));String original=rows.getFirst().getSnapshotJson();
  var expanded=service.append("pipe-1",1,active(5000));var reduced=service.append("pipe-1",2,active(2000));
  var rolled=service.append("pipe-1",3,policy(2000,PipelineRolloutPolicy.State.ROLLED_BACK,"salt_0001"));
  var resumed=service.append("pipe-1",4,active(2000));var replaced=service.append("pipe-1",5,policy(2000,PipelineRolloutPolicy.State.ACTIVE,"salt_0002"));
  assertThat(List.of(expanded.snapshot().change(),reduced.snapshot().change(),rolled.snapshot().change(),resumed.snapshot().change(),replaced.snapshot().change())).containsExactly(PipelineRolloutService.Change.EXPAND,PipelineRolloutService.Change.REDUCE,PipelineRolloutService.Change.ROLLBACK,PipelineRolloutService.Change.RESUME,PipelineRolloutService.Change.REPLACE);
  var revisions=List.of(first,expanded,reduced,rolled,resumed,replaced);for(int i=1;i<revisions.size();i++)assertThat(revisions.get(i).snapshot().previousContentHash()).isEqualTo(revisions.get(i-1).contentHash());
  assertThat(rows.getFirst().getSnapshotJson()).isEqualTo(original);verify(mapper,times(6)).insert(any(PipelineRolloutRevision.class));
 }
 @Test void staleCasStopsBeforeReleaseValidationAndInsert(){
  service.append("pipe-1",0,active(1000));clearInvocations(mapper,pipelines);
  assertThatThrownBy(()->service.append("pipe-1",0,active(2000))).hasMessageContaining("修订已变化");verify(pipelines,never()).rolloutSourcesForTask(anyString(),any());verify(mapper,never()).insert(any(PipelineRolloutRevision.class));
 }
 @Test void duplicatePolicyDoesNotAppend(){service.append("pipe-1",0,active(1000));assertThatThrownBy(()->service.append("pipe-1",1,active(1000))).hasMessageContaining("没有变化");assertThat(rows).hasSize(1);}
 @Test void firstRollbackAndRollbackThatChangesAllocationAreRejected(){
  assertThatThrownBy(()->service.append("pipe-1",0,policy(1000,PipelineRolloutPolicy.State.ROLLED_BACK,"salt_0001"))).hasMessageContaining("首次");service.append("pipe-1",0,active(1000));
  for(var invalid:List.of(policy(2000,PipelineRolloutPolicy.State.ROLLED_BACK,"salt_0001"),policy(1000,PipelineRolloutPolicy.State.ROLLED_BACK,"salt_0002")))assertThatThrownBy(()->service.append("pipe-1",1,invalid)).hasMessageContaining("回退必须保留");assertThat(rows).hasSize(1);
 }
 @Test void invalidRevisionAndPipelineNeverAcquireLock(){
  for(int revision:List.of(-1,Integer.MAX_VALUE))assertThatThrownBy(()->service.append("pipe-1",revision,active(1000))).hasMessageContaining("预期修订无效");
  assertThatThrownBy(()->service.append("other",0,active(1000))).hasMessageContaining("预期修订无效");verifyNoInteractions(pipelines,mapper,scope);
 }
 @Test void corruptPreviousEnvelopeBlocksAppendAndReads(){
  service.append("pipe-1",0,active(1000));rows.getFirst().setContentHash("c".repeat(64));clearInvocations(mapper,pipelines);
  assertThatThrownBy(()->service.current("pipe-1")).hasMessageContaining("证据无效");assertThatThrownBy(()->service.append("pipe-1",1,active(2000))).hasMessageContaining("证据无效");verify(mapper,never()).insert(any(PipelineRolloutRevision.class));verify(pipelines,never()).rolloutSourcesForTask(anyString(),any());
 }
 @Test void historyValidatesCanonicalScopeAndPagingWithoutCheckingExecutionDependencies(){
  service.append("pipe-1",0,active(1000));when(mapper.selectList(any())).thenReturn(List.copyOf(rows));clearInvocations(pipelines,mapper);
  assertThat(service.history("pipe-1",2,20)).hasSize(1);verify(pipelines).get("pipe-1");verify(pipelines,never()).rolloutSourcesForTask(anyString(),any());verify(pipelines,never()).lockForRollout(anyString());
  var query=org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);verify(mapper).selectList(query.capture());assertThat(query.getValue().getSqlSegment()).contains("revision_no <","ORDER BY revision_no DESC","LIMIT 20");
  clearInvocations(pipelines,mapper);for(int limit:List.of(0,101))assertThatThrownBy(()->service.history("pipe-1",null,limit)).hasMessageContaining("分页");assertThatThrownBy(()->service.history("pipe-1",0,20)).hasMessageContaining("分页");verifyNoInteractions(pipelines,mapper);
 }
 @Test void sourceValidationFailureNeverWrites(){when(pipelines.rolloutSourcesForTask(anyString(),any())).thenThrow(new IllegalStateException("source unavailable"));assertThatThrownBy(()->service.append("pipe-1",0,active(1000))).hasMessageContaining("source unavailable");assertThat(rows).isEmpty();verify(mapper,never()).insert(any(PipelineRolloutRevision.class));}
}
