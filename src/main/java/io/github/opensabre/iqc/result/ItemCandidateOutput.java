package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.result.llm.LlmQualityProvider;
import io.github.opensabre.iqc.result.model.InspectionResult;

import java.util.ArrayList;
import java.util.List;

/** Strict candidate-stage protocol; ordinary hit flags are not proof of complete candidate extraction. */
final class ItemCandidateOutput {
    static final String SCHEMA = "iqc-item-candidates-v1";
    private ItemCandidateOutput() { }

    record Decoded(InspectionResult result, List<ConversationMessage> quotes) { }

    /** Source membership and exact quote containment are independently checked by ItemRouteRunner. */
    static Decoded decode(ObjectMapper mapper, LlmQualityProvider.LlmEvaluation evaluation) {
        var result = new InspectionResult();
        result.setDeduction(0); result.setFindingJson("[]"); result.setEvidenceJson("[]");
        result.setResultStatus("REVIEW_REQUIRED"); result.setReason("模型未提供可验证的候选结果");
        if (evaluation == null) return new Decoded(result, List.of());
        if (!evaluation.supported()) {
            result.setResultStatus("ERROR"); result.setReason("候选模型执行失败或不支持候选抽取");
            return new Decoded(result, List.of());
        }
        String json = evaluation.structuredJson();
        if (json == null || json.length() > 100000) return new Decoded(result, List.of());
        try {
            var root = mapper.readTree(json);
            if (root == null || !root.isObject() || !SCHEMA.equals(root.path("schemaVersion").asText())
                    || !root.path("outcome").isTextual() || !root.path("quotes").isArray()
                    || root.path("quotes").size() > 200) return new Decoded(result, List.of());
            String outcome = root.path("outcome").asText();
            if ("NO_MATCH".equals(outcome) && root.path("quotes").isEmpty()) {
                result.setResultStatus("NOT_HIT"); result.setReason("候选阶段明确无候选");
                result.setFindingJson(json); return new Decoded(result, List.of());
            }
            if (!"MATCH".equals(outcome) || root.path("quotes").isEmpty()) return new Decoded(result, List.of());
            var quotes = new ArrayList<ConversationMessage>();
            for (var quote : root.path("quotes")) {
                if (!quote.isObject()) return new Decoded(result, List.of());
                for (String field : List.of("messageId", "conversationId", "speakerRole", "text"))
                    if (!quote.path(field).isTextual() || quote.path(field).asText().isBlank())
                        return new Decoded(result, List.of());
                var message = new ConversationMessage(); message.setId(quote.path("messageId").asText());
                message.setConversationId(quote.path("conversationId").asText());
                message.setSpeakerRole(quote.path("speakerRole").asText()); message.setContent(quote.path("text").asText());
                quotes.add(message);
            }
            result.setResultStatus("HIT"); result.setReason("候选阶段返回待验证引文"); result.setFindingJson(json);
            return new Decoded(result, List.copyOf(quotes));
        } catch (JsonProcessingException exception) {
            return new Decoded(result, List.of());
        }
    }
}
