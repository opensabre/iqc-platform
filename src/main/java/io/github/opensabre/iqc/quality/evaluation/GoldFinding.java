package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.finding.Finding;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Human expectation, without invented engine provenance or confidence. */
public record GoldFinding(String code, Finding.ValueType valueType, Finding.State state,
                          @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.ExactValueDeserializer.class) JsonNode value, List<String> evidenceMessageIds) {
    public GoldFinding {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,63}"))
            throw new IllegalArgumentException("invalid gold finding code");
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(state, "state");
        if (state == Finding.State.KNOWN) {
            if (value == null || value.isNull()) throw new IllegalArgumentException("known gold requires a value");
            if (!Finding.validValue(valueType, value)) throw new IllegalArgumentException("gold value differs from declared type");
        } else if (value != null && !value.isNull()) {
            throw new IllegalArgumentException("uncertain gold cannot carry a canonical value");
        }
        value = value == null || value.isNull() ? null : value.deepCopy();
        evidenceMessageIds = evidenceMessageIds == null ? List.of() : List.copyOf(evidenceMessageIds);
        if (evidenceMessageIds.size() > 10000 || evidenceMessageIds.stream().anyMatch(String::isBlank)
                || evidenceMessageIds.stream().distinct().count() != evidenceMessageIds.size())
            throw new IllegalArgumentException("invalid gold evidence references");
    }

    @Override public JsonNode value() { return value == null ? null : value.deepCopy(); }

    public void validateEvidence(Set<String> frozenMessageIds) {
        if (!Objects.requireNonNull(frozenMessageIds, "frozenMessageIds").containsAll(evidenceMessageIds))
            throw new IllegalArgumentException("gold evidence is outside the frozen conversation");
    }
}
