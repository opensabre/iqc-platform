package io.github.opensabre.iqc.result.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.governance.ratelimit.GovernanceRateLimiter;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationLlmProviderTest {
    @Test void candidateCallUsesExplicitContractAndKeepsIdentityWithoutChangingRule() {
        respond("{\"hit\":false,\"reason\":\"不确定\",\"schemaVersion\":\"iqc-item-candidates-v1\",\"outcome\":\"UNKNOWN\",\"quotes\":[]}");
        var rule = json.createObjectNode().put("id", "candidate");
        var result = provider.evaluateCandidates(List.of(message("m1", 1, "想贷款")), rule, null, "candidate-record");
        assertThat(result.supported()).isTrue();
        assertThat(result.structuredJson()).contains("UNKNOWN", "iqc-item-candidates-v1");
        var prompt = ArgumentCaptor.forClass(Prompt.class); verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getSystemMessage().getText()).contains("不判定最终违规", "不得用 hit 代替 outcome", "逐字引用");
        assertThat(prompt.getValue().getContents()).contains("conversationId", "m1", "customer", "想贷款");
        assertThat(rule.size()).isEqualTo(1);
        verify(usage, times(2)).record(any()); verify(limiter).check(any());
    }

    @Test void candidateInputAndLabelMixAreRejectedBeforeCallingModel() {
        var rule = json.createObjectNode().put("id", "candidate");
        assertThat(provider.evaluateCandidates(List.of(), rule, null, "record").supported()).isFalse();
        rule.putArray("labelFactTargets");
        assertThat(provider.evaluateCandidates(List.of(message("m1", 1, "内容")), rule, null, "record").supported()).isFalse();
        verifyNoInteractions(model, usage, limiter);
    }

    @Test void conversationReviewKeepsEvidenceIdentityAndSanitizesTextWithoutChangingCaller() throws Exception {
        respond("{\"hit\":false,\"reason\":\"引用排除\"}");
        var context = json.readTree("""
                [{"ruleId":"prefilter","status":"HIT","reason":"号码 13812345678",
                "evidence":[{"messageId":"m1","text":"联系 13812345678"}]}]
                """);
        var result = provider.evaluateConversation(List.of(message("m1", 1, "联系 13812345678")),
                json.createObjectNode().put("id", "review"), null, context, "review-record");
        assertThat(result.supported()).isTrue(); assertThat(result.hit()).isFalse();
        var prompt = ArgumentCaptor.forClass(Prompt.class); verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getContents()).contains("prefilter", "\"messageId\":\"m1\"", "[手机号]")
                .doesNotContain("13812345678");
        assertThat(prompt.getValue().getSystemMessage().getText()).contains("待复核候选", "不代表已确认违规");
        assertThat(context.toString()).contains("13812345678");
    }

    @Test void malformedReviewEvidenceIsRejectedBeforeModelAndUsage() {
        var result = provider.evaluateConversation(List.of(message("m1", 1, "内容")), json.createObjectNode(), null,
                json.createObjectNode().put("instruction", "discard-evidence"), "record");
        assertThat(result.supported()).isFalse(); assertThat(result.reason()).contains("禁止丢弃或截断");
        verifyNoInteractions(model, usage, limiter);
    }

    @Test void oldConversationAdapterMustExplicitlySupportReviewInsteadOfDroppingFindings() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        LlmQualityProvider legacy = new LlmQualityProvider() {
            @Override public LlmEvaluation evaluate(String content, com.fasterxml.jackson.databind.JsonNode rule, String recordId) {
                return new LlmEvaluation(true, false, "旧消息能力");
            }
            @Override public LlmEvaluation evaluateConversation(List<ConversationMessage> messages,
                    com.fasterxml.jackson.databind.JsonNode rule, com.fasterxml.jackson.databind.JsonNode agent, String recordId) {
                calls.incrementAndGet(); return new LlmEvaluation(true, false, "旧会话能力");
            }
        };
        var findings = json.createArrayNode().addObject().put("status", "HIT");
        assertThat(legacy.evaluateConversation(List.of(message("m1", 1, "内容")), json.createObjectNode(), null,
                findings, "record").supported()).isFalse();
        assertThat(calls.get()).isZero();
        assertThat(legacy.evaluateConversation(List.of(message("m1", 1, "内容")), json.createObjectNode(), null,
                null, "record").supported()).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }
    private final ObjectMapper json = new ObjectMapper();
    private final ChatModel model = mock(ChatModel.class);
    private final UsageCounterRecorder usage = mock(UsageCounterRecorder.class);
    private final GovernanceRateLimiter limiter = mock(GovernanceRateLimiter.class);
    private final SpringAiLlmQualityProvider provider = provider();

    @Test void preservesMessageIdentityAndSequenceWhileSanitizingOnlyContent() {
        respond("{\"hit\":false,\"reason\":\"未违规\"}");
        var result = provider.evaluateConversation(List.of(message("m2", 2, "我没有房子，联系 13812345678"),
                message("m1", 1, "请问有房吗？")), json.createObjectNode().put("id", "r1"), null, "conversation-1");
        assertThat(result.supported()).isTrue(); assertThat(result.hit()).isFalse();
        var prompt = ArgumentCaptor.forClass(Prompt.class); verify(model).call(prompt.capture());
        var content = prompt.getValue().getContents();
        assertThat(content).contains("待质检会话", "messageId", "m1", "m2", "customer", "[手机号]").doesNotContain("13812345678");
        assertThat(content.indexOf("\"messageId\":\"m1\"")).isLessThan(content.indexOf("\"messageId\":\"m2\""));
        assertThat(prompt.getValue().getSystemMessage().getText()).contains("保留 false", "相互矛盾", "不可信数据");
        verify(usage, times(2)).record(any()); verify(limiter).check(any());
    }

    @Test void preservesFalseFactsWithoutConflatingThemWithRuleHit() {
        respond("""
                {"hit":false,"reason":"未违规，明确否认","schemaVersion":"iqc-label-facts-v2","facts":[
                {"labelId":"house","valueCode":"owns","ruleId":"r1","subjectKind":"CURRENT_PARTICIPANT",
                "subjectRole":"customer","value":false,"evidence":[{"messageId":"m1","text":"没有房子"}]}]}
                """);
        var rule = json.createObjectNode().put("id", "r1"); rule.putArray("labelFactTargets").addObject().put("labelId", "house");
        var result = provider.evaluateConversation(List.of(message("m1", 1, "没有房子")), rule, null, "record");
        assertThat(result.supported()).isTrue(); assertThat(result.hit()).isFalse();
        assertThat(result.structuredJson()).contains("\"value\":false", "iqc-label-facts-v2");
    }

    @Test void userTargetPromptRequiresExactFrozenSubjectRole() {
        respond("""
                {"hit":false,"reason":"客户明确否认","schemaVersion":"iqc-label-facts-v2","facts":[
                {"labelId":"house","valueCode":"owns","ruleId":"r1","subjectKind":"CURRENT_PARTICIPANT",
                "subjectRole":"user","value":false,"evidence":[{"messageId":"m1","text":"没有房子"}]}]}
                """);
        var rule = json.createObjectNode().put("id", "r1");
        rule.putArray("labelFactTargets").addObject().put("labelId", "house").put("valueCode", "owns").put("subjectRole", "user");
        var customer = message("m1", 1, "我没有房子"); customer.setSpeakerRole("user");

        var result = provider.evaluateConversation(List.of(customer), rule, null, "record-user");

        assertThat(result.supported()).isTrue();
        var prompt = ArgumentCaptor.forClass(Prompt.class); verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getSystemMessage().getText()).contains("labelFactTargets", "完全一致", "user、customer 或 agent");
        assertThat(prompt.getValue().getContents()).contains("\"speakerRole\":\"user\"");
    }

    @Test void legacyOutputCannotPretendToHaveEvaluatedRequestedFacts() {
        respond("{\"hit\":false,\"reason\":\"无命中\"}");
        var rule = json.createObjectNode().put("id", "r1"); rule.putArray("labelFactTargets").addObject().put("labelId", "house");
        var result = provider.evaluateConversation(List.of(message("m1", 1, "你好")), rule, null, "record");
        assertThat(result.supported()).isFalse(); assertThat(result.reason()).contains("事实协议");
    }

    @Test void rejectsForeignConversationsAndDuplicateMessagesBeforeModelUse() {
        var first = message("m1", 1, "你好"); var foreign = message("m2", 2, "你好"); foreign.setConversationId("other");
        for (var input : List.of(List.of(first, foreign), List.of(first, first), List.of(first, message("m2", 1, "你好")))) {
            assertThat(provider.evaluateConversation(input, json.createObjectNode(), null, "record").supported()).isFalse();
        }
        verifyNoInteractions(model, usage, limiter);
    }

    @Test void rejectsOversizedConversationRatherThanDroppingTailMessages() {
        var result = provider.evaluateConversation(List.of(message("m1", 1, "长".repeat(100_001))), json.createObjectNode(), null, "record");
        assertThat(result.supported()).isFalse(); assertThat(result.reason()).contains("禁止截断");
        verifyNoInteractions(model, usage, limiter);
    }

    @Test void unsupportedAdaptersNeverCallSingleMessageFallback() {
        LlmQualityProvider adapter = (content, rule, id) -> { throw new AssertionError("must not fall back"); };
        assertThat(adapter.evaluateConversation(List.of(message("m1", 1, "你好")), json.createObjectNode(), null, "record").supported()).isFalse();
    }

    private SpringAiLlmQualityProvider provider() {
        var properties = new LlmQualityProperties(); properties.setMaxAttempts(1); properties.setModel("test-model");
        return new SpringAiLlmQualityProvider(model, null, json, limiter, usage, properties);
    }
    private void respond(String response) { when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(response))))); }
    private ConversationMessage message(String id, int sequence, String content) {
        var message = new ConversationMessage(); message.setId(id); message.setSequenceNo(sequence); message.setConversationId("c1");
        message.setSpeakerRole("customer"); message.setContent(content); return message;
    }
}
