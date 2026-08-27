package io.github.opensabre.iqc.result.llm;

import java.util.regex.Pattern;

/** 在模型边界前做最小必要脱敏，避免将常见联系方式和证件号直接发送给模型。 */
public final class LlmTextSanitizer {
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final Pattern WECHAT = Pattern.compile("(?i)(微信|wechat|wx)[：:=\\s]*[a-z][-_a-z0-9]{5,19}");

    private LlmTextSanitizer() {
    }

    public static String sanitize(String content) {
        if (content == null || content.isBlank()) return content;
        String sanitized = PHONE.matcher(content).replaceAll("[手机号]");
        sanitized = EMAIL.matcher(sanitized).replaceAll("[邮箱]");
        sanitized = ID_CARD.matcher(sanitized).replaceAll("[证件号]");
        sanitized = BANK_CARD.matcher(sanitized).replaceAll("[银行卡号]");
        return WECHAT.matcher(sanitized).replaceAll("[微信号]");
    }
}
