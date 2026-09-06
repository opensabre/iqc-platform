package io.github.opensabre.iqc.rule.dls;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compiles and evaluates the deterministic DLS 1.0 compatibility language. */
public final class DlsEngine {
    private static final int MAX_DOCUMENT_BYTES = 60_000;
    private static final int MAX_DEFINITIONS = 1_000;
    private static final int MAX_REFERENCE_DEPTH = 32;
    private static final Pattern BRACKET_REFERENCE = Pattern.compile("\\[((?:slot|rule)_[^]\\s]+)]");
    private static final Pattern RULE_REFERENCE = Pattern.compile("!?\\[?(rule_[\\p{L}\\p{N}_/\\-]+)]?");
    private static final Pattern WINDOW_SUFFIX = Pattern.compile("<([1-9][0-9]{0,2})>\\s*$");

    private DlsEngine() { }

    public static DlsRuleDocument read(ObjectMapper objectMapper, String expression) {
        if (expression == null || expression.isBlank()) throw new IllegalArgumentException("DLS 文档不能为空");
        if (expression.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES)
            throw new IllegalArgumentException("DLS 文档 UTF-8 内容不能超过 60000 字节");
        try {
            return objectMapper.readValue(expression, DlsRuleDocument.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("DLS 文档不是有效 JSON", exception);
        }
    }

    public static Compiled compile(ObjectMapper objectMapper, String expression) {
        return compile(read(objectMapper, expression));
    }

    public static Compiled compile(DlsRuleDocument document) {
        if (document == null || !"1.0".equals(document.languageVersion()))
            throw new IllegalArgumentException("仅支持 DLS languageVersion 1.0");
        if (document.definitions() == null || document.definitions().isEmpty())
            throw new IllegalArgumentException("DLS 至少需要一个定义");
        if (document.definitions().size() > MAX_DEFINITIONS)
            throw new IllegalArgumentException("DLS 定义不能超过 1000 条");
        if (document.entryName() == null || document.entryName().isBlank()
                || document.entryExpression() == null || document.entryExpression().isBlank())
            throw new IllegalArgumentException("DLS 缺少最终规则");

        Map<String, DlsRuleDocument.Definition> definitions = new LinkedHashMap<>();
        for (DlsRuleDocument.Definition definition : document.definitions()) {
            if (definition == null || definition.name() == null || definition.name().isBlank()
                    || definition.expression() == null || definition.expression().isBlank())
                throw new IllegalArgumentException("DLS 定义名称和表达式不能为空");
            if (!List.of("SLOT", "RULE").contains(normalizeKind(definition.kind())))
                throw new IllegalArgumentException("DLS 定义类型无效: " + definition.kind());
            if (definitions.putIfAbsent(definition.name(), definition) != null)
                throw new IllegalArgumentException("DLS 定义重复: " + definition.name());
        }
        validateReferences(definitions, document.entryExpression());
        detectCycles(definitions);

        Map<String, Pattern> rules = new LinkedHashMap<>();
        Map<String, String> expandedSlots = new HashMap<>();
        for (DlsRuleDocument.Definition definition : document.definitions()) {
            if (!"RULE".equals(normalizeKind(definition.kind()))) continue;
            String regex = expandRegex(definition.expression(), definitions, expandedSlots, new ArrayDeque<>());
            try {
                rules.put(definition.name(), Pattern.compile(regex));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("DLS 正则无效 " + definition.name() + ": " + exception.getMessage(), exception);
            }
        }
        // Parse once during publication validation so unsupported boolean syntax fails early.
        for (String clause : splitTopLevel(document.entryExpression(), ';')) validateEntryClause(clause, definitions.keySet());
        return new Compiled(document, Map.copyOf(rules));
    }

    public static Evaluation evaluate(Compiled compiled, List<ConversationMessage> messages) {
        if (messages == null || messages.isEmpty()) return new Evaluation(false, List.of(), "会话没有消息");
        List<ConversationMessage> ordered = messages.stream()
                .sorted(Comparator.comparing(message -> message.getSequenceNo() == null ? Integer.MAX_VALUE : message.getSequenceNo()))
                .toList();
        Map<String, List<Hit>> hits = new LinkedHashMap<>();
        Map<String, DlsRuleDocument.Definition> definitions = new LinkedHashMap<>();
        compiled.document().definitions().forEach(definition -> definitions.put(definition.name(), definition));
        for (var entry : compiled.rulePatterns().entrySet()) {
            DlsRuleDocument.Definition definition = definitions.get(entry.getKey());
            List<Hit> ruleHits = new ArrayList<>();
            for (int index = 0; index < ordered.size(); index++) {
                ConversationMessage message = ordered.get(index);
                if (!roleMatches(definition.targetRole(), message.getSpeakerRole())) continue;
                Matcher matcher = entry.getValue().matcher(message.getContent() == null ? "" : message.getContent());
                while (matcher.find()) {
                    ruleHits.add(new Hit(entry.getKey(), message.getId(), message.getSequenceNo(), index,
                            matcher.start(), matcher.end(), matcher.group()));
                    if (matcher.start() == matcher.end() && matcher.end() == message.getContent().length()) break;
                }
            }
            hits.put(entry.getKey(), List.copyOf(ruleHits));
        }
        for (String clause : splitTopLevel(compiled.document().entryExpression(), ';')) {
            ClauseEvaluation evaluation = evaluateClause(clause, hits, ordered.size());
            if (evaluation.hit()) return new Evaluation(true, evaluation.evidence(), "DLS 最终规则命中");
        }
        return new Evaluation(false, List.of(), "DLS 最终规则未命中");
    }

    private static String expandRegex(String expression, Map<String, DlsRuleDocument.Definition> definitions,
                                      Map<String, String> expandedSlots, Deque<String> stack) {
        Matcher matcher = BRACKET_REFERENCE.matcher(expression);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            DlsRuleDocument.Definition referenced = definitions.get(name);
            if (referenced == null) throw new IllegalArgumentException("DLS 引用不存在: " + name);
            if (!name.startsWith("slot_"))
                throw new IllegalArgumentException("子规则 " + stack.peekLast() + " 的正则体不能直接嵌入规则引用: " + name);
            String expanded = expandedSlots.get(name);
            if (expanded == null) {
                if (stack.size() >= MAX_REFERENCE_DEPTH) throw new IllegalArgumentException("DLS 引用深度超过 32");
                stack.addLast(name);
                expanded = expandRegex(referenced.expression(), definitions, expandedSlots, stack);
                stack.removeLast();
                expanded = alternatives(expanded);
                expandedSlots.put(name, expanded);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement("(?:" + expanded + ")"));
        }
        matcher.appendTail(result);
        // Legacy '~' joins two same-message regex fragments in source order.
        return alternatives(result.toString().replace("~", ""));
    }

