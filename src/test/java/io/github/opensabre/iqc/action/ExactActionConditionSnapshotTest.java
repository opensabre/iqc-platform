package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Original Jackson snapshot round-trip and actual condition evaluation, without floating point operands. */
class ExactActionConditionSnapshotTest {
    private final ObjectMapper json=new ObjectMapper();
    private ActionPolicy.Context context(InspectionScoring.ScoreStatus status,String score) {
        return new ActionPolicy.Context(Map.of(),Map.of(),status,score==null?null:new BigDecimal(score),"NONE","PENDING");
    }
    private ActionPolicy.Condition condition(String threshold) {
        return new ActionPolicy.Condition(ActionPolicy.Kind.FINAL_SCORE_BELOW,null,null,List.of(),threshold);
    }
    @Test void frozenJsonRetainsExactOperandAndDistinctHashAfterRestore() throws Exception {
        var original=condition("99.999999999999999999");
        var snapshot=json.writeValueAsString(original);
        assertEquals("99.999999999999999999",json.readTree(snapshot).path("decimalOperand").textValue());
        assertTrue(json.readTree(snapshot).path("value").isNull());
        assertFalse(json.readTree(snapshot).has("operand"));
        var restored=json.readValue(snapshot,ActionPolicy.Condition.class);
        assertEquals(original,restored);
        assertEquals(snapshot,json.writeValueAsString(restored));
        assertNotEquals(SelectorAssetService.hash(snapshot),SelectorAssetService.hash(json.writeValueAsString(condition("100"))));
        var actual=context(InspectionScoring.ScoreStatus.FINAL,"99.999999999999999999");
        assertEquals(ActionPolicy.Match.NO_MATCH,ActionPolicy.evaluate(restored,actual));
        assertEquals(ActionPolicy.Match.MATCH,ActionPolicy.evaluate(condition("100"),actual));
    }
    @Test void pendingScoreAndMissingBusinessValueRemainUnknownAfterRestore() throws Exception {
        var score=json.readValue(json.writeValueAsString(condition("100")),ActionPolicy.Condition.class);
        assertEquals(ActionPolicy.Match.UNKNOWN,ActionPolicy.evaluate(score,context(InspectionScoring.ScoreStatus.PENDING,null)));
        var field=new ActionPolicy.Condition(ActionPolicy.Kind.BUSINESS_EQUALS,"/amount",null,List.of(),"99.999999999999999999");
        var restored=json.readValue(json.writeValueAsString(field),ActionPolicy.Condition.class);
        assertEquals(ActionPolicy.Match.UNKNOWN,ActionPolicy.evaluate(restored,context(InspectionScoring.ScoreStatus.FINAL,"100")));
        var business=json.createObjectNode().put("amount",new BigDecimal("99.999999999999999999"));
        var actual=new ActionPolicy.Context(Map.of(),Map.of(),InspectionScoring.ScoreStatus.FINAL,new BigDecimal("100"),"NONE","QUALIFIED",Map.of(),business);
        assertEquals(ActionPolicy.Match.MATCH,ActionPolicy.evaluate(restored,actual));
        assertEquals(ActionPolicy.Match.NO_MATCH,ActionPolicy.evaluate(new ActionPolicy.Condition(ActionPolicy.Kind.BUSINESS_EQUALS,"/amount",null,List.of(),"100"),actual));
    }
    @Test void oldFourArgumentSnapshotDoesNotAcquireDecimalField() throws Exception {
        var old=new ActionPolicy.Condition(ActionPolicy.Kind.FINAL_SCORE_BELOW,null,json.getNodeFactory().numberNode(100),List.of());
        var snapshot=json.writeValueAsString(old);
        assertFalse(json.readTree(snapshot).has("decimalOperand"));
        assertEquals(old,json.readValue(snapshot,ActionPolicy.Condition.class));
        assertEquals(ActionPolicy.Match.MATCH,ActionPolicy.evaluate(old,context(InspectionScoring.ScoreStatus.FINAL,"99")));
    }
    @Test void wireRejectsNumericCoercionAmbiguousOperandsAndInvalidDecimalForms() {
        for(var operand:List.of("100","\"1e2\"","\"0.0000000000000000001\"","\"123456789012345678901234567890123456789\"")) {
            var snapshot="{\"kind\":\"FINAL_SCORE_BELOW\",\"code\":null,\"value\":null,\"children\":[],\"decimalOperand\":"+operand+"}";
            assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->json.readValue(snapshot,ActionPolicy.Condition.class));
        }
        assertThrows(IllegalArgumentException.class,()->new ActionPolicy.Condition(ActionPolicy.Kind.FINAL_SCORE_BELOW,null,json.getNodeFactory().numberNode(100),List.of(),"100"));
        assertThrows(IllegalArgumentException.class,()->new ActionPolicy.Condition(ActionPolicy.Kind.ALWAYS,null,null,List.of(),"100"));
    }
}
