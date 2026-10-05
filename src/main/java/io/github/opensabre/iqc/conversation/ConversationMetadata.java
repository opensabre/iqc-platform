package io.github.opensabre.iqc.conversation;

import io.github.opensabre.iqc.governance.IqcException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Locale;

/**
 * Identifies the real conversation participants and upstream business context.
 * Employee identifiers reference OpenSabre organization data; names are immutable import-time snapshots.
 */
public record ConversationMetadata(
        String employeeId,
        String employeeName,
        String employeeGroupId,
        String customerExternalId,
        String customerName,
        String customerContactMasked,
        String channel,
        LocalDateTime startedTime,
        LocalDateTime endedTime,
        String businessType,
        String businessNo,
        List<String> tags,
        @JsonInclude(JsonInclude.Include.NON_NULL) String asrStatus,
        @JsonInclude(JsonInclude.Include.NON_NULL) String customerLevel,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean complaint,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, JsonNode> businessFields,
        @JsonInclude(JsonInclude.Include.NON_NULL) String employeeDepartmentId) {

    public ConversationMetadata(String employeeId, String employeeName, String employeeGroupId,
            String customerExternalId, String customerName, String customerContactMasked, String channel,
            LocalDateTime startedTime, LocalDateTime endedTime, String businessType, String businessNo,
            List<String> tags, String asrStatus, String customerLevel, Boolean complaint,
            Map<String, JsonNode> businessFields) {
        this(employeeId, employeeName, employeeGroupId, customerExternalId, customerName, customerContactMasked,
                channel, startedTime, endedTime, businessType, businessNo, tags, asrStatus, customerLevel,
                complaint, businessFields, null);
    }

    public ConversationMetadata(String employeeId, String employeeName, String employeeGroupId,
            String customerExternalId, String customerName, String customerContactMasked, String channel,
            LocalDateTime startedTime, LocalDateTime endedTime, String businessType, String businessNo,
            List<String> tags) {
        this(employeeId, employeeName, employeeGroupId, customerExternalId, customerName, customerContactMasked,
                channel, startedTime, endedTime, businessType, businessNo, tags, null, null, null, null);
    }

    /** Normalizes optional upstream values and rejects an invalid conversation period. */
    public ConversationMetadata normalized() {
        if (startedTime != null && endedTime != null && endedTime.isBefore(startedTime)) {
            throw IqcException.invalidArgument("会话结束时间不能早于开始时间");
        }
        String maskedContact = sized("customerContactMasked", customerContactMasked, 128);
        if (maskedContact != null && !maskedContact.contains("*")) {
            throw IqcException.invalidArgument("客户联系方式只能传入脱敏值");
        }
        List<String> normalizedTags = tags == null ? List.of() : tags.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().limit(50).toList();
        return new ConversationMetadata(sized("employeeId", employeeId, 128), sized("employeeName", employeeName, 128),
                sized("employeeGroupId", employeeGroupId, 64), sized("customerExternalId", customerExternalId, 128),
                sized("customerName", customerName, 128), maskedContact, sizedUpper("channel", channel, 32),
                startedTime, endedTime, sizedUpper("businessType", businessType, 64),
                sized("businessNo", businessNo, 128), normalizedTags, sizedUpper("asrStatus", asrStatus, 32),
                sized("customerLevel", customerLevel, 64), complaint, normalizedBusinessFields(businessFields), sized("employeeDepartmentId", employeeDepartmentId, 64));
    }

    /** Stable metadata portion used when content fingerprinting file imports. */
    public String fingerprintPart() {
        ConversationMetadata value = normalized();
        String legacy = String.join("|", safe(value.employeeId), safe(value.employeeGroupId), safe(value.customerExternalId),
                safe(value.channel), safe(value.startedTime), safe(value.businessType), safe(value.businessNo));
        if (value.asrStatus == null && value.customerLevel == null && value.complaint == null && value.businessFields == null && value.employeeDepartmentId == null) return legacy;
        Map<String, Object> extension = new TreeMap<>();
        extension.put("asrStatus", value.asrStatus); extension.put("customerLevel", value.customerLevel);
        extension.put("complaint", value.complaint); extension.put("businessFields", value.businessFields);
        if (value.employeeDepartmentId != null) extension.put("employeeDepartmentId", value.employeeDepartmentId);
        return legacy + "|selector-metadata-v1:" + serialize(extension);
    }

    private static Map<String, JsonNode> normalizedBusinessFields(Map<String, JsonNode> input) {
        if (input == null || input.isEmpty()) return null;
        if (input.size() > 64) throw IqcException.invalidArgument("businessFields 最多 64 项");
        Map<String, JsonNode> fields = new TreeMap<>();
        input.forEach((key, node) -> {
            if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))
                throw IqcException.invalidArgument("businessFields 键格式无效");
            if (node == null || !(node.isNull() || node.isTextual() || node.isBoolean() || node.isNumber()))
                throw IqcException.invalidArgument("businessFields 只允许 JSON 标量值");
            if (node.isTextual() && node.textValue().length() > 4096)
                throw IqcException.invalidArgument("businessFields 字符串最长 4096 字符");
            if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue()))
                throw IqcException.invalidArgument("businessFields 数字必须有限");
            fields.put(key, node.deepCopy());
        });
        serialize(fields);
        return Collections.unmodifiableMap(fields);
    }

    private static String serialize(Object fields) {
        try {
            String json = new ObjectMapper().writeValueAsString(fields);
            if (json.getBytes(StandardCharsets.UTF_8).length > 65536)
                throw IqcException.invalidArgument("businessFields 序列化最长 65536 字节");
            return json;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw IqcException.invalidArgument("businessFields 无法序列化");
        }
    }

    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String sized(String field, String value, int maxLength) {
        String result = trim(value);
        if (result != null && result.length() > maxLength) throw IqcException.invalidArgument(field + " 长度不能超过 " + maxLength);
        return result;
    }
    private static String sizedUpper(String field, String value, int maxLength) { String result = sized(field, value, maxLength); return result == null ? null : result.toUpperCase(Locale.ROOT); }
    private static String safe(Object value) { return value == null ? "" : value.toString(); }
}