    private static String alternatives(String expression) {
        List<String> alternatives = splitTopLevel(expression, ';');
        if (alternatives.size() == 1) return alternatives.get(0).trim();
        return alternatives.stream().map(String::trim).filter(value -> !value.isBlank())
                .reduce((left, right) -> left + "|" + right).map(value -> "(?:" + value + ")").orElse("");
    }

    private static void validateReferences(Map<String, DlsRuleDocument.Definition> definitions, String entryExpression) {
        Set<String> missingReferences = new LinkedHashSet<>();
        for (DlsRuleDocument.Definition definition : definitions.values()) {
            Matcher matcher = BRACKET_REFERENCE.matcher(definition.expression());
            while (matcher.find()) {
                String name = matcher.group(1);
                if (!definitions.containsKey(name)) missingReferences.add(name);
            }
        }
        Matcher entryReferences = RULE_REFERENCE.matcher(entryExpression);
        int count = 0;
        Set<String> invalidEntryReferences = new LinkedHashSet<>();
        while (entryReferences.find()) {
            count++;
            String name = entryReferences.group(1);
            DlsRuleDocument.Definition definition = definitions.get(name);
            if (definition == null) missingReferences.add(name);
            else if (!"RULE".equals(normalizeKind(definition.kind()))) invalidEntryReferences.add(name);
        }
        if (count == 0) throw new IllegalArgumentException("DLS 最终规则至少引用一个 rule_ 定义");
        if (!missingReferences.isEmpty())
            throw new IllegalArgumentException("DLS 引用不存在: " + String.join(", ", missingReferences));
        if (!invalidEntryReferences.isEmpty())
            throw new IllegalArgumentException("DLS 最终规则只能引用子规则: " + String.join(", ", invalidEntryReferences));
    }

    private static void detectCycles(Map<String, DlsRuleDocument.Definition> definitions) {
        Set<String> complete = new HashSet<>();
        Set<String> active = new LinkedHashSet<>();
        for (String name : definitions.keySet()) visit(name, definitions, complete, active);
    }

