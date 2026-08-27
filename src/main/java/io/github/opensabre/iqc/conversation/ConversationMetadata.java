package io.github.opensabre.iqc.conversation;

import io.github.opensabre.iqc.governance.IqcException;
import java.time.LocalDateTime;
import java.util.List;

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
        List<String> tags) {

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
                sized("businessNo", businessNo, 128), normalizedTags);
    }

    /** Stable metadata portion used when content fingerprinting file imports. */
    public String fingerprintPart() {
        ConversationMetadata value = normalized();
        return String.join("|", safe(value.employeeId), safe(value.employeeGroupId), safe(value.customerExternalId),
                safe(value.channel), safe(value.startedTime), safe(value.businessType), safe(value.businessNo));
    }

    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String sized(String field, String value, int maxLength) {
        String result = trim(value);
        if (result != null && result.length() > maxLength) throw IqcException.invalidArgument(field + " 长度不能超过 " + maxLength);
        return result;
    }
    private static String sizedUpper(String field, String value, int maxLength) { String result = sized(field, value, maxLength); return result == null ? null : result.toUpperCase(); }
    private static String safe(Object value) { return value == null ? "" : value.toString(); }
}
