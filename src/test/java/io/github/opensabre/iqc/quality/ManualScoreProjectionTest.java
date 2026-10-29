package io.github.opensabre.iqc.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Human numeric adjustments remain distinct from calculated policy results and vetoes. */
class ManualScoreProjectionTest {
 private final ObjectMapper json=new ObjectMapper();
 private InspectionScoring.Result calculated(boolean veto){
  var policy=new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION,InspectionScoring.Mode.DEDUCTION,100,60,
    List.of(new InspectionScoring.Item("item",20,veto)));
  return InspectionScoring.evaluate(policy,List.of(new InspectionScoring.Decision("item",InspectionScoring.ItemStatus.FAIL)));
 }
 private BusinessItemReviewEvaluator.ScoreAdjustment adjustment(InspectionScoring.Result score,String value){
  return new BusinessItemReviewEvaluator.ScoreAdjustment("result",BusinessItemReviewEvaluator.scoringHash("result",score,json),value," reviewed ");
 }
 @Test void boundariesAndExactTwoDecimalsDoNotOverwriteCalculatedScore(){
  var score=calculated(false);
  for(var value:List.of("0","100","99.99","0.01")){
   var projection=BusinessItemReviewEvaluator.manualScore("result",score,adjustment(score,value),true,json);
   assertThat(projection.status()).isEqualTo("APPLIED");assertThat(projection.effectiveScore()).isEqualByComparingTo(value);
   assertThat(projection.adjustment().reason()).isEqualTo("reviewed");
  }
  assertThat(score.finalScore()).isEqualByComparingTo("80");
 }
 @Test void numericOverrideDoesNotEraseCalculatedVeto(){
  var score=calculated(true);var projection=BusinessItemReviewEvaluator.manualScore("result",score,adjustment(score,"100"),true,json);
  assertThat(projection.effectiveScore()).isEqualByComparingTo("100");assertThat(score.vetoTriggered()).isTrue();
  assertThat(score.finalScore()).isEqualByComparingTo("0");
 }
 @Test void changedBaseRejectsNewCommandAndMarksInheritedCommandStale(){
  var old=calculated(false);var next=calculated(true);var command=adjustment(old,"90");
  assertThatThrownBy(()->BusinessItemReviewEvaluator.manualScore("result",next,command,true,json)).hasMessageContaining("评分已变化");
  var stale=BusinessItemReviewEvaluator.manualScore("result",next,command,false,json);
  assertThat(stale.status()).isEqualTo("STALE");assertThat(stale.effectiveScore()).isNull();
  assertThat(stale.adjustment().expectedScoringHash()).isEqualTo(command.expectedScoringHash());
 }
 @Test void invalidPrecisionRangeReasonAndResultAreRejected(){
  var score=calculated(false);
  for(var value:List.of("-1","100.01","101","1.001","1e2","01","NaN"))
   assertThatThrownBy(()->BusinessItemReviewEvaluator.manualScore("result",score,adjustment(score,value),true,json)).isInstanceOf(RuntimeException.class);
  var command=adjustment(score,"90");
  assertThatThrownBy(()->BusinessItemReviewEvaluator.manualScore("other",score,command,true,json)).isInstanceOf(RuntimeException.class);
  assertThatThrownBy(()->BusinessItemReviewEvaluator.manualScore("result",score,new BusinessItemReviewEvaluator.ScoreAdjustment("result",command.expectedScoringHash(),"90"," "),true,json)).isInstanceOf(RuntimeException.class);
 }
 @Test void wireScoreMustBeAnExactStringAndHashBindsResultIdentity() throws Exception {
  var score=calculated(false);var body=json.valueToTree(adjustment(score,"99.99"));
  assertThat(json.treeToValue(body,BusinessItemReviewEvaluator.ScoreAdjustment.class).finalScore()).isEqualTo("99.99");
  ((com.fasterxml.jackson.databind.node.ObjectNode)body).put("finalScore",99.99);
  assertThatThrownBy(()->json.treeToValue(body,BusinessItemReviewEvaluator.ScoreAdjustment.class)).isInstanceOf(Exception.class);
  assertThat(BusinessItemReviewEvaluator.scoringHash("result",score,json)).isNotEqualTo(BusinessItemReviewEvaluator.scoringHash("other",score,json));
 }
}
