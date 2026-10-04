package io.github.opensabre.iqc.scheme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.scoring.InspectionScoring;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Projects detector observations into business decisions before applying the frozen scoring policy. */
public final class SchemeResultEvaluator {
    private SchemeResultEvaluator() { }

    /** All business items remain visible, including those that intentionally do not contribute points. */
    public record ItemResult(String itemCode, String name, String ruleId, int ruleVersionNo,
                             InspectionScoring.ItemStatus status, List<String> matchedMessageIds) { }
    public record Evaluation(List<ItemResult> items, InspectionScoring.Result scoring) { }

    /** A missing marker means legacy scoring; an invalid explicit marker must never fall back to it. */
    public static SchemeDefinition definition(JsonNode snapshot, ObjectMapper mapper) {
        if (snapshot == null || !snapshot.has("schemeSnapshot")) return null;
        try {
            JsonNode node = snapshot.path("schemeSnapshot").path("release").path("definition");
            if (!node.isObject()) throw IqcException.invalidState("缺少业务方案定义");
            return mapper.treeToValue(node, SchemeDefinition.class);
        } catch (Exception exception) {
            throw IqcException.invalidState("业务方案任务快照无效，禁止回退旧评分");
        }
    }

    /** Requires complete applicable-message coverage; missing, erroneous and disputed evidence never becomes PASS. */
    public static Evaluation evaluate(SchemeDefinition definition, JsonNode snapshot, List<ConversationMessage> messages,
                                      List<InspectionResult> results, ObjectMapper mapper) {
        definition.requireSupportedRoutes();
        Map<String, JsonNode> rules = new HashMap<>();
        snapshot.path("rules").forEach(rule -> rules.put(rule.path("id").asText(), rule));
        Map<String, InspectionResult> byMessage = new HashMap<>();
        results.forEach(result -> byMessage.put(result.getMessageId(), result));
        List<ItemResult> items = new ArrayList<>();
        for (var item : definition.items()) {
            JsonNode rule = rules.get(item.rule().id());
            if (rule == null || rule.path("versionNo").asInt() != item.rule().versionNo())
                throw IqcException.invalidState("业务质检项与检测规则快照版本不一致");
            if (item.appliesWhen() != null) {
                var gate = applicability(item.appliesWhen(), rules, messages, byMessage, mapper);
                if (gate != null) {
                    // Non-applicability is explicit business logic; absent detector output cannot masquerade as it.
                    items.add(new ItemResult(item.itemCode(), item.name(), item.rule().id(), item.rule().versionNo(), gate, List.of()));
                    continue;
                }
            }
            String role = rule.path("targetRole").asText("all");
            List<ConversationMessage> applicable = messages.stream().filter(message -> role.isBlank()
                    || "all".equalsIgnoreCase(role) || role.equalsIgnoreCase(message.getSpeakerRole())).toList();
            List<String> statuses = new ArrayList<>();
            List<String> matched = new ArrayList<>();
            for (ConversationMessage message : applicable) {
                String status = status(byMessage.get(message.getId()), item.rule().id(), mapper);
                statuses.add(status);
                if ("HIT".equals(status) && !rule.path("labelFactTargets").isArray()) {
                    if ("DLS".equalsIgnoreCase(rule.path("ruleType").asText())) {
                        try {
                            JsonNode evidence = mapper.readTree(byMessage.get(message.getId()).getEvidenceJson());
                            if (evidence != null && evidence.isArray()) for (JsonNode value : evidence)
                                if (item.rule().id().equals(value.path("ruleId").asText()) && value.path("messageId").isTextual())
                                    matched.add(value.path("messageId").asText());
                        } catch (Exception ignored) { /* Absence conditions can be true without a positive evidence span. */ }
                    } else if (!"CONVERSATION".equals(rule.path("inspectionScope").asText())) matched.add(message.getId());
                }
            }
            if (rule.path("labelFactTargets").isArray() && statuses.contains("HIT")) {
                Set<String> applicableIds = applicable.stream().map(ConversationMessage::getId).collect(Collectors.toSet());
                for (InspectionResult result : results) {
                    if (!"HIT".equals(status(result, item.rule().id(), mapper))) continue;
                    try {
                        JsonNode citations = mapper.readTree(result.getEvidenceJson());
                        if (citations != null && citations.isArray()) for (JsonNode citation : citations) {
                            String messageId = citation.path("messageId").asText("");
                            if (item.rule().id().equals(citation.path("ruleId").asText()) && applicableIds.contains(messageId))
                                matched.add(messageId);
                        }
                    } catch (Exception ignored) { /* Missing citations must not become a fabricated owner-message match. */ }
                }
            }
            InspectionScoring.ItemStatus decision;
            if (messages.isEmpty()) decision = InspectionScoring.ItemStatus.NOT_EVALUATED;
            else if (applicable.isEmpty()) decision = InspectionScoring.ItemStatus.NOT_APPLICABLE;
            else if (statuses.contains("ERROR")) decision = InspectionScoring.ItemStatus.ERROR;
            else if (statuses.contains("REVIEW_REQUIRED")) decision = InspectionScoring.ItemStatus.REVIEW_REQUIRED;
            else if (statuses.contains("NOT_EVALUATED")) decision = InspectionScoring.ItemStatus.NOT_EVALUATED;
            else {
                boolean hit = statuses.contains("HIT");
                boolean failed = item.hitMeaning() == SchemeDefinition.HitMeaning.VIOLATION ? hit : !hit;
                decision = failed ? InspectionScoring.ItemStatus.FAIL : InspectionScoring.ItemStatus.PASS;
            }
            items.add(new ItemResult(item.itemCode(), item.name(), item.rule().id(), item.rule().versionNo(), decision, matched.stream().distinct().toList()));
        }
        return scoreItems(definition, items);
    }

