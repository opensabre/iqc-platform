package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.fusion.FindingFusion;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActionPolicySafetyTest {
    private final ObjectMapper json=new ObjectMapper();
    private ActionPolicy.Condition leaf(ActionPolicy.Kind kind,String code,Object value) {
        return new ActionPolicy.Condition(kind,code,value==null?null:json.valueToTree(value),List.of());
    }
    private ActionPolicy.Condition group(ActionPolicy.Kind kind,ActionPolicy.Condition... children) {
        return new ActionPolicy.Condition(kind,null,null,List.of(children));
    }
    private ActionPolicy.Context context(Map<String,FindingFusion.Result> findings,InspectionScoring.ScoreStatus score,BigDecimal value,String risk) {
        return new ActionPolicy.Context(findings,Map.of(),score,value,risk,null);
    }
    private ActionPolicy.Context empty() {return context(Map.of(),InspectionScoring.ScoreStatus.FINAL,null,"UNKNOWN");}
    private ActionPolicy.Action action(Integer version) {
        return new ActionPolicy.Action("save",ActionExecutionContract.Type.SAVE_RESULT,leaf(ActionPolicy.Kind.ALWAYS,null,null),
                ActionPolicy.Idempotency.PRODUCTION_SOURCE_V1,null,ActionPolicy.FailureHandling.RECORD_ONLY,json.createObjectNode(),version);
    }
    @Test void negatingMissingEvidenceRemainsUnknown() {
        var absent=leaf(ActionPolicy.Kind.FINDING_EQUALS,"fraud",true);
        assertEquals(ActionPolicy.Match.UNKNOWN,ActionPolicy.evaluate(group(ActionPolicy.Kind.NOT,absent),empty()));
    }
    @Test void allCannotMatchWithUnknownEvidenceButExplicitFalseWins() {
        var absent=leaf(ActionPolicy.Kind.ITEM_STATUS,"quality","PASS");
        var yes=leaf(ActionPolicy.Kind.ALWAYS,null,null);
        assertEquals(ActionPolicy.Match.UNKNOWN,ActionPolicy.evaluate(group(ActionPolicy.Kind.ALL,yes,absent),empty()));
        assertEquals(ActionPolicy.Match.NO_MATCH,ActionPolicy.evaluate(group(ActionPolicy.Kind.ALL,absent,group(ActionPolicy.Kind.NOT,yes)),empty()));
    }
    @Test void anyMatchesOnlyWithPositiveEvidence() {
        var absent=leaf(ActionPolicy.Kind.RESULT_STATUS,null,"ERROR");
        var yes=leaf(ActionPolicy.Kind.ALWAYS,null,null);
        assertEquals(ActionPolicy.Match.UNKNOWN,ActionPolicy.evaluate(group(ActionPolicy.Kind.ANY,group(ActionPolicy.Kind.NOT,yes),absent),empty()));
        assertEquals(ActionPolicy.Match.MATCH,ActionPolicy.evaluate(group(ActionPolicy.Kind.ANY,absent,yes),empty()));
    }
    @Test void provisionalScoreCannotTriggerEvenWhenNumericValueIsLow() {
        var condition=leaf(ActionPolicy.Kind.FINAL_SCORE_BELOW,null,60);
        for(var status:InspectionScoring.ScoreStatus.values()) {
            assertEquals(status==InspectionScoring.ScoreStatus.FINAL?ActionPolicy.Match.MATCH:ActionPolicy.Match.UNKNOWN,
                    ActionPolicy.evaluate(condition,context(Map.of(),status,BigDecimal.TEN,"UNKNOWN")),status.name());
        }
        assertEquals(ActionPolicy.Match.UNKNOWN,ActionPolicy.evaluate(condition,empty()));
    }
    @Test void numericFindingEqualityIgnoresScaleAndUnknownStateCannotTrigger() {
        var condition=leaf(ActionPolicy.Kind.FINDING_EQUALS,"amount",new BigDecimal("1.00"));
        for(var state:Finding.State.values()) {
            var fact=new FindingFusion.Result("amount",Finding.ValueType.NUMBER,state,json.valueToTree(1),Finding.Severity.NONE,
                    List.of(),List.of(),List.of(),List.of(),null,"fusion",1);
            assertEquals(state==Finding.State.KNOWN?ActionPolicy.Match.MATCH:ActionPolicy.Match.UNKNOWN,
                    ActionPolicy.evaluate(condition,context(Map.of("amount",fact),InspectionScoring.ScoreStatus.FINAL,null,"UNKNOWN")));
        }
    }
    @Test void absentRiskIsUnknownAndThresholdIsInclusive() {
        var condition=leaf(ActionPolicy.Kind.RISK_AT_LEAST,null,"HIGH");
        assertEquals(ActionPolicy.Match.UNKNOWN,ActionPolicy.evaluate(condition,empty()));
        assertEquals(ActionPolicy.Match.MATCH,ActionPolicy.evaluate(condition,context(Map.of(),InspectionScoring.ScoreStatus.FINAL,null,"HIGH")));
    }
    @Test void undeclaredReferencesAndDuplicateActionsAreRejected() {
        var a=new ActionPolicy.Action("save",ActionExecutionContract.Type.SAVE_RESULT,leaf(ActionPolicy.Kind.FINDING_EQUALS,"fraud",true),
                ActionPolicy.Idempotency.PRODUCTION_SOURCE_V1,null,ActionPolicy.FailureHandling.RECORD_ONLY,json.createObjectNode());
        var policy=new ActionPolicy(ActionPolicy.SCHEMA,List.of(a));
        assertThrows(IllegalArgumentException.class,()->policy.validateReferences(Set.of(),Set.of()));
        assertDoesNotThrow(()->policy.validateReferences(Set.of("fraud"),Set.of()));
        assertThrows(IllegalArgumentException.class,()->new ActionPolicy(ActionPolicy.SCHEMA,List.of(a,a)));
    }
    @Test void configurationIsFrozenAndLegacyVersionIsExactlyOne() {
        var a=action(null);((com.fasterxml.jackson.databind.node.ObjectNode)a.configuration()).put("injected",true);
        assertTrue(a.configuration().isEmpty());assertEquals(1,a.effectiveAdapterVersion());
        assertThrows(IllegalArgumentException.class,()->action(0));
    }
    @Test void registryRejectsMissingVersionInsteadOfUsingLatest() {
        var adapter=mock(ActionAdapter.class);
        when(adapter.definition()).thenReturn(new ActionAdapter.Definition(ActionExecutionContract.Type.SAVE_RESULT,2,true));
        var registry=new ActionAdapterRegistry(List.of(adapter));
        assertThrows(RuntimeException.class,()->registry.require(action(null)));
        assertSame(adapter,registry.require(action(2)).adapter());
        verify(adapter,times(1)).validateConfiguration(any());
        verify(adapter,never()).execute(any());
    }
    @Test void duplicateAndMutatedAdapterDefinitionsAreRejected() {
        var adapter=mock(ActionAdapter.class);
        var definition=new ActionAdapter.Definition(ActionExecutionContract.Type.SAVE_RESULT,1,true);
        when(adapter.definition()).thenReturn(definition);
        assertThrows(IllegalStateException.class,()->new ActionAdapterRegistry(List.of(adapter,adapter)));
        var registry=new ActionAdapterRegistry(List.of(adapter));
        when(adapter.definition()).thenReturn(new ActionAdapter.Definition(ActionExecutionContract.Type.SAVE_RESULT,1,false));
        assertThrows(RuntimeException.class,()->registry.require(action(null)));
        verify(adapter,never()).validateConfiguration(any());
    }
}
