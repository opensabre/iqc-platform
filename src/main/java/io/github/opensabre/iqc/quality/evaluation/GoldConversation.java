package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable reviewed Finding-level gold input; persistence must authenticate both identities. */
public record GoldConversation(String schemaVersion, String sourceSampleId, String conversationId,
                               List<Message> messages, JsonNode businessContext, List<GoldFinding> findings,
                               String annotatorId, String reviewerId, String source, List<String> tags,
                               Difficulty difficulty, GoldDecision decision) {
    public static final String SCHEMA = "iqc-gold-conversation-v1";
    public enum Difficulty { BASIC, INTERMEDIATE, HARD }
    public record Message(String id, String role, String content, int sequence, java.time.LocalTime relativeTime,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.conversation.ExactMillisecondsDeserializer.class) Long startMs,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.conversation.ExactMillisecondsDeserializer.class) Long endMs,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) java.math.BigDecimal confidence) {
        public Message(String id,String role,String content,int sequence,java.time.LocalTime relativeTime) {
            this(id,role,content,sequence,relativeTime,null,null,null);
        }
        public Message {
            io.github.opensabre.iqc.conversation.TranscriptAlignment.validate(startMs,endMs,confidence);
            required(id, "message id"); required(role, "message role");
            if(id.length()>64 || role.length()>128) throw new IllegalArgumentException("gold message identity too long");
            Objects.requireNonNull(content, "message content");
            if (sequence < 0) throw new IllegalArgumentException("negative message sequence");
        }
    }
    public GoldConversation {
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("unsupported gold schema");
        required(sourceSampleId, "source sample"); required(conversationId, "conversation");
        required(annotatorId, "annotator"); required(reviewerId, "reviewer"); required(source, "source");
        if (annotatorId.equals(reviewerId)) throw new IllegalArgumentException("gold requires independent review");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        tags = tags == null ? List.of() : List.copyOf(tags);
        businessContext = businessContext == null || businessContext.isNull() ? null : businessContext;
        validateInput(messages, businessContext, findings, tags, difficulty);
        businessContext = businessContext == null ? null : businessContext.deepCopy();
    }
    static void validateInput(List<Message> messages, JsonNode businessContext, List<GoldFinding> findings,
                              List<String> tags, Difficulty difficulty) {
        Objects.requireNonNull(difficulty, "difficulty");
        if (messages.isEmpty() || messages.size() > 10000) throw new IllegalArgumentException("invalid gold conversation size");
        Set<String> ids = messages.stream().map(Message::id).collect(Collectors.toSet());
        if (ids.size() != messages.size()) throw new IllegalArgumentException("duplicate message id");
        int previous = -1;
        for (Message message : messages) {
            if (message.sequence() <= previous) throw new IllegalArgumentException("messages must be strictly ordered");
            previous = message.sequence();
        }
        if (findings.isEmpty() || findings.size() > 1000
                || findings.stream().map(GoldFinding::code).distinct().count() != findings.size())
            throw new IllegalArgumentException("invalid gold finding set");
        findings.forEach(finding -> finding.validateEvidence(ids));
        if (businessContext != null && !businessContext.isObject())
            throw new IllegalArgumentException("business context must be an object");
        if (tags.size() > 100 || tags.stream().anyMatch(tag -> tag.isBlank() || tag.length() > 128)
                || tags.stream().distinct().count() != tags.size())
            throw new IllegalArgumentException("invalid gold tags");
    }
    @Override public JsonNode businessContext() { return businessContext == null ? null : businessContext.deepCopy(); }
    private static void required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
