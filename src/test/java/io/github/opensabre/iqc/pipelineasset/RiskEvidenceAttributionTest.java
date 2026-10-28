package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.opensabre.iqc.action.ActionPolicy;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.fusion.*;
import io.github.opensabre.iqc.pipeline.PipelineDefinition;
import io.github.opensabre.iqc.pipelinebridge.PipelineDecisionEvaluator;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.scheme.SchemeDefinition;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Matched risk paths must carry their own valid evidence; pure fixtures do not execute detector graphs. */
class RiskEvidenceAttributionTest {
    private static final JsonNodeFactory J=JsonNodeFactory.instance;
    private ActionPolicy.Condition eq(String code,boolean value){return new ActionPolicy.Condition(ActionPolicy.Kind.FINDING_EQUALS,code,J.booleanNode(value),List.of());}
    private ActionPolicy.Condition group(ActionPolicy.Kind kind,ActionPolicy.Condition... children){return new ActionPolicy.Condition(kind,null,null,List.of(children));}
    private RiskPolicy policy(Finding.Severity fallback,RiskPolicy.Rule... rules){return new RiskPolicy(RiskPolicy.SCHEMA,fallback,List.of(rules));}
    private RiskPolicy.Rule rule(String id,Finding.Severity level,ActionPolicy.Condition condition){return new RiskPolicy.Rule(id,level,condition);}
    private Finding raw(String id,String code,boolean value,Finding.Severity severity,boolean evidence){
        return new Finding(id,code,"compliance",Finding.ValueType.BOOLEAN,J.booleanNode(value),Finding.State.KNOWN,null,severity,"observed",
            evidence?List.of(new Finding.Evidence("quote",Finding.EvidenceType.CONVERSATION,"message-"+code,null)):List.of(),
            new Finding.Source(id,"RULE","1",1,"input"));
    }
    private FindingFusion.Result fact(String code,boolean value,Finding.Severity severity,boolean selected,boolean rejected){
        return FindingFusion.fuse(List.of(raw("winner-"+code,code,value,severity,selected),raw("loser-"+code,code,value,severity,rejected)),
            new FusionPolicy("priority",1,FusionPolicy.Method.PRIORITY,Map.of("winner-"+code,2,"loser-"+code,1),Map.of(),null,Set.of())).getFirst();
    }
    private ActionPolicy.Context context(String risk,FindingFusion.Result... facts){
        var byCode=new LinkedHashMap<String,FindingFusion.Result>();for(var fact:facts)byCode.put(fact.code(),fact);
        return new ActionPolicy.Context(byCode,Map.of("itemA",InspectionScoring.ItemStatus.FAIL,"itemB",InspectionScoring.ItemStatus.FAIL),InspectionScoring.ScoreStatus.FINAL,java.math.BigDecimal.TEN,risk,"UNQUALIFIED");
    }
    private PipelineRelease release(RiskPolicy policy){
        return new PipelineRelease(PipelineRelease.SCHEMA,new PipelineDefinition(PipelineDefinition.SCHEMA,List.of()),
            new FusionPolicy("priority",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),
            new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION,InspectionScoring.Mode.DEDUCTION,100,60,
                List.of(new InspectionScoring.Item("itemA",50,false),new InspectionScoring.Item("itemB",50,false))),
            List.of(new PipelineRelease.ItemMapping("itemA","A",SchemeDefinition.HitMeaning.VIOLATION),new PipelineRelease.ItemMapping("itemB","B",SchemeDefinition.HitMeaning.VIOLATION)),null,null,null,policy);
    }
    @Test void unrelatedSelectedEvidenceCannotJustifyMatchedHighRule(){
        var risk=policy(Finding.Severity.LOW,rule("a",Finding.Severity.HIGH,eq("A",true)));
        var input=context("HIGH",fact("A",true,Finding.Severity.LOW,false,false),fact("B",true,Finding.Severity.LOW,true,false));
        assertTrue(RiskPolicy.hasSelectedEvidence(input.findings().values()));
        assertEquals("HIGH",risk.evaluate(input).level());assertFalse(PipelineDecisionEvaluator.hasRiskEvidence(release(risk),input,true));
    }
    @Test void anyCanUseOnlyEvidenceFromAnActuallyMatchedAlternative(){
        var risk=policy(Finding.Severity.LOW,rule("either",Finding.Severity.HIGH,group(ActionPolicy.Kind.ANY,eq("A",true),eq("B",true))));
        assertFalse(PipelineDecisionEvaluator.hasRiskEvidence(release(risk),context("HIGH",fact("A",true,Finding.Severity.LOW,false,false),fact("B",false,Finding.Severity.LOW,true,false)),true));
        assertTrue(PipelineDecisionEvaluator.hasRiskEvidence(release(risk),context("HIGH",fact("A",false,Finding.Severity.LOW,false,false),fact("B",true,Finding.Severity.LOW,true,false)),true));
    }
    @Test void allRequiresTheWholeConditionToMatchBeforeUsingOneSupportedChild(){
        var risk=policy(Finding.Severity.LOW,rule("both",Finding.Severity.HIGH,group(ActionPolicy.Kind.ALL,eq("A",true),eq("B",true))));
        assertTrue(PipelineDecisionEvaluator.hasRiskEvidence(release(risk),context("HIGH",fact("A",true,Finding.Severity.LOW,false,false),fact("B",true,Finding.Severity.LOW,true,false)),true));
        assertFalse(PipelineDecisionEvaluator.hasRiskEvidence(release(risk),context("HIGH",fact("A",false,Finding.Severity.LOW,false,false),fact("B",true,Finding.Severity.LOW,true,false)),true));
    }
    @Test void supportedHighRuleCannotJustifyAnUnsupportedCriticalRule(){
        var risk=policy(Finding.Severity.LOW,rule("high",Finding.Severity.HIGH,eq("B",true)),rule("critical",Finding.Severity.CRITICAL,eq("A",true)));
        var input=context("CRITICAL",fact("A",true,Finding.Severity.LOW,false,false),fact("B",true,Finding.Severity.LOW,true,false));
        assertEquals("CRITICAL",risk.evaluate(input).level());assertFalse(PipelineDecisionEvaluator.hasRiskEvidence(release(risk),input,true));
    }
    @Test void historicalCriticalDefaultCannotBorrowLowerRuleEvidence(){
        var risk=policy(Finding.Severity.CRITICAL,rule("high",Finding.Severity.HIGH,eq("B",true)));
        var input=context("CRITICAL",fact("B",true,Finding.Severity.LOW,true,false));
        assertEquals("CRITICAL",risk.evaluate(input).level());assertFalse(PipelineDecisionEvaluator.hasRiskEvidence(release(risk),input,true));
    }
    @Test void negatedEqualityNeedsItsOwnKnownSelectedEvidence(){
        var risk=policy(Finding.Severity.LOW,rule("not",Finding.Severity.HIGH,group(ActionPolicy.Kind.NOT,eq("A",true))));
        assertTrue(PipelineDecisionEvaluator.hasRiskEvidence(release(risk),context("HIGH",fact("A",false,Finding.Severity.LOW,true,false)),true));
        assertFalse(PipelineDecisionEvaluator.hasRiskEvidence(release(risk),context("HIGH",fact("A",false,Finding.Severity.LOW,false,true)),true));
    }
    @Test void legacyFallbackRequiresFailedMappingAtTheActualHighestSeverity(){
        var release=release(null);var a=fact("A",true,Finding.Severity.HIGH,false,false);var b=fact("B",true,Finding.Severity.LOW,true,false);
        assertFalse(PipelineDecisionEvaluator.hasRiskEvidence(release,context("HIGH",a,b),true));
        assertTrue(PipelineDecisionEvaluator.hasRiskEvidence(release,context("HIGH",fact("A",true,Finding.Severity.HIGH,true,false),b),true));
        var pass=new ActionPolicy.Context(Map.of("A",fact("A",true,Finding.Severity.HIGH,true,false)),Map.of("itemA",InspectionScoring.ItemStatus.PASS),InspectionScoring.ScoreStatus.FINAL,java.math.BigDecimal.TEN,"HIGH","QUALIFIED");
        assertFalse(PipelineDecisionEvaluator.hasRiskEvidence(release,pass,true));
    }
    @Test void reviewingAnotherCodeCannotAdoptRejectedEvidenceForUnreviewedRiskFact(){
        var release=release(policy(Finding.Severity.LOW,rule("a",Finding.Severity.HIGH,eq("A",true))));
        var facts=List.of(fact("A",true,Finding.Severity.LOW,false,true),fact("B",true,Finding.Severity.LOW,true,false));
        var items=PipelineDecisionEvaluator.decisions(release.items(),facts);var score=InspectionScoring.evaluate(release.scoring(),items);
        assertThrows(IllegalArgumentException.class,()->PipelineDecisionEvaluator.requireReviewedRiskEvidence(release,facts,items,score,null,Set.of("B")));
        assertDoesNotThrow(()->PipelineDecisionEvaluator.requireReviewedRiskEvidence(release,facts,items,score,null,Set.of("A")));
        assertEquals(List.of("winner-A"),facts.getFirst().selectedIds());assertEquals(List.of("loser-A"),facts.getFirst().rejectedIds());
    }
}
