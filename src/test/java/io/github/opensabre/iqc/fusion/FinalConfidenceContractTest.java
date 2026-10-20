package io.github.opensabre.iqc.fusion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import io.github.opensabre.iqc.finding.Finding;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Calibrated final confidence and strict legacy trace replay contracts. */
class FinalConfidenceContractTest {
    private final ObjectMapper json=new ObjectMapper();
    private FusionPolicy policy(){return new FusionPolicy("calibrated",1,FusionPolicy.Method.CONFIDENCE,Map.of(),Map.of(),"calibration-v1",Set.of("a","b"));}
    private Finding finding(String node,boolean value,String confidence){return WeightedFusionBehaviorTest.observation(node,node,Finding.State.KNOWN,BooleanNode.valueOf(value),confidence==null?null:new BigDecimal(confidence));}
    private FindingFusion.Result copy(FindingFusion.Result r,BigDecimal confidence){return new FindingFusion.Result(r.code(),r.valueType(),r.state(),r.value(),r.severity(),r.rawFindings(),r.selectedIds(),r.rejectedIds(),r.evidence(),r.explanation(),r.policyId(),r.policyVersion(),confidence);}
    @Test void highestCalibratedValueRetainsExactProbabilityWithoutAveraging() {
        var result=FindingFusion.fuse(List.of(finding("a",true,"0.900000000000000001"),finding("b",false,"0.2")),policy()).getFirst();
        assertThat(result.finalConfidence()).isEqualByComparingTo("0.900000000000000001");assertThat(result.selectedIds()).containsExactly("a");assertThat(result.value().booleanValue()).isTrue();
    }
    @Test void equalConfidenceSourcesAgreeOrRemainConflictWithoutAProbability() {
        var same=FindingFusion.fuse(List.of(finding("a",true,"0.90"),finding("b",true,"0.9")),policy()).getFirst();
        assertThat(same.state()).isEqualTo(Finding.State.KNOWN);assertThat(same.finalConfidence()).isEqualByComparingTo("0.9");assertThat(same.selectedIds()).containsExactly("a","b");
        var conflict=FindingFusion.fuse(List.of(finding("a",true,"0.90"),finding("b",false,"0.9")),policy()).getFirst();
        assertThat(conflict.state()).isEqualTo(Finding.State.CONFLICT);assertThat(conflict.finalConfidence()).isNull();
    }
    @Test void missingConfidenceOrUncalibratedSourceIsNotPromotedToKnownConfidence() {
        var missing=FindingFusion.fuse(List.of(finding("a",true,null),finding("b",true,"0.9")),policy()).getFirst();
        assertThat(missing.state()).isEqualTo(Finding.State.UNKNOWN);assertThat(missing.finalConfidence()).isNull();
        var uncalibrated=WeightedFusionBehaviorTest.observation("c","c",Finding.State.KNOWN,BooleanNode.TRUE,new BigDecimal("0.99"));
        assertThatThrownBy(()->FindingFusion.fuse(List.of(uncalibrated),policy())).hasMessageContaining("missing confidence calibration");
    }
    @Test void otherFusionMethodsNeverTreatSupportOrRawConfidenceAsFinalCalibration() {
        var raw=List.of(finding("a",true,"0.9"),finding("b",true,"0.8"));
        for(var method:List.of(FusionPolicy.Method.PRIORITY,FusionPolicy.Method.VOTING,FusionPolicy.Method.OVERRIDE)) {
            var p=new FusionPolicy("p",1,method,Map.of(),Map.of("COMPLIANCE","a"),null,Set.of());
            assertThat(FindingFusion.fuse(raw,p).getFirst().finalConfidence()).as(method.name()).isNull();
        }
        assertThat(FindingFusion.fuse(raw,WeightedFusionBehaviorTest.policy(Map.of("a","2","b","1"))).getFirst().finalConfidence()).isNull();
    }
    @Test void legacyReplayPreservesOnlyAnAbsentFieldAndStillDetectsOtherTampering() {
        var raw=List.of(finding("a",true,"0.9"));var result=FindingFusion.fuse(raw,policy()).getFirst();
        var legacy=new FindingFusion.Result(result.code(),result.valueType(),result.state(),result.value(),result.severity(),result.rawFindings(),result.selectedIds(),result.rejectedIds(),result.evidence(),result.explanation(),result.policyId(),result.policyVersion());
        assertThat(FindingFusion.reproduce(raw,policy(),List.of(legacy))).containsExactly(legacy);
        var tampered=new FindingFusion.Result(legacy.code(),legacy.valueType(),legacy.state(),BooleanNode.FALSE,legacy.severity(),legacy.rawFindings(),legacy.selectedIds(),legacy.rejectedIds(),legacy.evidence(),legacy.explanation(),legacy.policyId(),legacy.policyVersion());
        assertThat(FindingFusion.reproduce(raw,policy(),List.of(tampered))).doesNotContain(tampered);
        var alteredConfidence=copy(result,new BigDecimal("0.8"));
        assertThat(FindingFusion.reproduce(raw,policy(),List.of(alteredConfidence))).containsExactly(result).doesNotContain(alteredConfidence);
    }
    @Test void storedResultsRoundTripExactlyAndHttpProjectionOmitsAbsentConfidence() throws Exception {
        var result=FindingFusion.fuse(List.of(finding("a",true,"0.900000000000000001")),policy()).getFirst();
        var http=tools.jackson.databind.json.JsonMapper.builder().build();
        assertThat(json.readValue(json.writeValueAsString(result),FindingFusion.Result.class).finalConfidence()).isEqualByComparingTo(result.finalConfidence());
        // Fusion Result is an internal Jackson2 trace type, not an HTTP input DTO.
        assertThat(new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(http.writeValueAsString(result)).path("finalConfidence").decimalValue()).isEqualByComparingTo(result.finalConfidence());
        var legacy=copy(result,null);String old=json.writeValueAsString(legacy);
        assertThat(old).doesNotContain("finalConfidence");assertThat(http.writeValueAsString(legacy)).doesNotContain("finalConfidence");
        var restored=json.readValue(old,FindingFusion.Result.class);
        // Stored provenance compares complete JSON trees; optional JsonNode null may have a different Java representation.
        assertThat((com.fasterxml.jackson.databind.JsonNode)json.valueToTree(restored)).isEqualTo(json.valueToTree(legacy));
        assertThat(json.writeValueAsString(restored)).isEqualTo(old);
        assertThat(restored.finalConfidence()).isNull();
    }
    @Test void invalidProbabilityOrProbabilityOnAnIncompleteResultIsRejected() {
        var result=FindingFusion.fuse(List.of(finding("a",true,"0.9")),policy()).getFirst();
        for(String value:List.of("-0.1","1.000000000000000001"))assertThatThrownBy(()->copy(result,new BigDecimal(value))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new FindingFusion.Result(result.code(),result.valueType(),Finding.State.UNKNOWN,null,result.severity(),result.rawFindings(),result.selectedIds(),result.rejectedIds(),result.evidence(),result.explanation(),result.policyId(),result.policyVersion(),BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void reviewGateUsesExactOriginalCalibratedSourcesForBothNewAndLegacyResults() {
        var result=FindingFusion.fuse(List.of(finding("a",true,"0.9")),policy()).getFirst();
        for(var candidate:List.of(result,copy(result,null))) {
            var decision=review(candidate);
            assertThat(decision.rules().getFirst().confidenceMatch()).isEqualTo(io.github.opensabre.iqc.action.ActionPolicy.Match.MATCH);
            assertThat(decision.rules().getFirst().calibratedConfidence()).isEqualByComparingTo("0.9");
        }
    }
    @Test void reviewGateTreatsAFieldContradictingItsCalibratedSourceAsUnknown() {
        var result=FindingFusion.fuse(List.of(finding("a",true,"0.9")),policy()).getFirst();
        var decision=review(copy(result,new BigDecimal("0.1")));
        assertThat(decision.rules().getFirst().confidenceMatch()).isEqualTo(io.github.opensabre.iqc.action.ActionPolicy.Match.UNKNOWN);
        assertThat(decision.rules().getFirst().calibratedConfidence()).isNull();
        assertThat(decision.status()).isEqualTo(io.github.opensabre.iqc.pipelineasset.ReviewPolicy.Status.UNKNOWN);
    }
    private io.github.opensabre.iqc.pipelineasset.ReviewPolicy.Decision review(FindingFusion.Result result) {
        var rule=new io.github.opensabre.iqc.pipelineasset.ReviewPolicy.Rule("confidence",new io.github.opensabre.iqc.action.ActionPolicy.Condition(
                io.github.opensabre.iqc.action.ActionPolicy.Kind.ALWAYS,null,null,List.of()),
                new io.github.opensabre.iqc.pipelineasset.ReviewPolicy.ConfidenceGate("COMPLIANCE","0.8",null),"1");
        var review=new io.github.opensabre.iqc.pipelineasset.ReviewPolicy(io.github.opensabre.iqc.pipelineasset.ReviewPolicy.SCHEMA,"seed",
                io.github.opensabre.iqc.pipelineasset.ReviewPolicy.UnknownHandling.KEEP_UNKNOWN,List.of(rule));
        var context=new io.github.opensabre.iqc.action.ActionPolicy.Context(Map.of("COMPLIANCE",result),Map.of(),
                io.github.opensabre.iqc.scoring.InspectionScoring.ScoreStatus.FINAL,BigDecimal.valueOf(100),"NONE","SUCCEEDED");
        return review.evaluate(context,policy(),"a".repeat(64),json);
    }

}
