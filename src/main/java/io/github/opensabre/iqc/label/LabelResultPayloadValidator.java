package io.github.opensabre.iqc.label;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.governance.IqcException;

import java.util.HashSet;
import java.util.Set;

/** Shared V2 label-result contract for publication and result reads. */
public final class LabelResultPayloadValidator {
    private LabelResultPayloadValidator() { }

    /** Validates a generated result; publication additionally supplies frozen type/role and rejects ERROR. */
    public static void validate(JsonNode payload, String expectedType, String expectedRole, boolean allowError) {
        if (payload == null || !payload.isObject()
                || !"iqc-label-result-v2".equals(payload.path("schemaVersion").asText()))
            throw IqcException.invalidState("标签结果协议无效");
        String status = payload.path("status").asText();
        if (!Set.of("KNOWN", "UNKNOWN", "CONFLICT", "ERROR").contains(status)
                || !allowError && "ERROR".equals(status))
            throw IqcException.invalidState("标签结果状态无效");
        if (expectedType != null && !expectedType.equals(payload.path("valueType").asText())
                || expectedRole != null && !expectedRole.equals(payload.path("subjectRole").asText())
                || !payload.path("candidates").isArray())
            throw IqcException.invalidState("标签结果值或候选无效");
        var distinct = new HashSet<JsonNode>();
        for (JsonNode candidate : payload.path("candidates")) {
            JsonNode value = candidate.path("value");
            if (!candidate.isObject() || !candidate.path("sourceRuleResultId").isTextual()
                    || candidate.path("sourceRuleResultId").asText().isBlank()
                    || !validValue(expectedType, value)
                    || !candidate.path("evidence").isArray() || candidate.path("evidence").isEmpty())
                throw IqcException.invalidState("标签结果值或候选无效");
            for (JsonNode quote : candidate.path("evidence"))
                if (!quote.isObject() || !quote.path("messageId").isTextual() || quote.path("messageId").asText().isBlank()
                        || !quote.path("text").isTextual() || quote.path("text").asText().isBlank())
                    throw IqcException.invalidState("标签结果值或候选无效");
            distinct.add(value);
        }
        boolean hasValue = payload.hasNonNull("value");
        if ("KNOWN".equals(status)) {
            if (!hasValue || !validValue(expectedType, payload.get("value"))
                    || distinct.size() != 1 || !distinct.contains(payload.get("value")))
                throw IqcException.invalidState("标签结果确定值缺少有效候选");
        } else if (hasValue || "CONFLICT".equals(status) && distinct.size() < 2
                || "UNKNOWN".equals(status) && distinct.size() > 1) {
            throw IqcException.invalidState("标签结果状态与候选不一致");
        }
    }

    private static boolean validValue(String type, JsonNode value) {
        return type == null ? value != null && value.isValueNode() && !value.isNull() && !value.isBinary()
                : LabelFactEvaluator.validValue(type, value);
    }
}
