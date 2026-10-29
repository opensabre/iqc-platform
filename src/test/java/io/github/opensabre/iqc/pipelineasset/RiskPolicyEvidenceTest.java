package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.opensabre.iqc.action.ActionPolicy;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.fusion.FindingFusion;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Evidence gates cover matching branches and selected sources without rewriting historical policies. */
class RiskPolicyEvidenceTest {
    private static final JsonNodeFactory J=JsonNodeFactory.instance;
    private ActionPolicy.Condition known(String code) {
        return new ActionPolicy.Condition(ActionPolicy.Kind.FINDING_STATE,code,J.textNode("KNOWN"),List.of());
    }
    private ActionPolicy.Condition equalsTrue(String code) {
        return new ActionPolicy.Condition(ActionPolicy.Kind.FINDING_EQUALS,code,J.booleanNode(true),List.of());
    }
    private ActionPolicy.Condition group(ActionPolicy.Kind kind,ActionPolicy.Condition... children) {
        return new ActionPolicy.Condition(kind,null,null,List.of(children));
    }
    private RiskPolicy policy(Finding.Severity defaultLevel,ActionPolicy.Condition condition) {
        return new RiskPolicy(RiskPolicy.SCHEMA,defaultLevel,List.of(new RiskPolicy.Rule("risk",Finding.Severity.HIGH,condition)));
    }
    @Test void unconditionalHighRiskIsRejectedForNewUseButHistoricalPolicyRemainsReadable() {
        var always=group(ActionPolicy.Kind.ALWAYS);
        var historical=policy(Finding.Severity.HIGH,always);
        assertThrows(IllegalArgumentException.class,()->historical.validateHighRiskEvidence(Set.of()));
        assertEquals("HIGH",historical.evaluate(new ActionPolicy.Context(Map.of(),Map.of(),io.github.opensabre.iqc.scoring.InspectionScoring.ScoreStatus.PENDING,null,null,null)).level());
        assertThrows(IllegalArgumentException.class,()->policy(Finding.Severity.LOW,always).validateHighRiskEvidence(Set.of()));
    }
    @Test void everyAlternativeMustCarryAnEvidenceGate() {
        var safe=equalsTrue("consent");var unsupported=equalsTrue("sentiment");
        assertThrows(IllegalArgumentException.class,()->policy(Finding.Severity.LOW,group(ActionPolicy.Kind.ANY,safe,unsupported)).validateHighRiskEvidence(Set.of("consent")));
        assertDoesNotThrow(()->policy(Finding.Severity.LOW,group(ActionPolicy.Kind.ALL,safe,unsupported)).validateHighRiskEvidence(Set.of("consent")));
        assertDoesNotThrow(()->policy(Finding.Severity.LOW,group(ActionPolicy.Kind.ANY,safe,known("consent"))).validateHighRiskEvidence(Set.of("consent")));
    }
    @Test void unknownAndNegatedKnownCannotStandInForPositiveKnownEvidence() {
        var unknown=new ActionPolicy.Condition(ActionPolicy.Kind.FINDING_STATE,"consent",J.textNode("UNKNOWN"),List.of());
        assertThrows(IllegalArgumentException.class,()->policy(Finding.Severity.LOW,unknown).validateHighRiskEvidence(Set.of("consent")));
        assertThrows(IllegalArgumentException.class,()->policy(Finding.Severity.LOW,group(ActionPolicy.Kind.NOT,known("consent"))).validateHighRiskEvidence(Set.of("consent")));
        assertDoesNotThrow(()->policy(Finding.Severity.LOW,group(ActionPolicy.Kind.NOT,equalsTrue("consent"))).validateHighRiskEvidence(Set.of("consent")));
    }
    @Test void nestedUnsafeAlternativeCannotBorrowEvidenceFromAnotherPath() {
        var nested=group(ActionPolicy.Kind.ANY,group(ActionPolicy.Kind.ALL,known("consent"),equalsTrue("sentiment")),equalsTrue("sentiment"));
        assertThrows(IllegalArgumentException.class,()->policy(Finding.Severity.LOW,nested).validateHighRiskEvidence(Set.of("consent")));
    }
    private Finding raw(String node,boolean evidence) {
        return new Finding(node,"consent","compliance",Finding.ValueType.BOOLEAN,J.booleanNode(true),Finding.State.KNOWN,null,
                Finding.Severity.LOW,"observed",evidence?List.of(new Finding.Evidence("quote",Finding.EvidenceType.CONVERSATION,"message-1",null)):List.of(),
                new Finding.Source(node,"RULE","1",1,"input"));
    }
    @Test void rejectedSourceEvidenceCannotJustifySelectedMachineConclusion() {
        var fused=FindingFusion.fuse(List.of(raw("winner",false),raw("loser",true)),
                new FusionPolicy("priority",1,FusionPolicy.Method.PRIORITY,Map.of("winner",2,"loser",1),Map.of(),null,Set.of()));
        assertTrue(RiskPolicy.hasLocatableEvidence(fused));assertFalse(RiskPolicy.hasSelectedEvidence(fused));
        assertThrows(IllegalArgumentException.class,()->RiskPolicy.requireSelectedEvidence("HIGH",fused));
        assertDoesNotThrow(()->RiskPolicy.requireLocatableEvidence("HIGH",fused));
        assertDoesNotThrow(()->RiskPolicy.requireSelectedEvidence("LOW",fused));
    }
    @Test void selectedSourceEvidenceSupportsHighRiskWithoutChangingRawFindings() {
        var raw=List.of(raw("winner",true),raw("loser",false));
        var fused=FindingFusion.fuse(raw,new FusionPolicy("priority",1,FusionPolicy.Method.PRIORITY,Map.of("winner",2,"loser",1),Map.of(),null,Set.of()));
        assertTrue(RiskPolicy.hasSelectedEvidence(fused));assertDoesNotThrow(()->RiskPolicy.requireSelectedEvidence("CRITICAL",fused));
        assertEquals(raw,fused.getFirst().rawFindings());assertEquals(List.of("winner"),fused.getFirst().selectedIds());
        assertEquals(List.of("loser"),fused.getFirst().rejectedIds());
    }
}
