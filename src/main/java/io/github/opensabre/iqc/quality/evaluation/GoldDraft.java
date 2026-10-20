package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/** Server-authored annotation identity and input snapshot awaiting independent review. */
public record GoldDraft(String schemaVersion, String sourceSampleId, String conversationId,
                        List<GoldConversation.Message> messages, @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.ExactValueDeserializer.class) JsonNode businessContext,
                        List<GoldFinding> findings, String annotatorId, String source, List<String> tags,
                        GoldConversation.Difficulty difficulty, GoldDecision decision) {
    public static final String SCHEMA = "iqc-gold-draft-v1";
    /** Explicit exact-number digest protocol; historical v1 remains unchanged. */
    public static final String EXACT_SCHEMA = "iqc-gold-draft-v2";
    public GoldDraft {
        if ((!SCHEMA.equals(schemaVersion) && !EXACT_SCHEMA.equals(schemaVersion))) throw new IllegalArgumentException("unsupported gold draft schema");
        for (String required : new String[]{sourceSampleId, conversationId, annotatorId, source})
            if (required == null || required.isBlank()) throw new IllegalArgumentException("missing gold draft identity");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        tags = tags == null ? List.of() : List.copyOf(tags);
        businessContext = businessContext == null || businessContext.isNull() ? null : businessContext;
        GoldConversation.validateInput(messages, businessContext, findings, tags, difficulty);
        businessContext = businessContext == null ? null : businessContext.deepCopy();
    }
    @Override public JsonNode businessContext() { return businessContext == null ? null : businessContext.deepCopy(); }
    public GoldConversation approve(String reviewerId) {
        return new GoldConversation(EXACT_SCHEMA.equals(schemaVersion) ? GoldConversation.EXACT_SCHEMA : GoldConversation.SCHEMA, sourceSampleId, conversationId, messages,
                businessContext, findings, annotatorId, reviewerId, source, tags, difficulty, decision);
    }
}
