package io.github.opensabre.iqc.finding;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Engine-neutral immutable observation. A missing, failed or conflicting value is never false. */
public record Finding(String id, String code, String category, ValueType valueType, JsonNode value,
                      State state, BigDecimal confidence, Severity severity, String reason,
                      List<Evidence> evidence, Source source) {
    public static final String SCHEMA = "iqc-finding-v1";
    public enum ValueType { BOOLEAN, STRING, NUMBER, ENUM, OBJECT }
    public enum State { KNOWN, UNKNOWN, CONFLICT, ERROR, NOT_EVALUATED }
    public enum Severity { NONE, LOW, MEDIUM, HIGH, CRITICAL }
    public enum EvidenceType { CONVERSATION, RULE_MATCH, METRIC, KNOWLEDGE, BUSINESS_FIELD, EXTERNAL_RESULT }
    public record Evidence(String id, EvidenceType type, String reference, JsonNode details) {
        public Evidence {
            require(id, "evidence id"); Objects.requireNonNull(type, "evidence type");
            require(reference, "evidence reference");
            details = details == null ? null : details.deepCopy();
        }
        @Override public JsonNode details() { return details == null ? null : details.deepCopy(); }
    }
    public record Source(String nodeId, String engineType, String engineVersion, int runIndex, String inputHash) {
        public Source {
            require(nodeId, "source node"); require(engineType, "source engine");
            require(engineVersion, "source version");
            if (runIndex < 1) throw new IllegalArgumentException("runIndex must be positive");
        }
    }
    public Finding {
        require(id, "finding id"); require(code, "finding code"); require(category, "finding category");
        Objects.requireNonNull(valueType, "value type"); Objects.requireNonNull(state, "finding state");
        Objects.requireNonNull(severity, "severity"); Objects.requireNonNull(source, "source");
        if (confidence != null && (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0))
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        if (state == State.KNOWN && !valid(valueType, value))
            throw new IllegalArgumentException("known finding requires a value matching its type");
        if (state != State.KNOWN && value != null && !value.isNull())
            throw new IllegalArgumentException("incomplete finding cannot carry a canonical value");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.stream().map(Evidence::id).distinct().count() != evidence.size())
            throw new IllegalArgumentException("duplicate evidence id");
        value = value == null || value.isNull() ? null : value.deepCopy();
    }
    @Override public JsonNode value() { return value == null ? null : value.deepCopy(); }
    private static boolean valid(ValueType type, JsonNode value) {
        if (value == null || value.isNull()) return false;
        return switch (type) {
            case BOOLEAN -> value.isBoolean();
            case STRING, ENUM -> value.isTextual();
            case NUMBER -> value.isNumber();
            case OBJECT -> value.isObject();
        };
    }
    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