    private static void visit(String name, Map<String, DlsRuleDocument.Definition> definitions,
                              Set<String> complete, Set<String> active) {
        if (complete.contains(name)) return;
        if (!active.add(name)) throw new IllegalArgumentException("DLS 存在循环引用: " + String.join(" -> ", active) + " -> " + name);
        Matcher matcher = BRACKET_REFERENCE.matcher(definitions.get(name).expression());
        while (matcher.find()) visit(matcher.group(1), definitions, complete, active);
        active.remove(name);
        complete.add(name);
    }

    private static void validateEntryClause(String clause, Set<String> definitions) {
        String normalized = normalizeLogicalGroup(clause);
        for (String stage : splitTopLevel(normalized, '%'))
            new BooleanParser(normalizeLogicalGroup(stage), definitions::contains).parse();
    }

    private static ClauseEvaluation evaluateClause(String clause, Map<String, List<Hit>> hits, int messageCount) {
        String unwrapped = stripOuterParentheses(clause.trim());
        Matcher windowMatcher = WINDOW_SUFFIX.matcher(unwrapped);
        Integer window = windowMatcher.find() ? Integer.parseInt(windowMatcher.group(1)) : null;
        String normalized = normalizeLogicalGroup(unwrapped);
        List<String> stages = splitTopLevel(normalized, '%');
        if (stages.size() > 1) return evaluateSequence(stages, hits, messageCount);
        Set<String> positiveReferences = positiveReferences(normalized);
        if (window != null && !positiveReferences.isEmpty()) {
            for (String positive : positiveReferences) {
                for (Hit anchor : hits.getOrDefault(positive, List.of())) {
                    int position = anchor.messageIndex();
                    boolean result = new BooleanParser(normalized, name -> hits.getOrDefault(name, List.of()).stream()
                            .anyMatch(hit -> Math.abs(hit.messageIndex() - position) <= window)).parse();
                    if (result) return new ClauseEvaluation(true, evidenceFor(normalized, hits, position, window));
                }
            }
            return new ClauseEvaluation(false, List.of());
        }
        boolean result = new BooleanParser(normalized, name -> !hits.getOrDefault(name, List.of()).isEmpty()).parse();
        return new ClauseEvaluation(result, result ? evidenceFor(normalized, hits, null, null) : List.of());
    }

    private static ClauseEvaluation evaluateSequence(List<String> stages, Map<String, List<Hit>> hits, int messageCount) {
        int after = -1;
        List<Hit> evidence = new ArrayList<>();
        for (String stage : stages) {
            String stageSource = stripOuterParentheses(stage.trim());
            Matcher windowMatcher = WINDOW_SUFFIX.matcher(stageSource);
            Integer window = windowMatcher.find() ? Integer.parseInt(windowMatcher.group(1)) : null;
            String normalizedStage = normalizeLogicalGroup(stage);
            Set<String> positives = positiveReferences(normalizedStage);
            if (positives.isEmpty()) {
                int boundary = after;
                boolean absent = new BooleanParser(normalizedStage, name -> hits.getOrDefault(name, List.of()).stream()
                        .anyMatch(hit -> hit.messageIndex() > boundary)).parse();
                if (!absent) return new ClauseEvaluation(false, List.of());
                continue;
            }
            int matchedPosition = -1;
            Set<Integer> candidatePositions = new java.util.TreeSet<>();
            int sequenceBoundary = after;
            positives.forEach(name -> hits.getOrDefault(name, List.of()).stream()
                    .map(Hit::messageIndex).filter(position -> position > sequenceBoundary).forEach(candidatePositions::add));
            for (int position : candidatePositions) {
                int current = position;
                if (new BooleanParser(normalizedStage, name -> hits.getOrDefault(name, List.of()).stream()
                        .anyMatch(hit -> window == null ? hit.messageIndex() == current
                                : Math.abs(hit.messageIndex() - current) <= window)).parse()) {
                    matchedPosition = position;
                    evidence.addAll(evidenceFor(normalizedStage, hits, position, window == null ? 0 : window));
                    break;
                }
            }
            if (matchedPosition < 0) return new ClauseEvaluation(false, List.of());
            after = matchedPosition;
        }
        return new ClauseEvaluation(true, List.copyOf(new LinkedHashSet<>(evidence)));
    }

    private static List<Hit> evidenceFor(String expression, Map<String, List<Hit>> hits, Integer anchor, Integer window) {
        List<Hit> evidence = new ArrayList<>();
        for (String name : positiveReferences(expression)) {
            for (Hit hit : hits.getOrDefault(name, List.of())) {
                if (anchor == null || Math.abs(hit.messageIndex() - anchor) <= window) evidence.add(hit);
            }
        }
        return List.copyOf(new LinkedHashSet<>(evidence));
    }

