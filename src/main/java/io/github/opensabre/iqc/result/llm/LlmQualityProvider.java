package io.github.opensabre.iqc.result.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** LLM 规则的适配器边界，正式模型必须返回可校验的结构化结果。 */
public interface LlmQualityProvider {
    /** Keep provider decimals before any intermediate tree serialization or histogram selection. */
    static JsonNode readStructuredJson(com.fasterxml.jackson.databind.ObjectMapper mapper,String text)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        return mapper.readerFor(JsonNode.class)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(text);
    }
    /** A declared corpus must never disappear silently while task-authorized context is not yet materialized. */
    static void requireKnowledgeContext(JsonNode config){
        if(config!=null&&config.hasNonNull("knowledgeAssets")&&(!config.path("knowledgeAssets").isArray()||!config.path("knowledgeAssets").isEmpty()))
            throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("KNOWLEDGE_CONTEXT_NOT_FROZEN");
    }
    static String knowledgeMaterial(JsonNode config,ModelCallContext context){
        if(config==null||!config.hasNonNull("knowledgeAssets")||(config.path("knowledgeAssets").isArray()&&config.path("knowledgeAssets").isEmpty()))return "";
        if(context==null||context.knowledgeContext()==null)
            throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("KNOWLEDGE_CONTEXT_NOT_FROZEN");
        return context.knowledgeContext().material(config,context.taskId(),context.owner(),context.ownerGroupId());
    }
    default LlmEvaluation evaluate(String content,JsonNode rule,JsonNode agentSnapshot,JsonNode preRuleFindings,String recordId,ModelCallContext context){
        return evaluate(content,rule,agentSnapshot,preRuleFindings,recordId);
    }
    default LlmEvaluation evaluateConversation(java.util.List<io.github.opensabre.iqc.conversation.model.ConversationMessage> messages,
            JsonNode rule,JsonNode agentSnapshot,JsonNode preRuleFindings,String recordId,ModelCallContext context){
        return evaluateConversation(messages,rule,agentSnapshot,preRuleFindings,recordId);
    }
    default LlmEvaluation evaluateCandidates(java.util.List<io.github.opensabre.iqc.conversation.model.ConversationMessage> messages,
            JsonNode rule,JsonNode agentSnapshot,String recordId,ModelCallContext context){
        return evaluateCandidates(messages,rule,agentSnapshot,recordId);
    }
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

    /** Shared structural contract for provider parsing and measured logical-output diagnostics. */
    static boolean validJudgment(JsonNode output) {
        return output != null && output.isObject() && output.path("hit").isBoolean()
                && output.path("reason").isTextual() && !output.path("reason").asText().trim().isEmpty()
                && (!output.hasNonNull("confidence") || output.path("confidence").isNumber()
                && Double.isFinite(output.path("confidence").doubleValue())
                && output.path("confidence").decimalValue().signum()>=0
                && output.path("confidence").decimalValue().compareTo(java.math.BigDecimal.ONE)<=0);
    }

    record LlmEvaluation(boolean supported, boolean hit, String reason, String structuredJson,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            SnapshotChatModelRouter.InvocationFingerprint invocationFingerprint) {
        public LlmEvaluation(boolean supported,boolean hit,String reason,String structuredJson){this(supported,hit,reason,structuredJson,null);}
        public LlmEvaluation(boolean supported, boolean hit, String reason) {
            this(supported, hit, reason, null);
        }
        public java.math.BigDecimal reportedConfidence(com.fasterxml.jackson.databind.ObjectMapper mapper){
            if(!supported||structuredJson==null||structuredJson.isBlank())return null;
            try{
                JsonNode output=readStructuredJson(mapper,structuredJson);
                return validJudgment(output)&&output.path("hit").booleanValue()==hit&&output.hasNonNull("confidence")
                        ?output.path("confidence").decimalValue():null;
            }catch(java.io.IOException|RuntimeException invalid){return null;}
        }
        public boolean validStructuredOutput(com.fasterxml.jackson.databind.ObjectMapper mapper,boolean labelFacts) {
            if(!supported || structuredJson==null || structuredJson.isBlank()) return false;
            try {
                JsonNode output=readStructuredJson(mapper,structuredJson);
                return validJudgment(output) && output.path("hit").booleanValue()==hit
                        && (!labelFacts || "iqc-label-facts-v2".equals(output.path("schemaVersion").asText())
                        && output.path("facts").isArray());
            } catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { return false; }
        }
    }
}
