package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.fusion.FindingFusion;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Pure Finding-layer metrics over validated runtime output and frozen reviewed gold. No production writes. */
public final class FindingEvaluationMetrics {
    private FindingEvaluationMetrics() { }
    public static final String SCHEMA = "iqc-finding-evaluation-metrics-v1";
    public record Sample(GoldConversation gold, List<FindingFusion.Result> actual) {
        public Sample {
            Objects.requireNonNull(gold, "gold"); actual = List.copyOf(Objects.requireNonNull(actual, "actual"));
            if (actual.stream().map(FindingFusion.Result::code).distinct().count() != actual.size())
                throw new IllegalArgumentException("duplicate actual finding code");
        }
    }
    public record Counts(long knownGold, long uncertainGold, long resolved, long valueMatches, long stateMatches,
                         long truePositive, long falsePositive, long trueNegative, long falseNegative,
                         long missing, long errors, long conflicts, long typeMismatches,
                         long evidenceGold, long evidenceMatches) { }
    public record Summary(Counts counts, BigDecimal precision, BigDecimal recall, BigDecimal f1,
                          BigDecimal coverage, BigDecimal valueAccuracy, BigDecimal stateAccuracy,
                          BigDecimal evidenceAccuracy) { }
    public record Result(String schemaVersion, int sampleCount, Summary aggregate,
                         Map<String, Summary> byCode, long unlabelledOutputs) {
        public Result { byCode = Collections.unmodifiableMap(new LinkedHashMap<>(byCode)); }
    }

    public static Result evaluate(List<Sample> samples) {
        samples = List.copyOf(Objects.requireNonNull(samples, "samples"));
        if (samples.isEmpty()) throw new IllegalArgumentException("cannot evaluate an empty dataset");
        if (samples.stream().map(sample -> sample.gold().sourceSampleId()).distinct().count() != samples.size()
                || samples.stream().map(sample -> sample.gold().conversationId()).distinct().count() != samples.size())
            throw new IllegalArgumentException("duplicate evaluation sample or conversation");
        var overall = new Counter(); var counters = new LinkedHashMap<String, Counter>(); long unlabelled = 0;
        for (Sample sample : samples) {
            var actual = new LinkedHashMap<String, FindingFusion.Result>(); sample.actual().forEach(fact -> actual.put(fact.code(), fact));
            Set<String> labelled = sample.gold().findings().stream().map(GoldFinding::code).collect(Collectors.toSet());
            unlabelled += actual.keySet().stream().filter(code -> !labelled.contains(code)).count();
            for (GoldFinding gold : sample.gold().findings()) {
                Counter count = compare(gold, actual.get(gold.code()));
                overall.add(count); counters.computeIfAbsent(gold.code(), key -> new Counter()).add(count);
            }
        }
        var byCode = new LinkedHashMap<String, Summary>(); counters.forEach((code, value) -> byCode.put(code, value.summary()));
        return new Result(SCHEMA, samples.size(), overall.summary(), byCode, unlabelled);
    }

    private static Counter compare(GoldFinding gold, FindingFusion.Result actual) {
        var c = new Counter(); boolean knownGold = gold.state() == Finding.State.KNOWN;
        if (knownGold) c.known = 1; else c.uncertain = 1;
        if (actual == null || actual.state() == Finding.State.NOT_EVALUATED) c.missing = 1;
        if (actual != null && actual.state() == Finding.State.ERROR) c.errors = 1;
        if (actual != null && actual.state() == Finding.State.CONFLICT) c.conflicts = 1;
        boolean compatible = actual != null && gold.valueType() == actual.valueType();
        if (actual != null && !compatible) c.typeMismatches = 1;
        if (compatible && actual.state() == gold.state()) c.stateMatches = 1;
        boolean resolved = compatible && actual.state() == Finding.State.KNOWN && Finding.validValue(actual.valueType(), actual.value());
        if (knownGold && resolved) c.resolved = 1;
        boolean match = knownGold && resolved && equal(gold.value(), actual.value());
        if (match) c.valueMatches = 1;
        if (knownGold && gold.valueType() == Finding.ValueType.BOOLEAN) {
            boolean positive = gold.value().booleanValue();
            boolean predictedPositive = resolved && actual.value().isBoolean() && actual.value().booleanValue();
            boolean predictedNegative = resolved && actual.value().isBoolean() && !actual.value().booleanValue();
            if (positive) { if (predictedPositive) c.tp = 1; else c.fn = 1; }
            else if (predictedPositive) c.fp = 1;
            else if (predictedNegative) c.tn = 1;
            // A missing/unknown/error output on a negative gold is unresolved, never a true negative.
        } else if (knownGold) {
            // Typed non-boolean findings use micro classification over exact (code,value) assignments.
            if (match) c.tp = 1;
            else { c.fn = 1; if (resolved) c.fp = 1; }
        }
        if (!gold.evidenceMessageIds().isEmpty()) {
            c.evidenceGold = 1;
            if (actual != null) {
                Set<String> selected = Set.copyOf(actual.selectedIds());
                Set<String> messages = actual.rawFindings().stream().filter(fact -> selected.contains(fact.id()))
                        .flatMap(fact -> fact.evidence().stream()).filter(e -> e.type() == Finding.EvidenceType.CONVERSATION)
                        .map(Finding.Evidence::reference).collect(Collectors.toSet());
                if (messages.equals(Set.copyOf(gold.evidenceMessageIds()))) c.evidenceMatches = 1;
            }
        }
        return c;
    }
    private static boolean equal(JsonNode expected, JsonNode actual) {
        if (expected.isNumber() && actual.isNumber()) return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
        if (expected.isObject() && actual.isObject()) {
            if (expected.size() != actual.size()) return false;
            for (var field : expected.properties())
                if (!actual.has(field.getKey()) || !equal(field.getValue(), actual.get(field.getKey()))) return false;
            return true;
        }
        if (expected.isArray() && actual.isArray()) {
            if (expected.size() != actual.size()) return false;
            for (int i = 0; i < expected.size(); i++) if (!equal(expected.get(i), actual.get(i))) return false;
            return true;
        }
        return expected.equals(actual);
    }
    private static BigDecimal ratio(long numerator, long denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }
    private static final class Counter {
        long known, uncertain, resolved, valueMatches, stateMatches, tp, fp, tn, fn, missing, errors, conflicts,
                typeMismatches, evidenceGold, evidenceMatches;
        void add(Counter other) {
            known += other.known; uncertain += other.uncertain; resolved += other.resolved; valueMatches += other.valueMatches;
            stateMatches += other.stateMatches; tp += other.tp; fp += other.fp; tn += other.tn; fn += other.fn;
            missing += other.missing; errors += other.errors; conflicts += other.conflicts; typeMismatches += other.typeMismatches;
            evidenceGold += other.evidenceGold; evidenceMatches += other.evidenceMatches;
        }
        Summary summary() {
            var counts = new Counts(known, uncertain, resolved, valueMatches, stateMatches, tp, fp, tn, fn,
                    missing, errors, conflicts, typeMismatches, evidenceGold, evidenceMatches);
            return new Summary(counts, ratio(tp, tp + fp), ratio(tp, tp + fn), ratio(2 * tp, 2 * tp + fp + fn),
                    ratio(resolved, known), ratio(valueMatches, known), ratio(stateMatches, known + uncertain),
                    ratio(evidenceMatches, evidenceGold));
        }
    }
}
