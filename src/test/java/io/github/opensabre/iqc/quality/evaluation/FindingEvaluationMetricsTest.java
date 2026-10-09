package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.fusion.FindingFusion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class FindingEvaluationMetricsTest {
    private final ObjectMapper json = new ObjectMapper();
    private JsonNode value(Object value) { return value == null ? null : json.valueToTree(value); }
    private GoldFinding gold(Finding.ValueType type, Object value) {
        return new GoldFinding("FACT", type, Finding.State.KNOWN, value(value), List.of());
    }
    private FindingFusion.Result actual(Finding.ValueType type, Finding.State state, Object value) {
        return new FindingFusion.Result("FACT", type, state, value(value), Finding.Severity.NONE,
                List.of(), List.of(), List.of(), List.of(), null, "policy", 1);
    }
    private FindingEvaluationMetrics.Sample sample(String id, GoldFinding gold, FindingFusion.Result... actual) {
        var conversation = new GoldConversation(GoldConversation.SCHEMA, id, "conversation-" + id,
                List.of(new GoldConversation.Message("m1", "agent", "text", 1, null)), null, List.of(gold),
                "annotator", "reviewer", "candidate", List.of(), GoldConversation.Difficulty.BASIC, null);
        return new FindingEvaluationMetrics.Sample(conversation, List.of(actual));
    }
    @Test
    void booleanConfusionMatrixUsesAllGoldIncludingMissingPositive() {
        var metrics = FindingEvaluationMetrics.evaluate(List.of(
                sample("tp", gold(Finding.ValueType.BOOLEAN, true), actual(Finding.ValueType.BOOLEAN, Finding.State.KNOWN, true)),
                sample("fp", gold(Finding.ValueType.BOOLEAN, false), actual(Finding.ValueType.BOOLEAN, Finding.State.KNOWN, true)),
                sample("tn", gold(Finding.ValueType.BOOLEAN, false), actual(Finding.ValueType.BOOLEAN, Finding.State.KNOWN, false)),
                sample("fn", gold(Finding.ValueType.BOOLEAN, true))));
        var summary = metrics.aggregate(); var counts = summary.counts();
        assertThat(counts.truePositive()).isEqualTo(1); assertThat(counts.falsePositive()).isEqualTo(1);
        assertThat(counts.trueNegative()).isEqualTo(1); assertThat(counts.falseNegative()).isEqualTo(1);
        assertThat(counts.missing()).isEqualTo(1); assertThat(counts.knownGold()).isEqualTo(4);
        assertThat(summary.precision()).isEqualByComparingTo("0.5");
        assertThat(summary.recall()).isEqualByComparingTo("0.5");
        assertThat(summary.f1()).isEqualByComparingTo("0.5");
        assertThat(summary.coverage()).isEqualByComparingTo("0.75");
        assertThat(summary.valueAccuracy()).isEqualByComparingTo("0.5");
        assertThat(metrics.byCode().get("FACT")).isEqualTo(summary);
    }
    @ParameterizedTest
    @EnumSource(value = Finding.State.class, names = {"UNKNOWN", "ERROR", "CONFLICT", "NOT_EVALUATED"})
    void unresolvedNegativeGoldIsNeverATrueNegative(Finding.State state) {
        var sample = sample("negative", gold(Finding.ValueType.BOOLEAN, false), actual(Finding.ValueType.BOOLEAN, state, null));
        var summary = FindingEvaluationMetrics.evaluate(List.of(sample)).aggregate();
        assertThat(summary.counts().trueNegative()).isZero();
        assertThat(summary.counts().resolved()).isZero();
        assertThat(summary.coverage()).isEqualByComparingTo("0");
        assertThat(summary.f1()).isNull();
        assertThat(FindingEvaluationMetrics.matchesGold(sample)).isFalse();
    }
    @Test
    void typedMismatchContributesFalsePositiveAndFalseNegative() {
        var sample = sample("typed", gold(Finding.ValueType.STRING, "expected"), actual(Finding.ValueType.STRING, Finding.State.KNOWN, "other"));
        var summary = FindingEvaluationMetrics.evaluate(List.of(sample)).aggregate();
        assertThat(summary.counts().falsePositive()).isEqualTo(1);
        assertThat(summary.counts().falseNegative()).isEqualTo(1);
        assertThat(summary.f1()).isEqualByComparingTo("0");
        assertThat(FindingEvaluationMetrics.matchesGold(sample)).isFalse();
    }
    @Test
    void numericAndNestedObjectEqualityIgnoresNumericScaleButPreservesArrayOrder() throws Exception {
        var expected = json.readTree("{\"nested\":{\"value\":1},\"list\":[1,2]}");
        var equivalent = json.readTree("{\"list\":[1.0,2.00],\"nested\":{\"value\":1.000}}");
        assertThat(FindingEvaluationMetrics.matchesGold(sample("object", gold(Finding.ValueType.OBJECT, expected),
                actual(Finding.ValueType.OBJECT, Finding.State.KNOWN, equivalent)))).isTrue();
        assertThat(FindingEvaluationMetrics.matchesGold(sample("number", gold(Finding.ValueType.NUMBER, 1),
                actual(Finding.ValueType.NUMBER, Finding.State.KNOWN, 1.0)))).isTrue();
        assertThat(FindingEvaluationMetrics.matchesGold(sample("order", gold(Finding.ValueType.OBJECT, expected),
                actual(Finding.ValueType.OBJECT, Finding.State.KNOWN, json.readTree("{\"nested\":{\"value\":1},\"list\":[2,1]}"))))).isFalse();
    }
    @Test
    void uncertainGoldHasStateMetricWithoutInventingClassificationDenominators() {
        var gold = new GoldFinding("FACT", Finding.ValueType.BOOLEAN, Finding.State.UNKNOWN, null, List.of());
        var sample = sample("uncertain", gold, actual(Finding.ValueType.BOOLEAN, Finding.State.UNKNOWN, null));
        var summary = FindingEvaluationMetrics.evaluate(List.of(sample)).aggregate();
        assertThat(summary.counts().uncertainGold()).isEqualTo(1);
        assertThat(summary.counts().knownGold()).isZero();
        assertThat(summary.stateAccuracy()).isEqualByComparingTo("1");
        assertThat(summary.coverage()).isNull(); assertThat(summary.f1()).isNull();
        assertThat(FindingEvaluationMetrics.matchesGold(sample)).isTrue();
    }
    @Test
    void incompatibleTypeCannotMatchEvenWhenStateMatches() {
        var sample = sample("type", gold(Finding.ValueType.BOOLEAN, true), actual(Finding.ValueType.STRING, Finding.State.KNOWN, "true"));
        var counts = FindingEvaluationMetrics.evaluate(List.of(sample)).aggregate().counts();
        assertThat(counts.typeMismatches()).isEqualTo(1); assertThat(counts.resolved()).isZero();
        assertThat(counts.falseNegative()).isEqualTo(1); assertThat(FindingEvaluationMetrics.matchesGold(sample)).isFalse();
    }
    @Test
    void unlabelledOutputIsReportedSeparatelyAndDuplicateInputIsRejected() {
        var extra = new FindingFusion.Result("EXTRA", Finding.ValueType.BOOLEAN, Finding.State.KNOWN, value(true),
                Finding.Severity.NONE, List.of(), List.of(), List.of(), List.of(), null, "policy", 1);
        var sample = sample("extra", gold(Finding.ValueType.BOOLEAN, true), actual(Finding.ValueType.BOOLEAN, Finding.State.KNOWN, true), extra);
        var metrics = FindingEvaluationMetrics.evaluate(List.of(sample));
        assertThat(metrics.unlabelledOutputs()).isEqualTo(1);
        assertThat(metrics.aggregate().counts().knownGold()).isEqualTo(1);
        assertThatThrownBy(() -> FindingEvaluationMetrics.evaluate(List.of(sample, sample))).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> FindingEvaluationMetrics.evaluate(List.of())).hasMessageContaining("empty");
        assertThatThrownBy(() -> new FindingEvaluationMetrics.Sample(sample.gold(), List.of(extra, extra))).hasMessageContaining("duplicate");
    }
    @Test
    void onlySelectedConversationEvidenceSatisfiesGold() {
        var expected = new GoldFinding("FACT", Finding.ValueType.BOOLEAN, Finding.State.KNOWN, value(true), List.of("m1"));
        var source = new Finding.Source("node", "RULE", "v1", 1, "hash");
        var selected = new Finding("selected", "FACT", "category", Finding.ValueType.BOOLEAN, value(true),
                Finding.State.KNOWN, null, Finding.Severity.NONE, null,
                List.of(new Finding.Evidence("e1", Finding.EvidenceType.CONVERSATION, "m1", null)), source);
        var rejected = new Finding("rejected", "FACT", "category", Finding.ValueType.BOOLEAN, value(true),
                Finding.State.KNOWN, null, Finding.Severity.NONE, null,
                List.of(new Finding.Evidence("e2", Finding.EvidenceType.CONVERSATION, "other", null)), source);
        var output = new FindingFusion.Result("FACT", Finding.ValueType.BOOLEAN, Finding.State.KNOWN, value(true),
                Finding.Severity.NONE, List.of(selected, rejected), List.of("selected"), List.of("rejected"),
                List.of(), null, "policy", 1);
        assertThat(FindingEvaluationMetrics.matchesGold(sample("evidence", expected, output))).isTrue();
        var wrongSelection = new FindingFusion.Result("FACT", Finding.ValueType.BOOLEAN, Finding.State.KNOWN, value(true),
                Finding.Severity.NONE, List.of(selected, rejected), List.of("rejected"), List.of("selected"),
                selected.evidence(), null, "policy", 1);
        var mismatch = sample("wrong", expected, wrongSelection);
        assertThat(FindingEvaluationMetrics.matchesGold(mismatch)).isFalse();
        assertThat(FindingEvaluationMetrics.evaluate(List.of(mismatch)).aggregate().evidenceAccuracy()).isEqualByComparingTo("0");
    }

}
