package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.pipeline.PipelineDefinition;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.pipelineasset.dao.*;
import io.github.opensabre.iqc.pipelineasset.model.*;
import io.github.opensabre.iqc.quality.evaluation.BenchmarkVariableComparator;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real frozen-evidence, original-source and publication guards; only read mappers are simulated. */
class PipelineRolloutExecutionGuardTest {
 private final ObjectMapper json=new ObjectMapper();
 private record Fixture(PipelineRolloutExecutionGuard guard,PipelineRolloutRevisionMapper revisions,PipelineAssetVersionMapper versions,
  PipelineRolloutRevision revision,PipelineAssetVersion baseline,PipelineAssetVersion candidate,ObjectNode snapshot,String taskId){}
 private PipelineRelease release(int retries){
  var config=json.createObjectNode();config.put("threshold",new java.math.BigDecimal("9007199254740993.123456789012345678"));
  var node=new PipelineDefinition.Node("rule","RULE","Rule","rule-v1",List.of(),Map.of(),config,null,3000,retries,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
  return new PipelineRelease(PipelineRelease.SCHEMA,new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(node)),new FusionPolicy("fusion",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION,InspectionScoring.Mode.DEDUCTION,100,60,List.of()),List.of());
 }
 private PipelineAssetVersion version(int number,PipelineRelease release){
  var row=new PipelineAssetVersion();row.setId("v"+number);row.setPipelineId("pipe-1");row.setVersionNo(number);row.setSourceDraftRevision(4);row.setSnapshotJson(json.valueToTree(release).toString());row.setContentHash(PipelineAssetService.hash(row.getSnapshotJson()));
  var admission=json.createObjectNode().put("schemaVersion","iqc-pipeline-publication-v1");admission.putObject("gate").put("schemaVersion","iqc-publication-gate-decision-v1").put("pipelineId","pipe-1").put("draftRevision",4).put("releaseHash",row.getContentHash());
  row.setPublicationDecisionJson(admission.toString());row.setPublicationDecisionHash(PipelineAssetService.hash(admission.toString()));return row;
 }
 private Fixture fixture(){
  for(Class<?> type:List.of(PipelineAssetVersion.class,PipelineRolloutRevision.class))com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"guard"),type);
  var left=release(0);var right=release(1);var baseline=version(1,left);var candidate=version(2,right);
  var preparedLeft=new PipelineAssetService.Prepared("pipe-1",1,null,left,baseline.getContentHash());var preparedRight=new PipelineAssetService.Prepared("pipe-1",2,null,right,candidate.getContentHash());
  var policy=new PipelineRolloutPolicy(PipelineRolloutPolicy.SCHEMA,"pipe-1",1,2,5000,"salt_0001",PipelineRolloutPolicy.State.ACTIVE,new BenchmarkVariableComparator.Selection(BenchmarkVariableComparator.Dimension.PIPELINE,null,null));
  var snapshot=new PipelineRolloutService.Snapshot("iqc-pipeline-rollout-revision-v1","pipe-1",1,null,policy,PipelineAssetService.hash(json.valueToTree(policy).toString()),baseline.getContentHash(),candidate.getContentHash(),PipelineRolloutService.Change.ACTIVATE);
  String hash=PipelineAssetService.hash(json.valueToTree(snapshot).toString());var revision=new PipelineRolloutService.Revision("r1",1,hash,snapshot,null);
  var row=new PipelineRolloutRevision();row.setId("r1");row.setPipelineId("pipe-1");row.setRevisionNo(1);row.setContentHash(hash);row.setSnapshotJson(json.valueToTree(snapshot).toString());
  String id=PipelineRolloutTaskEvidence.taskId("actor-1","pipe-1","request-1",json);var decision=policy.preview("actor-1","request-1",preparedLeft,preparedRight,json);
  var evidence=new PipelineRolloutTaskEvidence(PipelineRolloutTaskEvidence.SCHEMA,id,PipelineRolloutTaskEvidence.requestFingerprint("质检",List.of("c1"),1,json),revision,decision);
  var root=json.createObjectNode();root.putObject("executionStrategy").put("mode","PIPELINE");var selected=decision.side()==PipelineRolloutPolicy.Side.BASELINE?preparedLeft:preparedRight;
  var marker=(ObjectNode)json.valueToTree(selected);marker.put("trial",false);root.set("pipelineSnapshot",marker);root.set("rolloutSnapshot",evidence.envelope(json));
  var revisions=mock(PipelineRolloutRevisionMapper.class);var versions=mock(PipelineAssetVersionMapper.class);when(revisions.selectOne(any())).thenReturn(row);when(versions.selectOne(any())).thenReturn(baseline,candidate);
  return new Fixture(new PipelineRolloutExecutionGuard(revisions,versions,json),revisions,versions,row,baseline,candidate,root,id);
 }
 private void rejects(Fixture f){assertThatThrownBy(()->f.guard().validate(f.snapshot(),f.taskId())).hasMessageContaining("原修订、发布来源或比较证据不一致");}
 @Test void validatesOriginalSourcesWithoutActorOrCurrentPolicyAndLeavesAllEvidenceUnchanged()throws Exception{
  var f=fixture();String before=json.writeValueAsString(List.of(f.snapshot(),f.revision(),f.baseline(),f.candidate()));f.guard().validate(f.snapshot(),f.taskId());
  assertThat(json.writeValueAsString(List.of(f.snapshot(),f.revision(),f.baseline(),f.candidate()))).isEqualTo(before);verify(f.revisions()).selectOne(any());verify(f.versions(),times(2)).selectOne(any());verifyNoMoreInteractions(f.revisions(),f.versions());
  assertThat(f.snapshot().path("pipelineSnapshot").path("release").path("pipeline").path("nodes").get(0).path("config").path("threshold").asText()).isEqualTo("9007199254740993.123456789012345678");
 }
 @Test void missingNullEnvelopeAndWrongContextFailBeforeSourceQueries(){
  for(String field:List.of("rolloutSnapshot","pipelineSnapshot","evaluationSnapshot","schemeSnapshot")){
   var f=fixture();if(field.equals("rolloutSnapshot")||field.equals("pipelineSnapshot"))f.snapshot().remove(field);else f.snapshot().putNull(field);rejects(f);verifyNoInteractions(f.revisions(),f.versions());
  }
  var f=fixture();f.snapshot().putNull("rolloutSnapshot");rejects(f);verifyNoInteractions(f.revisions(),f.versions());
 }
 @Test void crossTaskTransplantAndTrialMarkerFailBeforeQueries()throws Exception{
  var transplanted=fixture();assertThatThrownBy(()->transplanted.guard().validate(transplanted.snapshot(),"g"+"a".repeat(63))).hasMessageContaining("原修订");verifyNoInteractions(transplanted.revisions(),transplanted.versions());
  for(String value:List.of("true","null","\"false\"")){var f=fixture();((ObjectNode)f.snapshot().path("pipelineSnapshot")).set("trial",json.readTree(value));rejects(f);verifyNoInteractions(f.revisions(),f.versions());}
 }
 @Test void originalRevisionMissingOrWrongIdHashAndStoredBodyAreRejected(){
  var missing=fixture();when(missing.revisions().selectOne(any())).thenReturn(null);rejects(missing);verifyNoInteractions(missing.versions());
  for(String field:List.of("id","pipeline","number","hash","body")){
   var f=fixture();switch(field){case "id"->f.revision().setId("other");case "pipeline"->f.revision().setPipelineId("other");case "number"->f.revision().setRevisionNo(2);case "hash"->f.revision().setContentHash("d".repeat(64));case "body"->f.revision().setSnapshotJson("{}");}rejects(f);verifyNoInteractions(f.versions());
  }
 }
 @Test void eitherOriginalVersionMissingOrMismatchedIsRejectedEvenWhenCandidateWasNotSelected(){
  for(boolean candidate:List.of(false,true))for(String field:List.of("missing","pipeline","version","hash","body")){
   var f=fixture();var row=candidate?f.candidate():f.baseline();switch(field){case "missing"->{if(candidate)when(f.versions().selectOne(any())).thenReturn(f.baseline(),null);else when(f.versions().selectOne(any())).thenReturn(null);}case "pipeline"->row.setPipelineId("other");case "version"->row.setVersionNo(99);case "hash"->row.setContentHash("d".repeat(64));case "body"->row.setSnapshotJson("{}");}rejects(f);
  }
 }
 @Test void corruptAndRehashedWrongPublicationGatesOnEitherSideAreRejected()throws Exception{
  for(boolean candidate:List.of(false,true))for(String field:List.of("missingHash","hash","schema","pipelineId","draftRevision","releaseHash")){
   var f=fixture();var row=candidate?f.candidate():f.baseline();
   if(field.equals("missingHash"))row.setPublicationDecisionHash(null);else if(field.equals("hash"))row.setPublicationDecisionHash("d".repeat(64));else{
    var admission=(ObjectNode)json.readTree(row.getPublicationDecisionJson());if(field.equals("schema"))admission.put("schemaVersion","other");else if(field.equals("draftRevision"))((ObjectNode)admission.path("gate")).put(field,99);else ((ObjectNode)admission.path("gate")).put(field,"other");row.setPublicationDecisionJson(admission.toString());row.setPublicationDecisionHash(PipelineAssetService.hash(admission.toString()));
   }rejects(f);
  }
 }
 @Test void historicalVersionsWithoutRecordedGateRemainCompatible(){var f=fixture();for(var row:List.of(f.baseline(),f.candidate())){row.setPublicationDecisionJson(null);row.setPublicationDecisionHash(null);}assertThatCode(()->f.guard().validate(f.snapshot(),f.taskId())).doesNotThrowAnyException();}
 @Test void rehashedEnvelopeCannotReplaceOriginalComparison(){
  var f=fixture();var envelope=(ObjectNode)f.snapshot().path("rolloutSnapshot");((ObjectNode)envelope.path("evidence").path("decision").path("comparison")).put("invariantHash","d".repeat(64));envelope.put("evidenceHash",PipelineAssetService.hash(envelope.path("evidence").toString()));rejects(f);verify(f.versions(),times(2)).selectOne(any());
 }
}
