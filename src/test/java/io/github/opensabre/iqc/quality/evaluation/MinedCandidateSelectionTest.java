package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.*;
import io.github.opensabre.iqc.action.*;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.fusion.FindingFusion;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Mining uncertainty selects unlabelled work, never a false-positive/negative judgement. */
class MinedCandidateSelectionTest {
    private final ObjectMapper json=new ObjectMapper();
    private JsonNode config(String threshold) {return json.createObjectNode().put("threshold",threshold).set("findingCodes",json.valueToTree(List.of("FACT")));}
    private ActionPolicy.Context context(FindingFusion.Result result) {
        return new ActionPolicy.Context(result==null?Map.of():Map.of("FACT",result),Map.of(),InspectionScoring.ScoreStatus.FINAL,null,"NONE",null);
    }
    private FindingFusion.Result fact(Finding.State state,String confidence,List<Finding> raw) {
        return new FindingFusion.Result("FACT",Finding.ValueType.BOOLEAN,state,state==Finding.State.KNOWN?json.valueToTree(true):null,
                Finding.Severity.NONE,raw,List.of(),List.of(),List.of(),"fixture","fusion",1,confidence==null?null:new BigDecimal(confidence));
    }
    private Finding raw(String id,Finding.ValueType type,String body) throws Exception {
        return new Finding(id,"FACT","test",type,json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(body),Finding.State.KNOWN,
                BigDecimal.ONE,Finding.Severity.NONE,null,List.of(),new Finding.Source(id,"LLM","1",1,"input"));
    }
    @Test void comparesExactThresholdWithoutRoundingAndKeepsEqualityOut() {
        var cfg=config("0.900000000000000001");
        assertThat(MinedCandidateSelection.reasons(context(fact(Finding.State.KNOWN,"0.900000000000000000",List.of())),cfg)).containsExactly("LOW_CONFIDENCE");
        for(String confidence:List.of("0.900000000000000001","0.900000000000000002"))
            assertThat(MinedCandidateSelection.reasons(context(fact(Finding.State.KNOWN,confidence,List.of())),cfg)).isEmpty();
        assertThat(MinedCandidateSelection.reasons(context(fact(Finding.State.KNOWN,"0",List.of())),config("0"))).isEmpty();
    }
    @Test void preservesMissingUnknownConflictAndUnmeasuredReasons() {
        assertThat(MinedCandidateSelection.reasons(context(null),config("0.5"))).containsExactly("MISSING_FINDING");
        for(var state:List.of(Finding.State.UNKNOWN,Finding.State.CONFLICT,Finding.State.ERROR,Finding.State.NOT_EVALUATED))
            assertThat(MinedCandidateSelection.reasons(context(fact(state,null,List.of())),config("0.5"))).containsExactly("FINDING_"+state);
        assertThat(MinedCandidateSelection.reasons(context(fact(Finding.State.KNOWN,null,List.of())),config("0.5"))).containsExactly("UNMEASURED_CONFIDENCE");
    }
    @Test void detectsExactNumericAndObjectDisagreementWithoutUsingConfidenceAsCorrectness() throws Exception {
        for(var pair:List.of(List.of(raw("a",Finding.ValueType.NUMBER,"0.900000000000000001"),raw("b",Finding.ValueType.NUMBER,"0.900000000000000002")),
                List.of(raw("a",Finding.ValueType.OBJECT,"{\"value\":9007199254740992}"),raw("b",Finding.ValueType.OBJECT,"{\"value\":9007199254740993}")),
                List.of(raw("a",Finding.ValueType.BOOLEAN,"true"),raw("b",Finding.ValueType.STRING,"\"true\""))))
            assertThat(MinedCandidateSelection.reasons(context(fact(Finding.State.KNOWN,"1",pair)),config("0.5"))).containsExactly("OBSERVATION_DISAGREEMENT");
        var equivalent=List.of(raw("a",Finding.ValueType.NUMBER,"1.0"),raw("b",Finding.ValueType.NUMBER,"1.00"));
        assertThat(MinedCandidateSelection.reasons(context(fact(Finding.State.KNOWN,"1",equivalent)),config("0.5"))).isEmpty();
    }
    @Test void reasonsAreSortedDeduplicatedAndInputRemainsUnchanged() throws Exception {
        var cfg=json.readTree("{\"threshold\":\"0.5\",\"findingCodes\":[\"FACT\",\"ABSENT\",\"OTHER\"]}");
        var value=fact(Finding.State.KNOWN,"0.1",List.of(raw("a",Finding.ValueType.BOOLEAN,"true"),raw("b",Finding.ValueType.BOOLEAN,"false")));
        String before=json.writeValueAsString(value);
        assertThat(MinedCandidateSelection.reasons(context(value),cfg)).containsExactly("LOW_CONFIDENCE","MISSING_FINDING","OBSERVATION_DISAGREEMENT");
        assertThat(json.writeValueAsString(value)).isEqualTo(before);
    }
    @Test void rejectsAmbiguousConfigurationAndMachineGroundTruthFields() throws Exception {
        for(String invalid:List.of("{\"threshold\":0.5,\"findingCodes\":[\"FACT\"]}","{\"threshold\":\"0.5\",\"findingCodes\":[\"FACT\",\"FACT\"]}",
                "{\"threshold\":\"0.5\",\"findingCodes\":[\"FACT\"],\"expected\":true}","{\"threshold\":\"0.5\",\"findingCodes\":[]}")) {
            var cfg=json.readTree(invalid);assertThatThrownBy(()->MinedCandidateSelection.validateConfiguration(cfg)).isInstanceOf(IllegalArgumentException.class);
        }
        for(String invalid:List.of("-0.1","1.1","1e-1","NaN","0.1234567890123456789"))
            assertThatThrownBy(()->MinedCandidateSelection.validateConfiguration(config(invalid))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void adapterAcceptsCommittedRunningSourcesAndRejectsNoMatchOrPausedStatuses() {
        var service=mock(MinedEvaluationCandidateService.class);var adapter=new MinedEvaluationCandidateActionAdapter(service,json);
        var action=new ActionPolicy.Action("mine",ActionExecutionContract.Type.ADD_TO_EVALUATION_CANDIDATE,
                new ActionPolicy.Condition(ActionPolicy.Kind.ALWAYS,null,null,List.of()),ActionPolicy.Idempotency.PRODUCTION_SOURCE_V1,null,
                ActionPolicy.FailureHandling.RECORD_ONLY,config("0.5"),2);
        var ref=new ActionProductionSourceValidator.Reference("task","execution","conversation","result","owner");
        for(String status:List.of("RUNNING","SUCCEEDED","PARTIAL_FAILED","FAILED","PAUSED","CANCELLED")) {
            var source=new ActionProductionSourceValidator.Validated(ref,"group","result","{}","hash",null,context(null),null,status);
            assertThat(adapter.ready(action,source)).isEqualTo(Set.of("RUNNING","SUCCEEDED","PARTIAL_FAILED","FAILED").contains(status));
        }
        var noMatch=new ActionProductionSourceValidator.Validated(ref,"group","result","{}","hash",null,context(fact(Finding.State.KNOWN,"1",List.of())),null,"RUNNING");
        assertThat(adapter.ready(action,noMatch)).isFalse();verifyNoInteractions(service);
    }
}