    private static Set<String> positiveReferences(String expression) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = RULE_REFERENCE.matcher(expression);
        while (matcher.find()) if (!matcher.group().startsWith("!")) result.add(matcher.group(1));
        return result;
    }

    static List<String> splitTopLevel(String value, char separator) {
        List<String> result = new ArrayList<>();
        int parentheses = 0;
        int brackets = 0;
        int start = 0;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaped) { escaped = false; continue; }
            if (current == '\\') { escaped = true; continue; }
            if (current == '[') brackets++;
            else if (current == ']' && brackets > 0) brackets--;
            else if (brackets == 0 && current == '(') parentheses++;
            else if (brackets == 0 && current == ')' && parentheses > 0) parentheses--;
            else if (current == separator && parentheses == 0 && brackets == 0) {
                result.add(value.substring(start, index));
                start = index + 1;
            }
        }
        result.add(value.substring(start));
        return result;
    }

    private static String removeWindow(String value) {
        return WINDOW_SUFFIX.matcher(value).replaceFirst("").trim();
    }

    private static String normalizeLogicalGroup(String value) {
        String previous;
        String normalized = value.trim();
        do {
            previous = normalized;
            normalized = stripOuterParentheses(removeWindow(normalized));
        } while (!normalized.equals(previous));
        return normalized;
    }

    private static String stripOuterParentheses(String value) {
        String result = value.trim();
        while (result.length() >= 2 && result.charAt(0) == '(' && result.charAt(result.length() - 1) == ')') {
            int depth = 0;
            boolean wrapsAll = true;
            boolean escaped = false;
            for (int index = 0; index < result.length(); index++) {
                char current = result.charAt(index);
                if (escaped) { escaped = false; continue; }
                if (current == '\\') { escaped = true; continue; }
                if (current == '(') depth++;
                else if (current == ')') depth--;
                if (depth == 0 && index < result.length() - 1) { wrapsAll = false; break; }
            }
            if (!wrapsAll) break;
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private static String normalizeKind(String kind) {
        return kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean roleMatches(String expected, String actual) {
        if (expected == null || expected.isBlank() || "all".equalsIgnoreCase(expected)) return true;
        return expected.equalsIgnoreCase(actual);
    }

    public record Compiled(DlsRuleDocument document, Map<String, Pattern> rulePatterns) { }
    public record Hit(String definition, String messageId, Integer sequenceNo, int messageIndex,
                      int start, int end, String text) { }
    public record Evaluation(boolean hit, List<Hit> evidence, String reason) { }
    private record ClauseEvaluation(boolean hit, List<Hit> evidence) { }

    private interface ReferenceValue { boolean value(String name); }

    /** Small boolean parser for rule references; regex parsing remains isolated in definition compilation. */
    private static final class BooleanParser {
        private final String source;
        private final ReferenceValue references;
        private int index;

        private BooleanParser(String source, ReferenceValue references) {
            this.source = source;
            this.references = references;
        }

        boolean parse() {
            boolean value = or();
            whitespace();
            if (index != source.length()) throw new IllegalArgumentException("DLS 最终规则语法无效: " + source.substring(index));
            return value;
        }

        private boolean or() {
            boolean value = and();
            while (consume('|')) { boolean right = and(); value = value || right; }
            return value;
        }

        private boolean and() {
            boolean value = unary();
            while (consume('&')) { boolean right = unary(); value = value && right; }
            return value;
        }

        private boolean unary() {
            whitespace();
            if (consume('!')) return !unary();
            if (consume('(')) {
                boolean value = or();
                if (!consume(')')) throw new IllegalArgumentException("DLS 最终规则缺少右括号");
                return value;
            }
            String name = reference();
            if (name == null) throw new IllegalArgumentException("DLS 最终规则缺少 rule_ 引用: " + source.substring(index));
            return references.value(name);
        }

        private String reference() {
            whitespace();
            boolean bracketed = index < source.length() && source.charAt(index) == '[';
            if (bracketed) index++;
            int start = index;
            while (index < source.length()) {
                char current = source.charAt(index);
                if (Character.isLetterOrDigit(current) || current == '_' || current == '-' || current == '/') index++;
                else break;
            }
            String name = source.substring(start, index);
            if (!name.startsWith("rule_")) { index = bracketed ? start - 1 : start; return null; }
            if (bracketed && !consume(']')) throw new IllegalArgumentException("DLS 规则引用缺少右方括号: " + name);
            return name;
        }

        private boolean consume(char expected) {
            whitespace();
            if (index < source.length() && source.charAt(index) == expected) { index++; return true; }
            return false;
        }

        private void whitespace() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
        }
    }
}
