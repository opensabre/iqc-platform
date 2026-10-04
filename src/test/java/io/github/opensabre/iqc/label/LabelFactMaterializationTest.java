package io.github.opensabre.iqc.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper;
import io.github.opensabre.iqc.label.model.InspectionLabelResult;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.result.model.RuleInspectionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LabelFactMaterializationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final InspectionLabelResultMapper mapper = mock(InspectionLabelResultMapper.class);
    private final LabelResultService service = new LabelResultService(mapper, json);
    private final String snapshot = """
            {"schemaVersion":"2.0","labels":[{"id":"house","versionNo":1,"targetRole":"customer",
            "bindings":[{"ruleId":"r1","ruleVersionNo":1}],
            "values":[{"valueCode":"owns","valueType":"BOOLEAN","configJson":"{\\"defaultValue\\":true}"}]}]}
            """;

    @Test void explicitFalseRemainsKnownWithEvidence() throws Exception {
        var result = project(source(fact(false)));
        assertThat(result.path("status").asText()).isEqualTo("KNOWN");
        assertThat(result.path("value").isBoolean()).isTrue(); assertThat(result.path("value").booleanValue()).isFalse();
        assertThat(result.path("candidates").get(0).path("sourceRuleResultId").asText()).isEqualTo("rr1");
    }
    @Test void existingUserRoleEvidenceMaterializesWithoutAliasRewrite() throws Exception {
        var userSnapshot = snapshot.replace("customer", "user");
        var userFact = fact(false);
        userFact.put("subjectRole", "user");
        var conversation = new ConversationInspectionResult(); conversation.setId("cr1"); conversation.setConversationId("c1");
        var userMessage = new ConversationMessage(); userMessage.setId("m1"); userMessage.setConversationId("c1");
        userMessage.setSpeakerRole("user"); userMessage.setContent("我没有房子");
        service.materialize(conversation, userSnapshot, List.of(source(userFact)), List.of(userMessage));
        var result = ArgumentCaptor.forClass(InspectionLabelResult.class); verify(mapper).insert(result.capture());
        assertThat(json.readTree(result.getValue().getValueJson()).path("status").asText()).isEqualTo("KNOWN");
    }
    @Test void contraryStatementsRemainConflict() throws Exception {
        var result = project(source(fact(true), fact(false)));
        assertThat(result.path("status").asText()).isEqualTo("CONFLICT");
        assertThat(result.has("value")).isFalse(); assertThat(result.path("candidates").size()).isEqualTo(2);
    }
    @Test void evaluatedEmptyFactsAreUnknownWithoutDefaultFallback() throws Exception {
        var result = project(source());
        assertThat(result.path("status").asText()).isEqualTo("UNKNOWN"); assertThat(result.has("value")).isFalse();
        assertThat(result.path("reasons").toString()).contains("NOT_MENTIONED");
    }
    @Test void noTargetSpeakerIsNotReportedAsNotMentioned() throws Exception {
        var result = project(source(), List.of(message("agent", "坐席：您好")));
        assertThat(result.path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(result.path("reasons").toString()).contains("NO_TARGET_SPEAKER").doesNotContain("NOT_MENTIONED");
    }
    @Test void unknownSpeakerKeepsCoverageUncertainWithoutAttributingFacts() throws Exception {
        var result = project(source(), List.of(message("系统", "系统：会话结束")));
        assertThat(result.path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(result.path("reasons").toString()).contains("UNMAPPED_SPEAKER_ROLE", "NO_TARGET_SPEAKER")
                .doesNotContain("NOT_MENTIONED");
    }
    @Test void knownTargetFactIsNotErasedByAnUnrelatedUnknownSpeaker() throws Exception {
        var target = message("customer", "我没有房子");
        var system = message("系统", "系统：会话结束"); system.setId("m2");
        var result = project(source(fact(false)), List.of(target, system));
        assertThat(result.path("status").asText()).isEqualTo("KNOWN");
        assertThat(result.path("value").booleanValue()).isFalse();
    }
    @Test void unmappedSpeakerPreventsNotMentionedEvenWhenTargetAlsoSpoke() throws Exception {
        var target = message("customer", "我考虑一下");
        var unknown = message("系统", "系统：会话结束"); unknown.setId("m2");
        var result = project(source(), List.of(target, unknown));
        assertThat(result.path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(result.path("reasons").toString()).contains("UNMAPPED_SPEAKER_ROLE").doesNotContain("NOT_MENTIONED", "NO_TARGET_SPEAKER");
    }
    @Test void missingProtocolIsErrorNotUnknown() throws Exception {
        var source = source(); source.setFindingJson("{}");
        assertThat(project(source).path("status").asText()).isEqualTo("ERROR");
    }
    @Test void blankFindingIsErrorInsteadOfThrowing() throws Exception {
        var source = source(); source.setFindingJson(" ");
        assertThat(project(source).path("status").asText()).isEqualTo("ERROR");
    }
    @Test void MalformedFactCannotBecomeNotMentioned() throws Exception {
        var result = project(source(json.createObjectNode()));
        assertThat(result.path("status").asText()).isEqualTo("ERROR");
        assertThat(result.path("reasons").toString()).contains("INVALID_FACT_CONTRACT");
    }
    @Test void thirdPartySubjectIsNotAssignedToCustomer() throws Exception {
        var fact = fact(true); fact.put("subjectKind", "THIRD_PARTY");
        assertThat(project(source(fact)).path("status").asText()).isEqualTo("UNKNOWN");
    }
    @Test void fabricatedAndCrossConversationQuotesAreErrors() throws Exception {
        var fact = fact(true); ((com.fasterxml.jackson.databind.node.ObjectNode) fact.path("evidence").get(0)).put("messageId", "foreign");
        assertThat(project(source(fact)).path("status").asText()).isEqualTo("ERROR");
    }
    @Test void nonTextualQuoteFieldsCannotBeCoercedIntoEvidence() throws Exception {
        var fact = fact(true);
        ((com.fasterxml.jackson.databind.node.ObjectNode) fact.path("evidence").get(0)).put("text", 123);
        var numericText = project(source(fact), List.of(message("customer", "我有房子123")));
        assertThat(numericText.path("status").asText()).isEqualTo("ERROR");
        assertThat(numericText.path("reasons").toString()).contains("INVALID_EVIDENCE");

        reset(mapper);
        var numericId = fact(true);
        ((com.fasterxml.jackson.databind.node.ObjectNode) numericId.path("evidence").get(0)).put("messageId", 123);
        var message = message("customer", "我有房子"); message.setId("123");
        assertThat(project(source(numericId), List.of(message)).path("status").asText()).isEqualTo("ERROR");
    }
    @Test void mismatchedRuleVersionFailsBeforeWriting() throws Exception {
        var source = source(fact(true)); source.setRuleVersionNo(2);
        assertThatThrownBy(() -> project(source)).hasMessageContaining("版本不一致"); verifyNoInteractions(mapper);
    }
    @Test void invalidFrozenSubjectCannotBecomeUnknownWhenFactsAreEmpty() {
        var conversation = new ConversationInspectionResult(); conversation.setId("cr1"); conversation.setConversationId("c1");
        var invalid = snapshot.replace("\"targetRole\":\"customer\"", "\"targetRole\":\"all\"");
        assertThatThrownBy(() -> service.materialize(conversation, invalid, List.of(source()),
                List.of(message("customer", "我考虑一下")))).hasMessageContaining("定义不完整");
        verifyNoInteractions(mapper);
    }
    @Test void unsupportedFrozenValueTypeCannotBecomeUnknownWhenFactsAreEmpty() {
        var conversation = new ConversationInspectionResult(); conversation.setId("cr1"); conversation.setConversationId("c1");
        var invalid = snapshot.replace("\"valueType\":\"BOOLEAN\"", "\"valueType\":\"UNSUPPORTED\"");
        assertThatThrownBy(() -> service.materialize(conversation, invalid, List.of(source()),
                List.of(message("customer", "我考虑一下")))).hasMessageContaining("类型无效");
        verifyNoInteractions(mapper);
    }
    @Test void nonTextualFrozenIdentifiersCannotBeCoercedIntoStoredLabels() {
        var conversation = new ConversationInspectionResult(); conversation.setId("cr1"); conversation.setConversationId("c1");
        var numericLabel = snapshot.replace("\"id\":\"house\"", "\"id\":123");
        var numericValue = snapshot.replace("\"valueCode\":\"owns\"", "\"valueCode\":123");
        assertThatThrownBy(() -> service.materialize(conversation, numericLabel, List.of(source()),
                List.of(message("customer", "我考虑一下")))).hasMessageContaining("定义不完整");
        assertThatThrownBy(() -> service.materialize(conversation, numericValue, List.of(source()),
                List.of(message("customer", "我考虑一下")))).hasMessageContaining("编码或类型无效");
        verifyNoInteractions(mapper);
    }
    @Test void detectionErrorCannotBeHiddenByAValidCandidate() throws Exception {
        var source = source(fact(true)); var envelope = json.readTree(source.getFindingJson());
        ((com.fasterxml.jackson.databind.node.ArrayNode) envelope.path("observations")).addObject().put("status", "ERROR");
        source.setFindingJson(envelope.toString());
        var result = project(source); assertThat(result.path("status").asText()).isEqualTo("ERROR");
        assertThat(result.path("candidates").size()).isEqualTo(1); assertThat(result.has("value")).isFalse();
    }
    @Test void locatedEvidenceFailureIsErrorEvenWhenTheRuleHit() throws Exception {
        var source = source();
        var envelope = json.readTree(source.getFindingJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) envelope.path("observations").get(0).path("finding"))
                .put("factError", "NO_LOCATED_EVIDENCE");
        source.setFindingJson(envelope.toString());
        var result = project(source);
        assertThat(result.path("status").asText()).isEqualTo("ERROR");
        assertThat(result.path("reasons").toString()).contains("NO_LOCATED_EVIDENCE");
    }
    private com.fasterxml.jackson.databind.node.ObjectNode fact(boolean value) {
        var fact = json.createObjectNode().put("labelId", "house").put("valueCode", "owns").put("ruleId", "r1")
                .put("subjectKind", "CURRENT_PARTICIPANT").put("subjectRole", "customer").put("value", value);
        fact.putArray("evidence").addObject().put("messageId", "m1").put("text", "房子"); return fact;
    }
    private RuleInspectionResult source(com.fasterxml.jackson.databind.JsonNode... facts) {
        var source = new RuleInspectionResult(); source.setId("rr1"); source.setConversationResultId("cr1");
        source.setRuleId("r1"); source.setRuleVersionNo(1);
        var envelope = json.createObjectNode().put("schemaVersion", "iqc-rule-observations-v2");
        var observation = envelope.putArray("observations").addObject().put("messageId", "m1").put("status", "HIT");
        var array = observation.putObject("finding").put("schemaVersion", "iqc-label-facts-v2").putArray("facts");
        for (var fact : facts) array.add(fact);
        source.setFindingJson(envelope.toString()); return source;
    }
    private com.fasterxml.jackson.databind.JsonNode project(RuleInspectionResult source) throws Exception {
        return project(source, List.of(message("customer", "我没有房子")));
    }
    private ConversationMessage message(String role, String content) {
        var message = new ConversationMessage(); message.setId("m1"); message.setConversationId("c1");
        message.setSpeakerRole(role); message.setContent(content); return message;
    }
    private com.fasterxml.jackson.databind.JsonNode project(RuleInspectionResult source, List<ConversationMessage> messages) throws Exception {
        var conversation = new ConversationInspectionResult(); conversation.setId("cr1"); conversation.setConversationId("c1");
        service.materialize(conversation, snapshot, List.of(source), messages);
        var result = ArgumentCaptor.forClass(InspectionLabelResult.class); verify(mapper).insert(result.capture());
        return json.readTree(result.getValue().getValueJson());
    }
}
