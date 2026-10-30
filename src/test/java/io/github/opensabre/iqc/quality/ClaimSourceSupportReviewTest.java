package io.github.opensabre.iqc.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.finding.FindingDefinition;
import io.github.opensabre.iqc.fusion.FindingFusion;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.*;
import static org.assertj.core.api.Assertions.*;

/** Source assessments remain bound to frozen observations without rewriting machine provenance. */
class ClaimSourceSupportReviewTest {
    private final ObjectMapper json = new ObjectMapper();

    private Finding raw(String id, String engine, Finding.State state, String evidenceId) {
        return new Finding(id,"CHECK","BUSINESS",Finding.ValueType.BOOLEAN,
                state==Finding.State.KNOWN?json.getNodeFactory().booleanNode(false):null,state,null,
                Finding.Severity.LOW,"machine",List.of(new Finding.Evidence(evidenceId,
                Finding.EvidenceType.CONVERSATION,"m1",null)),new Finding.Source("node",engine,"v1",1,"input"));
    }
    private FindingFusion.Result fact(Finding... observations) {
        return new FindingFusion.Result("CHECK",Finding.ValueType.BOOLEAN,Finding.State.KNOWN,
                json.getNodeFactory().booleanNode(false),Finding.Severity.LOW,List.of(observations),
                List.of(observations[0].id()),List.of(),Arrays.stream(observations)
                .flatMap(raw->raw.evidence().stream()).distinct().toList(),"machine","policy",1);
    }
    private ClaimSourceSupport assessment(String rawId,SourceSupport verdict,List<String> evidence,List<String> messages) {
        return new ClaimSourceSupport(rawId,verdict,evidence,messages," human assessment ");
    }
    private Decision decision(FindingFusion.Result fact,List<ClaimSourceSupport> assessments) {
        return new Decision("result","CHECK",findingHash(fact,json),Finding.State.KNOWN,
                json.getNodeFactory().booleanNode(true),Finding.Severity.LOW,"correction",List.of(),List.of(),null,assessments);
    }
    private Projection evaluate(FindingFusion.Result fact,Decision decision) {
        var definition=new FindingDefinition(FindingDefinition.SCHEMA,"CHECK","Check","BUSINESS",null,
                Finding.ValueType.BOOLEAN,List.of(),Finding.Severity.LOW,false,List.of(Finding.EvidenceType.CONVERSATION));
        var messages=json.createArrayNode().add(json.createObjectNode().put("id","m1")
                .put("conversationId","c1").put("sequenceNo",0));
        return HumanFindingReviewEvaluator.evaluate("result",List.of(fact),Map.of("CHECK",definition),
                List.of(decision),messages,json);
    }

    @Test void historicalJsonRemainsByteIdenticalWithAbsentNullOrEmptyAssessments() throws Exception {
        // Literal historical wire bytes are independent of the new record serializer.
        String legacy="{\"sourceResultId\":\"result\",\"findingCode\":\"CHECK\",\"expectedFindingHash\":\"hash\","
                +"\"state\":\"KNOWN\",\"value\":true,\"severity\":\"LOW\",\"reason\":\"correction\","
                +"\"retainedEvidenceIds\":[],\"addedMessageIds\":[]}";
        for(String suffix:List.of("",",\"sourceSupport\":null",",\"sourceSupport\":[]")) {
            var restored=json.readValue(legacy.substring(0,legacy.length()-1)+suffix+"}",Decision.class);
            assertThat(json.writeValueAsString(restored)).isEqualTo(legacy);
            assertThat(SelectorAssetService.hash(json.writeValueAsString(List.of(restored))))
                    .isEqualTo(SelectorAssetService.hash("["+legacy+"]"));
        }
    }

    @Test void allVerdictsRoundTripAndReplayWithoutChangingMachineFacts() throws Exception {
        var original=fact(raw("llm","LLM",Finding.State.KNOWN,"e1"));
        String machine=json.writeValueAsString(original);
        for(var verdict:SourceSupport.values()) {
            var claim=assessment("llm",verdict,verdict==SourceSupport.INSUFFICIENT_SOURCE?List.of():List.of("e1"),List.of());
            var first=evaluate(original,decision(original,List.of(claim)));
            var restored=json.readValue(json.writeValueAsString(first.decisions().getFirst()),Decision.class);
            var replay=evaluate(original,restored);
            assertThat(json.writeValueAsString(replay)).isEqualTo(json.writeValueAsString(first));
            assertThat(json.writeValueAsString(first.original().getFirst())).isEqualTo(machine);
            assertThat(first.reviewed().getFirst().rawFindings()).isEqualTo(original.rawFindings());
            assertThat(first.decisions().getFirst().sourceSupport().getFirst().reason()).isEqualTo("human assessment");
        }
    }

    @Test void annotationsParticipateInSerializedFingerprint() throws Exception {
        var original=fact(raw("llm","LLM",Finding.State.KNOWN,"e1"));
        var supported=decision(original,List.of(assessment("llm",SourceSupport.SUPPORTED,List.of("e1"),List.of())));
        var contradicted=decision(original,List.of(assessment("llm",SourceSupport.CONTRADICTED,List.of("e1"),List.of())));
        assertThat(SelectorAssetService.hash(json.writeValueAsString(List.of(supported))))
                .isNotEqualTo(SelectorAssetService.hash(json.writeValueAsString(List.of(contradicted))))
                .isNotEqualTo(SelectorAssetService.hash(json.writeValueAsString(List.of(decision(original,List.of())))));
    }

