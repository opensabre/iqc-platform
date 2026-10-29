package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.pipeline.PipelineDefinition;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.quality.evaluation.BenchmarkVariableComparator;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Exercises real rollout hashing/comparison/codecs without a database, service mock or model call. */
class PipelineRolloutPolicyBehaviorTest {
 private final ObjectMapper json=new ObjectMapper();
 private final tools.jackson.databind.json.JsonMapper http=tools.jackson.databind.json.JsonMapper.builder().build();
 private PipelineRelease release(int retries){
  var node=new PipelineDefinition.Node("rule","RULE","Rule","rule-v1",List.of(),Map.of(),json.createObjectNode(),null,3000,retries,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
  return new PipelineRelease(PipelineRelease.SCHEMA,new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(node)),
   new FusionPolicy("fusion",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),
   new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION,InspectionScoring.Mode.DEDUCTION,100,60,List.of()),List.of());
 }
 private PipelineAssetService.Prepared prepared(int version){return new PipelineAssetService.Prepared("pipe-1",version,null,release(version-1),(version==1?"a":"b").repeat(64));}
 private PipelineRolloutPolicy policy(int points,PipelineRolloutPolicy.State state){return new PipelineRolloutPolicy(PipelineRolloutPolicy.SCHEMA,"pipe-1",1,2,points,"salt_0001",state,new BenchmarkVariableComparator.Selection(BenchmarkVariableComparator.Dimension.PIPELINE,null,null));}
 private PipelineRolloutPolicy.Decision decide(int points,PipelineRolloutPolicy.State state,String actor,String key){return policy(points,state).preview(actor,key,prepared(1),prepared(2),json);}
 @Test void decisionPolicyHashMatchesPersistedRevisionProtocolForRoundAndNonRoundRatios(){
  for(int points:List.of(0,10,1000,5000,10000,1234)){
   var policy=policy(points,PipelineRolloutPolicy.State.ACTIVE);
   assertThat(policy.preview("actor-1","request-1",prepared(1),prepared(2),json).policyHash())
    .as("ratio %s must match revision policyHash",points).isEqualTo(PipelineAssetService.hash(json.valueToTree(policy).toString()));
  }
 }
 @Test void zeroFullAndRollbackUseExactReleaseHashes(){
  for(int i=0;i<50;i++){
   var zero=decide(0,PipelineRolloutPolicy.State.ACTIVE,"actor-1","key-"+i);
   var full=decide(10000,PipelineRolloutPolicy.State.ACTIVE,"actor-1","key-"+i);
   var rollback=decide(10000,PipelineRolloutPolicy.State.ROLLED_BACK,"actor-1","key-"+i);
   assertThat(zero.side()).isEqualTo(PipelineRolloutPolicy.Side.BASELINE);assertThat(zero.selectedVersionNo()).isEqualTo(1);assertThat(zero.selectedReleaseHash()).isEqualTo("a".repeat(64));
   assertThat(full.side()).isEqualTo(PipelineRolloutPolicy.Side.CANDIDATE);assertThat(full.selectedVersionNo()).isEqualTo(2);assertThat(full.selectedReleaseHash()).isEqualTo("b".repeat(64));
   assertThat(rollback.side()).isEqualTo(PipelineRolloutPolicy.Side.BASELINE);assertThat(rollback.selectedReleaseHash()).isEqualTo(zero.selectedReleaseHash());
   assertThat(full.bucket()).isBetween(0,9999).isEqualTo(zero.bucket()).isEqualTo(rollback.bucket());
   assertThat(full.cohortHash()).isEqualTo(zero.cohortHash()).isEqualTo(rollback.cohortHash());assertThat(full.policyHash()).isNotEqualTo(zero.policyHash()).isNotEqualTo(rollback.policyHash());
  }
 }
 @Test void deterministicAndExpansionNeverRemovesAnExistingCandidate(){
  for(int i=0;i<100;i++){
   var small=decide(1000,PipelineRolloutPolicy.State.ACTIVE,"actor-1","key-"+i);
   var larger=decide(6000,PipelineRolloutPolicy.State.ACTIVE,"actor-1","key-"+i);
   assertThat(small).isEqualTo(decide(1000,PipelineRolloutPolicy.State.ACTIVE,"actor-1","key-"+i));assertThat(small.bucket()).isEqualTo(larger.bucket());
   if(small.side()==PipelineRolloutPolicy.Side.CANDIDATE)assertThat(larger.side()).isEqualTo(PipelineRolloutPolicy.Side.CANDIDATE);
   assertThat(larger.side()).isEqualTo(larger.bucket()<6000?PipelineRolloutPolicy.Side.CANDIDATE:PipelineRolloutPolicy.Side.BASELINE);
  }
 }
 @Test void matchesIndependentLengthFramedSha256VectorAndLeavesSourcesUntouched() throws Exception {
  var baseline=prepared(1);var candidate=prepared(2);String before=json.writeValueAsString(List.of(baseline,candidate));
  var result=policy(5000,PipelineRolloutPolicy.State.ACTIVE).preview("actor-1","request-1",baseline,candidate,json);
  // Independently computed using Python hashlib over UTF-8 byte-length-prefixed frames.
  assertThat(result.cohortHash()).isEqualTo("3187e36ad8996976f61e9b88b8f5d0a0f4fc080465fd32988fa344d406af0ea0");
  assertThat(result.bucket()).isEqualTo(9568);assertThat(result.side()).isEqualTo(PipelineRolloutPolicy.Side.BASELINE);
  assertThat(json.writeValueAsString(List.of(baseline,candidate))).isEqualTo(before);
 }
 @Test void thresholdIsExclusiveAndDoesNotRehash(){
  var known=decide(0,PipelineRolloutPolicy.State.ACTIVE,"actor-1","request-1");
  assertThat(decide(known.bucket(),PipelineRolloutPolicy.State.ACTIVE,"actor-1","request-1").side()).isEqualTo(PipelineRolloutPolicy.Side.BASELINE);
  assertThat(decide(known.bucket()+1,PipelineRolloutPolicy.State.ACTIVE,"actor-1","request-1").side()).isEqualTo(PipelineRolloutPolicy.Side.CANDIDATE);
 }
 @Test void identityRequestAndSaltChangeCohortWithoutLeakingPlaintext() throws Exception {
  var original=decide(5000,PipelineRolloutPolicy.State.ACTIVE,"actor-1","request-1");
  assertThat(original.cohortHash()).isNotEqualTo(decide(5000,PipelineRolloutPolicy.State.ACTIVE,"actor-2","request-1").cohortHash()).isNotEqualTo(decide(5000,PipelineRolloutPolicy.State.ACTIVE,"actor-1","request-2").cohortHash());
  var salted=new PipelineRolloutPolicy(PipelineRolloutPolicy.SCHEMA,"pipe-1",1,2,5000,"salt_0002",PipelineRolloutPolicy.State.ACTIVE,policy(0,PipelineRolloutPolicy.State.ACTIVE).variable());
  assertThat(salted.preview("actor-1","request-1",prepared(1),prepared(2),json).cohortHash()).isNotEqualTo(original.cohortHash());
  assertThat(json.writeValueAsString(original)).doesNotContain("actor-1","request-1","salt_0001");
 }
 @Test void rejectsMismatchedDraftAndMalformedPublishedSources(){
  var valid=prepared(1);
  for(var bad:List.of(new PipelineAssetService.Prepared("other",1,null,valid.release(),valid.contentHash()),new PipelineAssetService.Prepared("pipe-1",3,null,valid.release(),valid.contentHash()),new PipelineAssetService.Prepared("pipe-1",1,1,valid.release(),valid.contentHash()),new PipelineAssetService.Prepared("pipe-1",1,null,valid.release(),"bad"))){
   assertThatThrownBy(()->policy(5000,PipelineRolloutPolicy.State.ACTIVE).preview("actor-1","request-1",bad,prepared(2),json)).hasMessageContaining("精确已发布版本");
  }
 }
 @Test void rejectsNoSemanticChangeEvenWhenVersionsAndHashesDiffer(){
  var candidate=new PipelineAssetService.Prepared("pipe-1",2,null,prepared(1).release(),"b".repeat(64));
  assertThatThrownBy(()->policy(5000,PipelineRolloutPolicy.State.ACTIVE).preview("actor-1","request-1",prepared(1),candidate,json)).hasMessageContaining("没有实际语义变化");
 }
 @Test void dualCodecsRoundTripAndRejectCoercionUnknownsAndOverflow() throws Exception {
  var valid=json.valueToTree(policy(5000,PipelineRolloutPolicy.State.ACTIVE));String body=valid.toString();
  assertThat(json.readValue(body,PipelineRolloutPolicy.class)).isEqualTo(policy(5000,PipelineRolloutPolicy.State.ACTIVE));assertThat(http.readValue(body,PipelineRolloutPolicy.class)).isEqualTo(policy(5000,PipelineRolloutPolicy.State.ACTIVE));
  for(String field:List.of("baselineVersionNo","candidateVersionNo","candidateBasisPoints"))for(String value:List.of("1.0","1.5","\"1\"","2147483648","null","true")){
   var bad=valid.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)bad).set(field,json.readTree(value));rejectBoth(bad.toString());
  }
  var unknown=valid.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)unknown).put("extra",1);rejectBoth(unknown.toString());
  var variable=valid.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)variable.path("variable")).put("extra",1);rejectBoth(variable.toString());
 }
 private void rejectBoth(String body){assertThatThrownBy(()->json.readValue(body,PipelineRolloutPolicy.class)).isInstanceOf(Exception.class);assertThatThrownBy(()->http.readValue(body,PipelineRolloutPolicy.class)).isInstanceOf(Exception.class);}
}
