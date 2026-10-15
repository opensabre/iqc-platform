package io.github.opensabre.iqc.quality;

import com.fasterxml.jackson.databind.*;
import io.github.opensabre.iqc.finding.*;
import io.github.opensabre.iqc.fusion.FindingFusion;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Human corrections retain machine provenance and can cite only frozen evidence. */
class HumanFindingReviewEvaluatorTest {
 private final ObjectMapper json=new ObjectMapper();
 private FindingFusion.Result fact(){
  var evidence=new Finding.Evidence("old",Finding.EvidenceType.CONVERSATION,"m1",null);
  var raw=new Finding("raw","CHECK","BUSINESS",Finding.ValueType.BOOLEAN,json.getNodeFactory().booleanNode(false),Finding.State.KNOWN,
    new BigDecimal("0.99"),Finding.Severity.LOW,"machine",List.of(evidence),new Finding.Source("node","RULE","rule-v1",1,"hash"));
  return new FindingFusion.Result("CHECK",Finding.ValueType.BOOLEAN,Finding.State.KNOWN,raw.value(),raw.severity(),List.of(raw),List.of("raw"),List.of(),List.of(evidence),"machine","policy",1);
 }
 private JsonNode messages(){return json.createArrayNode().add(json.createObjectNode().put("id","m1").put("conversationId","c1").put("sequenceNo",0))
   .add(json.createObjectNode().put("id","m2").put("conversationId","c1").put("sequenceNo",1));}
 private HumanFindingReviewEvaluator.Decision decision(FindingFusion.Result fact,List<String> retained,List<String> added){
  return new HumanFindingReviewEvaluator.Decision("result","CHECK",HumanFindingReviewEvaluator.findingHash(fact,json),Finding.State.KNOWN,
   json.getNodeFactory().booleanNode(true),Finding.Severity.LOW," correction ",retained,added);
 }
 private HumanFindingReviewEvaluator.Projection evaluate(FindingFusion.Result fact,List<HumanFindingReviewEvaluator.Decision> decisions){
  var definition=new FindingDefinition(FindingDefinition.SCHEMA,"CHECK","Check","BUSINESS",null,Finding.ValueType.BOOLEAN,List.of(),Finding.Severity.LOW,false,List.of(Finding.EvidenceType.CONVERSATION));
  return HumanFindingReviewEvaluator.evaluate("result",List.of(fact),Map.of("CHECK",definition),decisions,messages(),json);
 }
 @Test void correctedValueKeepsOriginalAndDoesNotBorrowMachineConfidence(){
  var fact=fact();var projection=evaluate(fact,List.of(decision(fact,List.of("old"),List.of("m2"))));
  var reviewed=projection.reviewed().getFirst();
  assertThat(projection.original().getFirst().value().booleanValue()).isFalse();assertThat(reviewed.value().booleanValue()).isTrue();
  assertThat(reviewed.rawFindings()).isEqualTo(fact.rawFindings());assertThat(reviewed.selectedIds()).isEmpty();
  assertThat(reviewed.rejectedIds()).containsExactly("raw");assertThat(reviewed.evidence()).hasSize(2);
  assertThat(projection.decisions().getFirst().reason()).isEqualTo("correction");
 }
 @Test void omittedEvidenceIsRemovedAndAddedEvidenceComesFromFrozenMessage(){
  var fact=fact();var reviewed=evaluate(fact,List.of(decision(fact,List.of(),List.of("m2")))).reviewed().getFirst();
  assertThat(reviewed.evidence()).hasSize(1);assertThat(reviewed.evidence().getFirst().reference()).isEqualTo("m2");
  assertThat(reviewed.evidence().getFirst().details().path("sequenceNo").intValue()).isEqualTo(1);
 }
 @Test void foreignEvidenceUnknownMessageAndDuplicateCorrectionAreRejected(){
  var fact=fact();
  assertThatThrownBy(()->evaluate(fact,List.of(decision(fact,List.of("foreign"),List.of())))).hasMessageContaining("其它事实");
  assertThatThrownBy(()->evaluate(fact,List.of(decision(fact,List.of(),List.of("foreign"))))).hasMessageContaining("冻结会话消息");
  var decision=decision(fact,List.of(),List.of());
  assertThatThrownBy(()->evaluate(fact,List.of(decision,decision))).hasMessageContaining("重复");
 }
 @Test void staleHashWrongSourceAndUnknownWithValueAreRejected(){
  var fact=fact();
  for(var d:List.of(
   new HumanFindingReviewEvaluator.Decision("result","CHECK","stale",Finding.State.KNOWN,json.getNodeFactory().booleanNode(true),Finding.Severity.LOW,"why",List.of(),List.of()),
   new HumanFindingReviewEvaluator.Decision("other","CHECK",HumanFindingReviewEvaluator.findingHash(fact,json),Finding.State.KNOWN,json.getNodeFactory().booleanNode(true),Finding.Severity.LOW,"why",List.of(),List.of()),
   new HumanFindingReviewEvaluator.Decision("result","CHECK",HumanFindingReviewEvaluator.findingHash(fact,json),Finding.State.UNKNOWN,json.getNodeFactory().booleanNode(true),Finding.Severity.LOW,"why",List.of(),List.of())))
   assertThatThrownBy(()->evaluate(fact,List.of(d))).isInstanceOf(RuntimeException.class);
 }
 @Test void highRiskEvidenceRequirementsAndFrozenConversationBoundaryAreEnforced(){
  var fact=fact();
  var definition=new FindingDefinition(FindingDefinition.SCHEMA,"CHECK","Check","BUSINESS",null,Finding.ValueType.BOOLEAN,List.of(),Finding.Severity.LOW,false,List.of(Finding.EvidenceType.CONVERSATION));
  var high=new HumanFindingReviewEvaluator.Decision("result","CHECK",HumanFindingReviewEvaluator.findingHash(fact,json),Finding.State.KNOWN,
   json.getNodeFactory().booleanNode(true),Finding.Severity.HIGH,"why",List.of(),List.of());
  assertThatThrownBy(()->HumanFindingReviewEvaluator.evaluate("result",List.of(fact),Map.of("CHECK",definition),List.of(high),messages(),json))
   .hasMessageContaining("冻结定义");
  var mixed=messages();((com.fasterxml.jackson.databind.node.ObjectNode)mixed.get(1)).put("conversationId","other");
  assertThatThrownBy(()->HumanFindingReviewEvaluator.evaluate("result",List.of(fact),Map.of("CHECK",definition),List.of(),mixed,json))
   .hasMessageContaining("跨会话");
 }
 @Test void unknownWithoutCanonicalValueIsValidAndDoesNotChangeOriginal(){
  var fact=fact();var unknown=new HumanFindingReviewEvaluator.Decision("result","CHECK",HumanFindingReviewEvaluator.findingHash(fact,json),
   Finding.State.UNKNOWN,null,Finding.Severity.LOW,"insufficient evidence",List.of(),List.of());
  var projection=evaluate(fact,List.of(unknown));
  assertThat(projection.reviewed().getFirst().state()).isEqualTo(Finding.State.UNKNOWN);
  assertThat(projection.reviewed().getFirst().value()).isNull();
  assertThat(projection.original().getFirst().state()).isEqualTo(Finding.State.KNOWN);
 }

 @Test void browserValueJsonPreservesNestedDecimalAndRejectsAmbiguousOrTrailingInput() throws Exception {
  var command=json.createObjectNode().put("sourceResultId","result").put("findingCode","CHECK").put("expectedFindingHash","hash")
   .put("state","KNOWN").put("severity","LOW").put("reason","why");
  command.putArray("retainedEvidenceIds");command.putArray("addedMessageIds");
  command.put("valueJson","{\"amount\":99.999999999999999999}");
  var parsed=json.treeToValue(command,HumanFindingReviewEvaluator.Decision.class);
  assertThat(parsed.value().path("amount").decimalValue()).isEqualByComparingTo("99.999999999999999999");
  assertThat(parsed.valueJson()).isNull();assertThat(json.valueToTree(parsed).has("valueJson")).isFalse();
  command.put("value",true);
  assertThatThrownBy(()->json.treeToValue(command,HumanFindingReviewEvaluator.Decision.class)).isInstanceOf(Exception.class);
  command.remove("value");command.put("valueJson","true false");
  assertThatThrownBy(()->json.treeToValue(command,HumanFindingReviewEvaluator.Decision.class)).isInstanceOf(Exception.class);
 }

}
