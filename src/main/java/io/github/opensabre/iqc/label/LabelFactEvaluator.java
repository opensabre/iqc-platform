package io.github.opensabre.iqc.label;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.result.model.RuleInspectionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Strict fact projection; raw detector findings are never recursively searched for convenient values. */
public final class LabelFactEvaluator {
    private LabelFactEvaluator() { }

    /** Evaluates one frozen value definition and retains all validated candidates, including explicit false. */
    static JsonNode evaluate(JsonNode label, JsonNode valueDefinition, List<RuleInspectionResult> sources,
                             Map<String, ConversationMessage> messages, ObjectMapper mapper) {
        var result = mapper.createObjectNode().put("schemaVersion", "iqc-label-result-v2")
                .put("valueCode", valueDefinition.path("valueCode").asText())
                .put("valueType", valueDefinition.path("valueType").asText())
                .put("subjectRole", label.path("targetRole").asText());
        var candidates = result.putArray("candidates");
        var reasons = result.putArray("reasons");
        var distinct = new ArrayList<JsonNode>();
        boolean error = false, uncertain = false;
        for (var source : sources) {
            try {
                var envelope = mapper.readTree(source.getFindingJson() == null ? "{}" : source.getFindingJson());
                if (envelope == null || !"iqc-rule-observations-v2".equals(envelope.path("schemaVersion").asText())
                        || !envelope.path("observations").isArray() || envelope.path("observations").isEmpty()) {
                    error = true; reasons.add("MISSING_OBSERVATIONS"); continue;
                }
                for (var observation : envelope.path("observations")) {
                    String status = observation.path("status").asText();
                    if (observation.has("findingError") || !("HIT".equals(status) || "NOT_HIT".equals(status))) {
                        error = true; reasons.add("DETECTION_INCOMPLETE"); continue;
                    }
                    // Even NOT_HIT must provide an explicit evaluated empty fact list; absence is not proof of coverage.
                    var finding = observation.path("finding");
                    if (!"iqc-label-facts-v2".equals(finding.path("schemaVersion").asText()) || !finding.path("facts").isArray()) {
                        error = true; reasons.add("INVALID_FACT_CONTRACT"); continue;
                    }
                    if (finding.hasNonNull("factError")) {
                        error = true; reasons.add(finding.path("factError").asText()); continue;
                    }
                    for (var fact : finding.path("facts")) {
                        if (!fact.isObject() || !fact.path("labelId").isTextual() || fact.path("labelId").asText().isBlank()
                                || !fact.path("valueCode").isTextual() || fact.path("valueCode").asText().isBlank()
                                || !fact.path("ruleId").isTextual() || fact.path("ruleId").asText().isBlank()) {
                            error = true; reasons.add("INVALID_FACT_CONTRACT"); continue;
                        }
                        if (!label.path("id").asText().equals(fact.path("labelId").asText())
                                || !valueDefinition.path("valueCode").asText().equals(fact.path("valueCode").asText())) continue;
                        if (!source.getRuleId().equals(fact.path("ruleId").asText())) {
                            error = true; reasons.add("RULE_MISMATCH"); continue;
                        }
                        if (!"CURRENT_PARTICIPANT".equals(fact.path("subjectKind").asText())
                                || !label.path("targetRole").asText().equals(fact.path("subjectRole").asText())
                                || !List.of("user", "customer", "agent").contains(fact.path("subjectRole").asText())) {
                            uncertain = true; reasons.add("SUBJECT_UNCERTAIN"); continue;
                        }
                        var value = fact.get("value");
                        if (!validValue(valueDefinition.path("valueType").asText(), value)) {
                            error = true; reasons.add("INVALID_VALUE"); continue;
                        }
                        var evidence = fact.path("evidence");
                        boolean validEvidence = evidence.isArray() && !evidence.isEmpty();
                        for (var quote : evidence) {
                            if (!quote.isObject() || !quote.path("messageId").isTextual()
                                    || !quote.path("text").isTextual()) {
                                validEvidence = false; continue;
                            }
                            var message = messages.get(quote.path("messageId").asText());
                            String text = quote.path("text").asText();
                            if (message == null || text.isBlank() || message.getContent() == null
                                    || !message.getContent().contains(text)
                                    || !fact.path("subjectRole").asText().equals(message.getSpeakerRole())) validEvidence = false;
                        }
                        if (!validEvidence) { error = true; reasons.add("INVALID_EVIDENCE"); continue; }
                        var candidate = candidates.addObject().put("sourceRuleResultId", source.getId());
                        candidate.set("value", value); candidate.set("evidence", evidence.deepCopy());
                        if (distinct.stream().noneMatch(value::equals)) distinct.add(value);
                    }
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                error = true; reasons.add("INVALID_JSON");
            }
        }
        String state = error ? "ERROR" : distinct.size() > 1 ? "CONFLICT" : uncertain || distinct.isEmpty() ? "UNKNOWN" : "KNOWN";
        result.put("status", state);
        if ("KNOWN".equals(state)) result.set("value", distinct.getFirst());
        if ("UNKNOWN".equals(state) && distinct.isEmpty()) {
            // A missing target speaker or an unmapped speaker is not evidence that the target said nothing.
            boolean targetPresent = messages.values().stream().anyMatch(message ->
                    label.path("targetRole").asText().equals(message.getSpeakerRole()));
            boolean unmappedPresent = messages.values().stream().anyMatch(message -> {
                String role = message.getSpeakerRole();
                return role == null || !List.of("user", "customer", "agent").contains(role);
            });
            if (unmappedPresent) reasons.add("UNMAPPED_SPEAKER_ROLE");
            if (!targetPresent) reasons.add("NO_TARGET_SPEAKER");
            if (reasons.isEmpty()) reasons.add("NOT_MENTIONED");
        }
        return result;
    }

    /** Shared value contract for expert hit mappings and materialized model facts. */
    public static boolean validValue(String type, JsonNode value) {
        if (value == null || value.isNull()) return false;
        try {
            return switch (type) {
                case "BOOLEAN" -> value.isBoolean();
                case "FIXED" -> value.isValueNode() && !value.isBinary();
                case "PERCENTAGE" -> value.isNumber() && value.decimalValue().signum() >= 0
                        && value.decimalValue().compareTo(java.math.BigDecimal.valueOf(100)) <= 0;
                case "MONTH" -> value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 1 && value.asInt() <= 12;
                case "DURATION_MONTHS" -> value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0;
                case "DATE" -> { if (!value.isTextual()) yield false; java.time.LocalDate.parse(value.asText()); yield true; }
                default -> false;
            };
        } catch (java.time.DateTimeException exception) { return false; }
    }
}
