package io.github.opensabre.iqc.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes deterministic IQC rule types and returns reproducible match evidence. */
public final class RuleMatcher {
    private static final int MAX_EXPRESSION_LENGTH = 16_000;

    private RuleMatcher() {
    }

    public static Match evaluate(ObjectMapper objectMapper, String ruleType, String expression,
                                 ConversationMessage message) {
        String type = normalizeType(ruleType);
        String content = message == null || message.getContent() == null ? "" : message.getContent();
        if (expression == null || expression.isBlank()) throw new IllegalArgumentException("规则表达式不能为空");
        return switch (type) {
            case "KEYWORD", "CONTAINS", "FORBIDDEN_CONTAINS" -> keyword(expression, content, false);
            case "REQUIRED_CONTAINS" -> keyword(expression, content, true);
            case "REGEX", "FORBIDDEN_REGEX" -> regex(expression, content, false);
            case "REQUIRED_REGEX" -> regex(expression, content, true);
            case "EQUALS" -> booleanMatch(content.equals(expression));
            case "NOT_EQUALS" -> booleanMatch(!content.equals(expression));
            case "STARTS_WITH" -> located(content, expression, content.startsWith(expression) ? 0 : -1);
            case "ENDS_WITH" -> located(content, expression,
                    content.endsWith(expression) ? content.length() - expression.length() : -1);
            case "STRUCTURED", "COMPOSITE" -> fromStructured(
                    StructuredConditionMatcher.evaluate(objectMapper, expression, message));
            default -> throw new IllegalArgumentException("不支持本地执行的规则类型: " + ruleType);
        };
    }

    public static void validate(String ruleType, String expression, ObjectMapper objectMapper) {
        String type = normalizeType(ruleType);
        if (expression == null || expression.isBlank()) throw new IllegalArgumentException("规则表达式不能为空");
        if (expression.length() > MAX_EXPRESSION_LENGTH) throw new IllegalArgumentException("规则表达式不能超过 16000 字符");
        switch (type) {
            case "REGEX", "FORBIDDEN_REGEX", "REQUIRED_REGEX" -> Pattern.compile(expression);
            case "STRUCTURED", "COMPOSITE" -> StructuredConditionMatcher.validate(objectMapper, expression);
            case "KEYWORD", "CONTAINS", "FORBIDDEN_CONTAINS", "REQUIRED_CONTAINS",
                    "EQUALS", "NOT_EQUALS", "STARTS_WITH", "ENDS_WITH", "LLM" -> { }
            default -> throw new IllegalArgumentException("不支持的规则类型: " + ruleType);
        }
    }

    private static Match keyword(String expression, String content, boolean invert) {
        List<String> keywords = Arrays.stream(expression.split("[|,，\\n]+"))
                .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
        if (keywords.isEmpty()) throw new IllegalArgumentException("至少配置一个关键词");
        for (String keyword : keywords) {
            int start = content.indexOf(keyword);
            if (start >= 0) return invert ? Match.noMatch() : located(content, keyword, start);
        }
        return invert ? new Match(true, null, -1, -1) : Match.noMatch();
    }

    private static Match regex(String expression, String content, boolean invert) {
        Matcher matcher = Pattern.compile(expression).matcher(content);
        if (!matcher.find()) return invert ? new Match(true, null, -1, -1) : Match.noMatch();
        return invert ? Match.noMatch() : new Match(true, matcher.group(), matcher.start(), matcher.end());
    }

    private static Match located(String content, String expected, int start) {
        return start < 0 ? Match.noMatch() : new Match(true, expected, start, start + expected.length());
    }

    private static Match booleanMatch(boolean hit) {
        return hit ? new Match(true, null, -1, -1) : Match.noMatch();
    }

    private static Match fromStructured(StructuredConditionMatcher.Match match) {
        return new Match(match.hit(), match.text(), match.start(), match.end());
    }

    private static String normalizeType(String ruleType) {
        return ruleType == null || ruleType.isBlank() ? "KEYWORD" : ruleType.trim().toUpperCase();
    }

    public record Match(boolean hit, String text, int start, int end) {
        public static Match noMatch() { return new Match(false, null, -1, -1); }
    }
}
