package io.github.opensabre.iqc.fusion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import io.github.opensabre.iqc.finding.Finding;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Independent PRD contracts for exact weighted consensus; no providers or persistence. */
class WeightedFusionBehaviorTest {
    private final ObjectMapper json=new ObjectMapper();
    static FusionPolicy policy(Map<String,String> weights) {
        return new FusionPolicy("weighted",1,FusionPolicy.Method.WEIGHTED,Map.of(),Map.of(),null,Set.of(),weights);
    }
    static Finding observation(String id,String node,Finding.State state,com.fasterxml.jackson.databind.JsonNode value,BigDecimal confidence) {
        var type=value!=null&&value.isNumber()?Finding.ValueType.NUMBER:Finding.ValueType.BOOLEAN;
        return new Finding(id,"COMPLIANCE","SALES",type,value,state,confidence,Finding.Severity.HIGH,"processed conversation",
                List.of(new Finding.Evidence("e-"+id,Finding.EvidenceType.CONVERSATION,"message-1",null)),
                new Finding.Source(node,"RULE","rule-v1",1,"input-hash"));
    }
    static Finding known(String node,boolean value){return observation(node,node,Finding.State.KNOWN,BooleanNode.valueOf(value),null);}
    @Test void strictMajorityKeepsRejectedCounterEvidenceAndOriginalObservations() throws Exception {
        var raw=List.of(known("a",true),known("b",false));String before=json.writeValueAsString(raw);
        var result=FindingFusion.fuse(raw,policy(Map.of("a","2","b","1"))).getFirst();
        assertThat(result.state()).isEqualTo(Finding.State.KNOWN);assertThat(result.value().booleanValue()).isTrue();
        assertThat(result.selectedIds()).containsExactly("a");assertThat(result.rejectedIds()).containsExactly("b");
        assertThat(result.evidence()).extracting(Finding.Evidence::id).containsExactly("e-a","e-b");
        assertThat(result.rawFindings()).containsExactlyElementsOf(raw);assertThat(json.writeValueAsString(raw)).isEqualTo(before);
        assertThat(result.finalConfidence()).isNull();
    }
    @Test void explicitFalseIsAValidMajorityValue() {
        var result=FindingFusion.fuse(List.of(known("a",false),known("b",true)),policy(Map.of("a","2","b","1"))).getFirst();
        assertThat(result.state()).isEqualTo(Finding.State.KNOWN);assertThat(result.value().booleanValue()).isFalse();
    }
    @Test void decimalPointOnePlusPointTwoIsAnExactTieWithPointThree() {
        var result=FindingFusion.fuse(List.of(known("a",true),known("b",true),known("c",false)),policy(Map.of("a","0.1","b","0.2","c","0.3"))).getFirst();
        assertThat(result.state()).isEqualTo(Finding.State.CONFLICT);assertThat(result.value()).isNull();assertThat(result.finalConfidence()).isNull();
    }
    @Test void incompleteObservationsRemainInTheDenominatorWithoutBecomingNegativeVotes() {
        for(var state:List.of(Finding.State.UNKNOWN,Finding.State.ERROR,Finding.State.CONFLICT,Finding.State.NOT_EVALUATED)) {
            var missing=observation("b","b",state,null,null);
            var result=FindingFusion.fuse(List.of(known("a",true),missing),policy(Map.of("a","0.4","b","0.6"))).getFirst();
            assertThat(result.state()).isEqualTo(state==Finding.State.NOT_EVALUATED?Finding.State.UNKNOWN:state);assertThat(result.value()).isNull();
        }
        var skipped=observation("a","a",Finding.State.NOT_EVALUATED,null,null);
        assertThat(FindingFusion.fuse(List.of(skipped),policy(Map.of("a","1"))).getFirst().state()).isEqualTo(Finding.State.NOT_EVALUATED);
    }
    @Test void knownStrictMajorityCanOutweighAnAbstention() {
        var result=FindingFusion.fuse(List.of(known("a",true),observation("b","b",Finding.State.UNKNOWN,null,null)),policy(Map.of("a","0.6","b","0.4"))).getFirst();
        assertThat(result.state()).isEqualTo(Finding.State.KNOWN);assertThat(result.selectedIds()).containsExactly("a");
    }
    @Test void repeatedSourceOrUnweightedSourceCannotIncreaseSupport() {
        var repeated=observation("a-second","a",Finding.State.KNOWN,BooleanNode.TRUE,null);
        assertThatThrownBy(()->FindingFusion.fuse(List.of(known("a",true),repeated),policy(Map.of("a","1")))).hasMessageContaining("one observation per source");
        assertThatThrownBy(()->FindingFusion.fuse(List.of(known("a",true),known("b",false)),policy(Map.of("a","1")))).hasMessageContaining("explicit weight");
    }
    @Test void numericValueEqualityUsesExactDecimalsRatherThanJsonScale() {
        var a=observation("a","a",Finding.State.KNOWN,new DecimalNode(new BigDecimal("1")),null);
        var b=observation("b","b",Finding.State.KNOWN,new DecimalNode(new BigDecimal("1.00")),null);
        var c=observation("c","c",Finding.State.KNOWN,new DecimalNode(new BigDecimal("2")),null);
        var result=FindingFusion.fuse(List.of(a,b,c),policy(Map.of("a","0.1","b","0.2","c","0.2"))).getFirst();
        assertThat(result.state()).isEqualTo(Finding.State.KNOWN);assertThat(result.value().decimalValue()).isEqualByComparingTo("1");assertThat(result.selectedIds()).containsExactly("a","b");
    }
    @Test void weightContractRejectsAmbiguousOrOutOfRangeValuesAndNormalizesOnlyTrailingZeroes() {
        for(String value:List.of("0","-1","+1","01","1e2","NaN","1000000.000000000001","0.0000000000001"," 1","1.",""))
            assertThatThrownBy(()->policy(Map.of("a",value))).as(value).isInstanceOf(IllegalArgumentException.class);
        assertThat(policy(Map.of("a","1.00","b","0.000000000001","c","1000000")).nodeWeights()).containsEntry("a","1").containsEntry("b","0.000000000001");
        assertThatThrownBy(()->policy(Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new FusionPolicy("p",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of(),Map.of("a","1"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void bothCodecsRejectNumericBooleanAndStructuredWeightsInsteadOfCoercingThem() throws Exception {
        var http=tools.jackson.databind.json.JsonMapper.builder().build();
        for(String value:List.of("1","true","null","[]","{}")) {
            String body="{\"id\":\"p\",\"version\":1,\"method\":\"WEIGHTED\",\"nodeWeights\":{\"a\":"+value+"}}";
            assertThatThrownBy(()->json.readValue(body,FusionPolicy.class)).as("Jackson2 "+value).isInstanceOf(Exception.class);
            assertThatThrownBy(()->http.readValue(body,FusionPolicy.class)).as("Jackson3 "+value).isInstanceOf(Exception.class);
        }
        var expected=policy(Map.of("a","0.000000000001"));
        assertThat(json.readValue(json.writeValueAsString(expected),FusionPolicy.class)).isEqualTo(expected);
        assertThat(http.readValue(http.writeValueAsString(expected),FusionPolicy.class)).isEqualTo(expected);
    }
    @Test void oldSevenArgumentPolicyKeepsItsOriginalJsonWithoutNewEmptyWeights() throws Exception {
        var old=new FusionPolicy("p",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of());
        String original="{\"id\":\"p\",\"version\":1,\"method\":\"PRIORITY\",\"nodePriority\":{},\"overrideNodeByCode\":{},\"confidenceCalibrationId\":null,\"calibratedNodeIds\":[]}";
        assertThat(json.writeValueAsString(old)).isEqualTo(original);
        var http=tools.jackson.databind.json.JsonMapper.builder().build();assertThat(http.writeValueAsString(old)).isEqualTo(original);
        assertThat(json.readValue(original,FusionPolicy.class)).isEqualTo(old);assertThat(http.readValue(original,FusionPolicy.class)).isEqualTo(old);
    }
}
