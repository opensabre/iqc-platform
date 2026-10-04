package io.github.opensabre.iqc.result.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** LLM 规则的适配器边界，正式模型必须返回可校验的结构化结果。 */
public interface LlmQualityProvider {
    /** Candidate extraction is explicit; legacy boolean judgments cannot stand in for complete extraction. */
    default LlmEvaluation evaluateCandidates(java.util.List<io.github.opensabre.iqc.conversation.model.ConversationMessage> messages,
                                           JsonNode rule, JsonNode agentSnapshot, String recordId) {
        return new LlmEvaluation(false, false, "当前模型适配器不支持逐项候选抽取");
    }
    /** Explicit review capability: an older adapter must not discard deterministic context silently. */
    default LlmEvaluation evaluateConversation(java.util.List<io.github.opensabre.iqc.conversation.model.ConversationMessage> messages,
                                              JsonNode rule, JsonNode agentSnapshot, JsonNode preRuleFindings, String recordId) {
        return preRuleFindings == null ? evaluateConversation(messages, rule, agentSnapshot, recordId)
                : new LlmEvaluation(false, false, "当前模型适配器不支持带初筛证据的会话级复核");
    }
    /** Conversation support is explicit: old adapters must not silently reinterpret it as a single message. */
    default LlmEvaluation evaluateConversation(java.util.List<io.github.opensabre.iqc.conversation.model.ConversationMessage> messages,
                                              JsonNode rule, JsonNode agentSnapshot, String recordId) {
        return new LlmEvaluation(false, false, "当前模型适配器不支持会话级质检");
    }

    default LlmEvaluation evaluate(String content, JsonNode rule) {
        return evaluate(content, rule, null);
    }

    /** Executes with the immutable Agent snapshot captured when the task was created. */
    default LlmEvaluation evaluate(String content, JsonNode rule, JsonNode agentSnapshot, String recordId) {
        return evaluate(content, rule, recordId);
    }

    /** Evaluates with deterministic pre-rule findings supplied by RULE_THEN_LLM mode. */
    default LlmEvaluation evaluate(String content, JsonNode rule, JsonNode agentSnapshot,
                                   JsonNode preRuleFindings, String recordId) {
        return evaluate(content, rule, agentSnapshot, recordId);
    }

    LlmEvaluation evaluate(String content, JsonNode rule, String recordId);

    record LlmEvaluation(boolean supported, boolean hit, String reason, String structuredJson) {
        public LlmEvaluation(boolean supported, boolean hit, String reason) {
            this(supported, hit, reason, null);
        }
    }
}
