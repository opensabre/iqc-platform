package io.github.opensabre.iqc.label;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper;
import io.github.opensabre.iqc.label.model.InspectionLabelResult;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.result.model.RuleInspectionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Materializes label projections from canonical rule decisions without invoking a detector again. */
@Service @RequiredArgsConstructor
public class LabelResultService {
    private final InspectionLabelResultMapper mapper; private final ObjectMapper objectMapper;

    /** Uses the caller's canonical conversation/messages; invoked inside conversation materialization's transaction. */
    public void materialize(ConversationInspectionResult conversation, String labelSnapshotJson, List<RuleInspectionResult> ruleResults,
                            List<io.github.opensabre.iqc.conversation.model.ConversationMessage> messages) {
        if (labelSnapshotJson == null || labelSnapshotJson.isBlank()) return;
        final JsonNode snapshot;
        try { snapshot = objectMapper.readTree(labelSnapshotJson); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalStateException("标签快照无效", exception); }
        if (!"2.0".equals(snapshot.path("schemaVersion").asText())) {
            materialize(conversation, labelSnapshotJson, ruleResults); return;
        }
        if (conversation.getId() == null || conversation.getConversationId() == null || !snapshot.path("labels").isArray()
                || snapshot.path("labels").isEmpty()) throw new IllegalStateException("新版标签快照或会话身份无效");
        var byMessage = new java.util.LinkedHashMap<String, io.github.opensabre.iqc.conversation.model.ConversationMessage>();
        for (var message : messages) {
            if (message.getId() == null || !conversation.getConversationId().equals(message.getConversationId())
                    || byMessage.putIfAbsent(message.getId(), message) != null) throw new IllegalStateException("标签证据会话范围无效");
        }
        var byRule = new java.util.LinkedHashMap<String, RuleInspectionResult>();
        for (var source : ruleResults) {
            if (source.getId() == null || !conversation.getId().equals(source.getConversationResultId())
                    || byRule.putIfAbsent(source.getRuleId(), source) != null) throw new IllegalStateException("标签规则结果归属无效");
        }
        var pending = new java.util.ArrayList<InspectionLabelResult>();
        var labelIds = new java.util.HashSet<String>();
        for (var label : snapshot.path("labels")) {
            String id = label.path("id").asText();
            if (!label.path("id").isTextual() || id.isBlank() || !labelIds.add(id)
                    || !label.path("versionNo").isIntegralNumber() || label.path("versionNo").asInt() < 1
                    || !List.of("user", "customer", "agent").contains(label.path("targetRole").asText())
                    || !label.path("bindings").isArray() || label.path("bindings").isEmpty()
                    || !label.path("values").isArray() || label.path("values").isEmpty()) throw new IllegalStateException("新版标签定义不完整");
            var sources = new java.util.ArrayList<RuleInspectionResult>();
            for (var binding : label.path("bindings")) {
                var source = byRule.get(binding.path("ruleId").asText());
                if (source == null || source.getRuleVersionNo() == null || !binding.path("ruleVersionNo").isIntegralNumber()
                        || source.getRuleVersionNo() != binding.path("ruleVersionNo").asInt()) throw new IllegalStateException("标签规则版本不一致");
                if (!sources.contains(source)) sources.add(source);
            }
            var valueCodes = new java.util.HashSet<String>();
            for (var definition : label.path("values")) {
                String code = definition.path("valueCode").asText();
                if (!definition.path("valueCode").isTextual() || code.isBlank() || !valueCodes.add(code)
                        || !List.of("FIXED", "BOOLEAN", "PERCENTAGE", "DURATION_MONTHS", "MONTH", "DATE")
                        .contains(definition.path("valueType").asText()))
                    throw new IllegalStateException("标签值编码或类型无效、重复");
                var payload = LabelFactEvaluator.evaluate(label, definition, sources, byMessage, objectMapper);
                var result = new InspectionLabelResult(); result.setConversationResultId(conversation.getId());
                result.setLabelId(id); result.setLabelVersionNo(label.path("versionNo").asInt()); result.setValueCode(code);
                result.setValueJson(payload.toString()); result.setGenerationSource("RULE");
                // Required legacy anchor, not a claim that only this rule supplied the fact. All candidates carry provenance.
                result.setSourceRuleResultId(sources.getFirst().getId()); pending.add(result);
            }
        }
        // Validate the entire projection before writing; database failures still propagate to the outer transaction.
        pending.forEach(mapper::insert);
    }