    @Test void frozenMessageIsAllowedButOtherObservationsEvidenceAndForeignMessagesAreRejected() {
        var original=fact(raw("llm","LLM",Finding.State.KNOWN,"e1"),raw("other","LLM",Finding.State.KNOWN,"e2"));
        assertThat(evaluate(original,decision(original,List.of(assessment("llm",SourceSupport.SUPPORTED,List.of(),List.of("m1"))))).decisions()).hasSize(1);
        for(var invalid:List.of(assessment("llm",SourceSupport.SUPPORTED,List.of("e2"),List.of()),
                assessment("llm",SourceSupport.CONTRADICTED,List.of(),List.of("foreign")),
                assessment("missing",SourceSupport.INSUFFICIENT_SOURCE,List.of(),List.of())))
            assertThatThrownBy(()->evaluate(original,decision(original,List.of(invalid)))).hasMessageContaining("来源支持标注");
    }

    @Test void nonModelIncompleteAndAmbiguousRawIdentitiesAreRejected() {
        var claim=assessment("target",SourceSupport.SUPPORTED,List.of("e1"),List.of());
        for(var state:Finding.State.values()) {
            if(state==Finding.State.KNOWN)continue;
            var original=fact(raw("target","LLM",state,"e1"));
            assertThatThrownBy(()->evaluate(original,decision(original,List.of(claim)))).hasMessageContaining("唯一已知LLM");
        }
        for(var original:List.of(fact(raw("target","RULE",Finding.State.KNOWN,"e1")),
                fact(raw("target","LLM",Finding.State.KNOWN,"e1"),raw("target","LLM",Finding.State.KNOWN,"e2"))))
            assertThatThrownBy(()->evaluate(original,decision(original,List.of(claim)))).hasMessageContaining("唯一已知LLM");
    }

    @Test void assessmentsCannotBypassFrozenResultAndFactHash() {
        var original=fact(raw("llm","LLM",Finding.State.KNOWN,"e1"));
        var claims=List.of(assessment("llm",SourceSupport.SUPPORTED,List.of("e1"),List.of()));
        for(var invalid:List.of(new Decision("foreign","CHECK",findingHash(original,json),Finding.State.KNOWN,
                json.getNodeFactory().booleanNode(true),Finding.Severity.LOW,"why",List.of(),List.of(),null,claims),
                new Decision("result","CHECK","stale",Finding.State.KNOWN,json.getNodeFactory().booleanNode(true),
                        Finding.Severity.LOW,"why",List.of(),List.of(),null,claims)))
            assertThatThrownBy(()->evaluate(original,invalid)).isInstanceOf(RuntimeException.class);
    }

    @Test void requiredSourcesReasonsBoundsAndDuplicateIdentitiesAreEnforced() {
        for(var verdict:List.of(SourceSupport.SUPPORTED,SourceSupport.CONTRADICTED))
            assertThatThrownBy(()->assessment("llm",verdict,List.of(),List.of())).isInstanceOf(IllegalArgumentException.class);
        for(var reason:List.of(" ","x".repeat(1001)))
            assertThatThrownBy(()->new ClaimSourceSupport("llm",SourceSupport.INSUFFICIENT_SOURCE,List.of(),List.of(),reason))
                    .isInstanceOf(IllegalArgumentException.class);
        for(var ids:List.of(List.of("e1","e1"),List.of(" "),List.of("x".repeat(257)),
                java.util.stream.IntStream.range(0,101).mapToObj(i->"e"+i).toList()))
            assertThatThrownBy(()->assessment("llm",SourceSupport.SUPPORTED,ids,List.of())).isInstanceOf(IllegalArgumentException.class);
        for(var ids:List.of(List.of("m1","m1"),List.of(" "),List.of("x".repeat(65))))
            assertThatThrownBy(()->assessment("llm",SourceSupport.SUPPORTED,List.of(),ids)).isInstanceOf(IllegalArgumentException.class);
        var original=fact(raw("llm","LLM",Finding.State.KNOWN,"e1"));
        var claim=assessment("llm",SourceSupport.INSUFFICIENT_SOURCE,List.of(),List.of());
        assertThatThrownBy(()->decision(original,List.of(claim,claim))).isInstanceOf(IllegalArgumentException.class);
        var tooMany=java.util.stream.IntStream.range(0,1001).mapToObj(i->assessment("r"+i,
                SourceSupport.INSUFFICIENT_SOURCE,List.of(),List.of())).toList();
        assertThatThrownBy(()->decision(original,tooMany)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sourceCollectionsAreDefensivelyCopiedAndReadOnly() {
        var evidence=new ArrayList<>(List.of("e1"));var messages=new ArrayList<>(List.of("m1"));
        var claim=assessment("llm",SourceSupport.SUPPORTED,evidence,messages);
        var assessments=new ArrayList<>(List.of(claim));
        var original=fact(raw("llm","LLM",Finding.State.KNOWN,"e1"));var decision=decision(original,assessments);
        evidence.clear();messages.clear();assessments.clear();
        assertThat(decision.sourceSupport()).containsExactly(claim);
        assertThat(claim.evidenceIds()).containsExactly("e1");assertThat(claim.messageIds()).containsExactly("m1");
        assertThatThrownBy(()->decision.sourceSupport().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->claim.evidenceIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->claim.messageIds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
