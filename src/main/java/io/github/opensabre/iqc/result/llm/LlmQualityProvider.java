package io.github.opensabre.iqc.result.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** LLM 规则的适配器边界，正式模型必须返回可校验的结构化结果。 */
public interface LlmQualityProvider {
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

    record LlmEvaluation(boolean supported, boolean hit, String reason) {
    }
}