    public void materialize(ConversationInspectionResult conversation, String labelSnapshotJson, List<RuleInspectionResult> ruleResults) {
        if (labelSnapshotJson == null || labelSnapshotJson.isBlank()) return;
        try {
            JsonNode snapshot = objectMapper.readTree(labelSnapshotJson);
            // Coverage-aware snapshots must never fall through to the legacy hit-only projection.
            if ("2.0".equals(snapshot.path("schemaVersion").asText()))
                throw new IllegalStateException("标签联合输出尚未接入结果链路");
            JsonNode labels = snapshot.path("labels");
            if (!labels.isArray()) return;
            Map<String, RuleInspectionResult> byRule = ruleResults.stream().collect(Collectors.toMap(RuleInspectionResult::getRuleId, Function.identity(), (a, b) -> a));
            for (JsonNode label : labels) {
                for (JsonNode binding : label.path("bindings")) {
                    RuleInspectionResult source = byRule.get(binding.path("ruleId").asText());
                    if (source == null || !"HIT".equals(source.getResultStatus())) continue;
                    JsonNode values = label.path("values");
                    boolean inserted = false;
                    if (values.isArray()) {
                        for (JsonNode definition : values) {
                            JsonNode extracted = extractedValue(label, definition, source.getFindingJson());
                            if (extracted != null && valid(definition.path("valueType").asText(), extracted)) {
                                insert(conversation, label, definition.path("valueCode").asText(""), definition.path("valueType").asText(), extracted, source);
                                inserted = true;
                            }
                        }
                    }
                    if (!inserted) insert(conversation, label, "", null, null, source);
                    break;
                }
            }
        } catch (Exception exception) { throw new IllegalStateException("标签结果快照无效", exception); }
    }

    private JsonNode extractedValue(JsonNode label, JsonNode definition, String findingJson) {
        String valueCode = definition.path("valueCode").asText();
        try {
            if (findingJson != null && !findingJson.isBlank()) {
                JsonNode finding = objectMapper.readTree(findingJson);
                JsonNode value = findExtractedValue(finding, label.path("code").asText(), valueCode);
                if (value != null && !value.isNull()) return value;
            }
            JsonNode config = definition.path("configJson");
            if (config.isTextual() && !config.asText().isBlank()) {
                JsonNode defaultValue = objectMapper.readTree(config.asText()).get("defaultValue");
                if (defaultValue != null && !defaultValue.isNull()) return defaultValue;
            }
        } catch (Exception ignored) {
            // Invalid optional extraction is omitted; the label hit remains traceable.
        }
        return null;
    }

    private JsonNode findExtractedValue(JsonNode node, String labelCode, String valueCode) {
        if (node == null) return null;
        if (node.isObject()) {
            JsonNode values = node.path("labelValues");
            JsonNode scoped = values.path(labelCode);
            JsonNode value = scoped.isObject() ? scoped.get(valueCode) : values.get(valueCode);
            if (value == null) value = node.path("values").get(valueCode);
            if (value != null && !value.isNull()) return value;
        }
        if (node.isContainerNode()) for (JsonNode child : node) {
            JsonNode value = findExtractedValue(child, labelCode, valueCode);
            if (value != null) return value;
        }
        return null;
    }

    private boolean valid(String type, JsonNode value) {
        try {
            return switch (type == null ? "" : type.toUpperCase()) {
                case "FIXED" -> value.isValueNode();
                case "BOOLEAN" -> value.isBoolean();
                case "PERCENTAGE" -> value.isNumber() && value.decimalValue().compareTo(BigDecimal.ZERO) >= 0
                        && value.decimalValue().compareTo(BigDecimal.valueOf(100)) <= 0;
                case "DURATION_MONTHS" -> value.canConvertToInt() && value.asInt() >= 0;
                case "MONTH" -> value.canConvertToInt() && value.asInt() >= 1 && value.asInt() <= 12;
                case "DATE" -> { LocalDate.parse(value.asText()); yield true; }
                default -> false;
            };
        } catch (Exception ignored) { return false; }
    }

    private void insert(ConversationInspectionResult conversation, JsonNode label, String valueCode, String valueType,
                        JsonNode value, RuleInspectionResult source) {
        InspectionLabelResult result = new InspectionLabelResult(); result.setConversationResultId(conversation.getId());
        result.setLabelId(label.path("id").asText()); result.setLabelVersionNo(label.path("versionNo").asInt(1));
        result.setValueCode(valueCode);
        if (value != null) {
            var payload = objectMapper.createObjectNode().put("valueCode", valueCode).put("valueType", valueType);
            payload.set("value", value);
            result.setValueJson(payload.toString());
        }
        BigDecimal confidence = source.getConfidence();
        result.setConfidence(confidence != null && confidence.compareTo(BigDecimal.ZERO) >= 0 && confidence.compareTo(BigDecimal.ONE) <= 0 ? confidence : null);
        result.setSourceRuleResultId(source.getId()); result.setGenerationSource("RULE"); mapper.insert(result);
    }
}
