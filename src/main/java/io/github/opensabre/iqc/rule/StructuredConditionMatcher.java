package io.github.opensabre.iqc.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** 对结构化条件执行纯本地、可复现的递归求值。表达式不允许执行任意脚本。 */
public final class StructuredConditionMatcher {
    private StructuredConditionMatcher() {
    }

    public static Match evaluate(ObjectMapper objectMapper, String expression, ConversationMessage message) {
        try {
            JsonNode root = objectMapper.readTree(expression);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("结构化条件必须是 JSON 对象");
            return evaluateNode(root, message);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("结构化条件不是合法 JSON", exception);
        }
    }

    /** Validates every branch without requiring a runtime message or short-circuiting evaluation. */
    public static void validate(ObjectMapper objectMapper, String expression) {
        try {
            JsonNode root = objectMapper.readTree(expression);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("结构化条件必须是 JSON 对象");
            validateNode(root);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("结构化条件不是合法 JSON", exception);
        }
    }

    private static void validateNode(JsonNode node) {
        if (node.has("all")) { validateGroup(node.get("all")); return; }
        if (node.has("any")) { validateGroup(node.get("any")); return; }
        if (node.has("not")) { validateNode(requiredObject(node.get("not"), "not")); return; }
        String field = requiredText(node, "field");
        if (!List.of("content", "speakerRole", "sequenceNo", "relativeTime").contains(field))
            throw new IllegalArgumentException("不支持的结构化条件字段: " + field);
        String operator = requiredText(node, "operator").toLowerCase();
        if (!List.of("equals", "eq", "not_equals", "neq", "contains", "not_contains", "contains_any",
                "contains_all", "not_contains_any", "starts_with", "ends_with", "regex", "not_regex",
                "gt", "gte", "lt", "lte", "length_gt", "length_gte", "length_lt", "length_lte").contains(operator))
            throw new IllegalArgumentException("不支持的结构化条件操作符: " + operator);
        JsonNode value = node.get("value");
        if (value == null || value.isNull()) throw new IllegalArgumentException("结构化条件缺少 value");
        String expected = value.isTextual() ? value.asText() : value.toString();
        if (List.of("regex", "not_regex").contains(operator)) java.util.regex.Pattern.compile(expected);
        if (List.of("contains_any", "contains_all", "not_contains_any").contains(operator)) valueList(expected);
        if (List.of("gt", "gte", "lt", "lte", "length_gt", "length_gte", "length_lt", "length_lte").contains(operator))
            new BigDecimal(expected);
    }

    private static void validateGroup(JsonNode children) {
        if (children == null || !children.isArray() || children.isEmpty())
            throw new IllegalArgumentException("结构化条件组必须是非空数组");
        if (children.size() > 100) throw new IllegalArgumentException("单个条件组最多包含 100 个节点");
        children.forEach(child -> validateNode(requiredObject(child, "condition")));
    }

    private static Match evaluateNode(JsonNode node, ConversationMessage message) {
        if (node.has("all")) return evaluateGroup(node.get("all"), message, true);
        if (node.has("any")) return evaluateGroup(node.get("any"), message, false);
        if (node.has("not")) {
            Match child = evaluateNode(requiredObject(node.get("not"), "not"), message);
            return child.hit() ? new Match(false, null, -1, -1) : new Match(true, null, -1, -1);
        }
        String field = requiredText(node, "field");
        String operator = requiredText(node, "operator").toLowerCase();
        JsonNode value = node.get("value");
        if (value == null || value.isNull()) throw new IllegalArgumentException("结构化条件缺少 value");
        String actual = fieldValue(field, message);
        String expected = value.isTextual() ? value.asText() : value.toString();
        return compare(actual, expected, operator);
    }

    private static Match evaluateGroup(JsonNode children, ConversationMessage message, boolean all) {
        if (children == null || !children.isArray() || children.isEmpty()) throw new IllegalArgumentException("结构化条件组必须是非空数组");
        Match firstHit = new Match(false, null, -1, -1);
        for (JsonNode child : children) {
            Match current = evaluateNode(requiredObject(child, "condition"), message);
            if (current.hit() && !firstHit.hit()) firstHit = current;
            if (all && !current.hit()) return new Match(false, null, -1, -1);
            if (!all && current.hit()) return current;
        }
        return all ? firstHit : new Match(false, null, -1, -1);
    }