    /** Shared scoring projection for automatic decisions and separately retained human-reviewed decisions. */
    public static Evaluation scoreItems(SchemeDefinition definition, List<ItemResult> items) {
        Set<String> scored = definition.scoring().items().stream().map(InspectionScoring.Item::itemCode).collect(Collectors.toSet());
        var decisions = items.stream().filter(item -> scored.contains(item.itemCode()))
                .map(item -> new InspectionScoring.Decision(item.itemCode(), item.status())).toList();
        return new Evaluation(List.copyOf(items), InspectionScoring.evaluate(definition.scoring(), decisions));
    }

    /** Null means a fully evaluated positive gate; only a complete negative gate permits NOT_APPLICABLE. */
    private static InspectionScoring.ItemStatus applicability(SchemeDefinition.RuleReference reference,
            Map<String, JsonNode> rules, List<ConversationMessage> messages, Map<String, InspectionResult> results, ObjectMapper mapper) {
        var rule = rules.get(reference.id());
        if (rule == null || rule.path("versionNo").asInt() != reference.versionNo())
            throw IqcException.invalidState("适用条件与检测规则快照版本不一致");
        if (messages.isEmpty()) return InspectionScoring.ItemStatus.NOT_EVALUATED;
        String role = rule.path("targetRole").asText("all");
        var applicable = messages.stream().filter(message -> role.isBlank() || "all".equalsIgnoreCase(role)
                || role.equalsIgnoreCase(message.getSpeakerRole())).toList();
        if (applicable.isEmpty()) return InspectionScoring.ItemStatus.NOT_APPLICABLE;
        var statuses = applicable.stream().map(message -> status(results.get(message.getId()), reference.id(), mapper)).toList();
        if (statuses.contains("ERROR")) return InspectionScoring.ItemStatus.ERROR;
        if (statuses.contains("REVIEW_REQUIRED")) return InspectionScoring.ItemStatus.REVIEW_REQUIRED;
        if (statuses.contains("NOT_EVALUATED")) return InspectionScoring.ItemStatus.NOT_EVALUATED;
        return statuses.contains("HIT") ? null : InspectionScoring.ItemStatus.NOT_APPLICABLE;
    }

    private static String status(InspectionResult result, String ruleId, ObjectMapper mapper) {
        if (result == null) return "NOT_EVALUATED";
        if ("REVIEW_REQUIRED".equals(result.getResultStatus())) return "REVIEW_REQUIRED";
        try {
            JsonNode breakdown = mapper.readTree(result.getRuleBreakdownJson());
            if (breakdown == null || !breakdown.isArray()) return "ERROR";
            String status = null;
            for (JsonNode slice : breakdown) {
                if (!ruleId.equals(slice.path("ruleId").asText())) continue;
                if (status != null) return "ERROR";
                status = slice.path("status").asText();
            }
            if (status == null) return result.getResultStatus() != null && result.getResultStatus().endsWith("ERROR") ? "ERROR" : "NOT_EVALUATED";
            return switch (status) {
                case "HIT", "NOT_HIT", "REVIEW_REQUIRED", "NOT_EVALUATED" -> status;
                default -> "ERROR";
            };
        } catch (Exception exception) { return "ERROR"; }
    }
}
