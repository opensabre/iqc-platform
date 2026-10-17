package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Optional business-layer expectations. Absent dimensions are unlabelled, never assumed negative. */
public record GoldDecision(BigDecimal score, ScoreStatus scoreStatus, @com.fasterxml.jackson.databind.annotation.JsonDeserialize(contentUsing=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.ExactValueDeserializer.class) Map<String, JsonNode> labels, Risk risk) {
    public enum ScoreStatus { FINAL, PENDING, NOT_APPLICABLE }
    public enum Risk { NONE, LOW, MEDIUM, HIGH, CRITICAL, UNKNOWN }
    public GoldDecision {
        if (score != null && (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(new BigDecimal("100")) > 0))
            throw new IllegalArgumentException("gold score must be between 0 and 100");
        if ((scoreStatus == ScoreStatus.FINAL) != (score != null))
            throw new IllegalArgumentException("only final gold scores carry a numeric value");
        labels = copy(labels);
        if (labels.size() > 1000) throw new IllegalArgumentException("too many gold labels");
    }
    @Override public Map<String, JsonNode> labels() { return copy(labels); }
    private static Map<String, JsonNode> copy(Map<String, JsonNode> values) {
        var result = new LinkedHashMap<String, JsonNode>();
        if (values != null) values.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > 128 || value == null || value.isNull())
                throw new IllegalArgumentException("invalid gold label");
            result.put(key, value.deepCopy());
        });
        return Collections.unmodifiableMap(result);
    }
}