    private static Match compare(String actual, String expected, String operator) {
        if (actual == null) throw new IllegalArgumentException("结构化条件字段不支持或消息缺少字段");
        return switch (operator) {
            case "equals", "eq" -> booleanMatch(actual.equals(expected));
            case "not_equals", "neq" -> booleanMatch(!actual.equals(expected));
            case "contains" -> textMatch(actual, expected, actual.indexOf(expected));
            case "not_contains" -> booleanMatch(!actual.contains(expected));
            case "contains_any" -> containsMany(actual, valueList(expected), false, false);
            case "contains_all" -> containsMany(actual, valueList(expected), true, false);
            case "not_contains_any" -> containsMany(actual, valueList(expected), false, true);
            case "starts_with" -> booleanMatch(actual.startsWith(expected));
            case "ends_with" -> booleanMatch(actual.endsWith(expected));
            case "regex" -> regexMatch(actual, expected);
            case "not_regex" -> booleanMatch(!java.util.regex.Pattern.compile(expected).matcher(actual).find());
            case "length_gt", "length_gte", "length_lt", "length_lte" ->
                    numericMatch(String.valueOf(actual.length()), expected, operator.substring("length_".length()));
            case "gt", "gte", "lt", "lte" -> numericMatch(actual, expected, operator);
            default -> throw new IllegalArgumentException("不支持的结构化条件操作符: " + operator);
        };
    }

    private static List<String> valueList(String expected) {
        List<String> values = new ArrayList<>();
        for (String value : expected.replace("[", "").replace("]", "").replace("\"", "").split("[,|，]")) {
            if (!value.trim().isBlank()) values.add(value.trim());
        }
        if (values.isEmpty()) throw new IllegalArgumentException("多值条件至少需要一个值");
        return values;
    }

    private static Match containsMany(String actual, List<String> values, boolean requireAll, boolean negateAny) {
        Match first = new Match(false, null, -1, -1);
        for (String value : values) {
            int start = actual.indexOf(value);
            if (start >= 0 && !first.hit()) first = textMatch(actual, value, start);
            if (requireAll && start < 0) return new Match(false, null, -1, -1);
            if (!requireAll && start >= 0) return negateAny ? new Match(false, null, -1, -1) : first;
        }
        if (negateAny) return new Match(true, null, -1, -1);
        return requireAll ? first : new Match(false, null, -1, -1);
    }

    private static Match regexMatch(String actual, String expected) {
        var matcher = java.util.regex.Pattern.compile(expected).matcher(actual);
        return matcher.find() ? new Match(true, matcher.group(), matcher.start(), matcher.end()) : new Match(false, null, -1, -1);
    }

    private static Match numericMatch(String actual, String expected, String operator) {
        try {
            int comparison = new BigDecimal(actual).compareTo(new BigDecimal(expected));
            boolean hit = switch (operator) { case "gt" -> comparison > 0; case "gte" -> comparison >= 0; case "lt" -> comparison < 0; default -> comparison <= 0; };
            return booleanMatch(hit);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("结构化条件数值字段格式无效", exception);
        }
    }

    private static Match textMatch(String actual, String expected, int start) {
        return start >= 0 ? new Match(true, expected, start, start + expected.length()) : new Match(false, null, -1, -1);
    }

    private static Match booleanMatch(boolean hit) { return new Match(hit, null, -1, -1); }

    private static String fieldValue(String field, ConversationMessage message) {
        return switch (field) {
            case "content" -> message.getContent();
            case "speakerRole" -> message.getSpeakerRole();
            case "sequenceNo" -> message.getSequenceNo() == null ? null : message.getSequenceNo().toString();
            case "relativeTime" -> message.getRelativeTime() == null ? null : message.getRelativeTime().toString();
            default -> throw new IllegalArgumentException("不支持的结构化条件字段: " + field);
        };
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isBlank()) throw new IllegalArgumentException("结构化条件缺少 " + field);
        return value;
    }

    private static JsonNode requiredObject(JsonNode node, String field) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("结构化条件 " + field + " 必须是对象");
        return node;
    }

    public record Match(boolean hit, String text, int start, int end) { }
}
