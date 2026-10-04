package io.github.opensabre.iqc.finding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.result.llm.LlmTextSanitizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Namespaced compatibility facts preserve legacy rule meaning until an explicit semantic mapping is configured. */
public final class LegacyFindingAdapter {
    private LegacyFindingAdapter() { }

    public static Finding adapt(String id, JsonNode rule, String status, boolean executed, String reason,
                                JsonNode citations, List<ConversationMessage> inputs, Finding.Source source) {
        String ruleId = rule.path("id").asText();
        var state = !executed ? Finding.State.NOT_EVALUATED : switch (Objects.requireNonNullElse(status, "")) {
            case "HIT", "NOT_HIT" -> Finding.State.KNOWN;
            case "ERROR" -> Finding.State.ERROR;
            case "NOT_EVALUATED" -> Finding.State.NOT_EVALUATED;
            default -> Finding.State.UNKNOWN;
        };
        var evidence = new ArrayList<Finding.Evidence>();
        boolean invalidEvidence = citations != null && !citations.isNull() && !citations.isArray();
        if (citations != null && citations.isArray()) for (var citation : citations) {
            String messageId = citation.path("messageId").asText();
            String text = citation.path("text").asText(citation.path("matchedText").asText());
            var message = inputs.stream().filter(input -> Objects.equals(messageId, input.getId())).findFirst().orElse(null);
            if (message == null || text.isBlank() || message.getContent() == null || !message.getContent().contains(text)) {
                invalidEvidence = true; continue;
            }
            var details = JsonNodeFactory.instance.objectNode().put("text", LlmTextSanitizer.sanitize(text))
                    .put("sequenceNo", message.getSequenceNo()).put("speakerRole", message.getSpeakerRole());
            var quote = new Finding.Evidence(id + ":e" + evidence.size(), Finding.EvidenceType.CONVERSATION, messageId, details);
            if (evidence.stream().noneMatch(existing -> existing.reference().equals(messageId) && existing.details().equals(details)))
                evidence.add(quote);
        }
        if (invalidEvidence && state == Finding.State.KNOWN) state = Finding.State.ERROR;
        var severity = severity(rule.path("riskLevel").asText());
        String category = rule.path("category").asText("LEGACY_RULE");
        if (category.isBlank()) category = "LEGACY_RULE";
        return new Finding(id, "LEGACY_RULE:" + ruleId, category,
                Finding.ValueType.BOOLEAN, state == Finding.State.KNOWN ? BooleanNode.valueOf("HIT".equals(status)) : null,
                state, null, severity, invalidEvidence ? "INVALID_EVIDENCE" : LlmTextSanitizer.sanitize(reason), evidence, source);
    }

    private static Finding.Severity severity(String value) {
        try { return Finding.Severity.valueOf(value); }
        catch (IllegalArgumentException exception) { return Finding.Severity.NONE; }
    }
}
