package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.fusion.FindingFusion;
import io.github.opensabre.iqc.label.FindingLabelPolicy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LabelEvaluationMetricsTest {
    final ObjectMapper json = new ObjectMapper();
    FindingLabelPolicy policy() throws Exception {
        return new FindingLabelPolicy(FindingLabelPolicy.SCHEMA,
                List.of(new FindingLabelPolicy.Binding("label/a", 1, "amount/~", "AMOUNT")),
                json.readTree("""
                {"schemaVersion":"2.0","labels":[{"id":"label/a","versionNo":1,"name":"Amount",
                  "targetRole":"all","values":[{"valueCode":"amount/~","valueType":"PERCENTAGE"}]}]}
                """));
    }
    JsonNode number(String value) { return DecimalNode.valueOf(new BigDecimal(value)); }
    FindingFusion.Result output(String value, Finding.State state) {
        var node = state == Finding.State.KNOWN ? number(value) : null;
        var finding = new Finding("f1", "AMOUNT", "BUSINESS", Finding.ValueType.NUMBER, node, state,
                null, Finding.Severity.LOW, "controlled", List.of(), new Finding.Source("node", "LLM", "v1", 1, "legacy"));
        return new FindingFusion.Result("AMOUNT", Finding.ValueType.NUMBER, state, node, Finding.Severity.LOW,
                List.of(finding), state == Finding.State.KNOWN ? List.of("f1") : List.of(), List.of(), List.of(), "controlled", "fusion", 1);
    }
    @Test void exactNumericValuesAndEscapedAddressesAreCompared() throws Exception {
        var gold = Map.of("/label~1a/amount~1~0", number("1.000000000000000001"));
        var equal = LabelEvaluationMetrics.evaluate(gold, policy(), List.of(output("1.0000000000000000010", Finding.State.KNOWN)));
        assertThat(equal).isEqualTo(new LabelEvaluationMetrics.Counts(1, 1, 1));
        assertThat(equal.matchesAll()).isTrue();
        var different = LabelEvaluationMetrics.evaluate(gold, policy(), List.of(output("1.000000000000000002", Finding.State.KNOWN)));
        assertThat(different).isEqualTo(new LabelEvaluationMetrics.Counts(1, 1, 0));
        assertThat(different.matchesAll()).isFalse();
    }
    @Test void unresolvedStatesRetainGoldWithoutInventingAccuracy() throws Exception {
        for (var state : Finding.State.values()) {
            if (state == Finding.State.KNOWN) continue;
            var counts = LabelEvaluationMetrics.evaluate(Map.of("/label~1a/amount~1~0", number("1")), policy(), List.of(output("1", state)));
            assertThat(counts).isEqualTo(new LabelEvaluationMetrics.Counts(1, 0, 0));
            assertThat(counts.matchesAll()).isFalse();
        }
    }
    @Test void missingSourcesLegacyKeysAndMissingPolicyAreUnmeasured() throws Exception {
        var gold = Map.of("/label~1a/amount~1~0", number("1"), "Amount", number("1"));
        assertThat(LabelEvaluationMetrics.evaluate(gold, policy(), List.of())).isEqualTo(new LabelEvaluationMetrics.Counts(2, 0, 0));
        assertThat(LabelEvaluationMetrics.evaluate(gold, null, List.of(output("1", Finding.State.KNOWN))))
                .isEqualTo(new LabelEvaluationMetrics.Counts(2, 0, 0));
        assertThat(LabelEvaluationMetrics.evaluate(gold, policy(), List.of(output("1", Finding.State.KNOWN))))
                .isEqualTo(new LabelEvaluationMetrics.Counts(2, 1, 1));
    }
    @Test void noGoldIsVacuouslyMatchedAndNeverCountsOutputs() throws Exception {
        var counts = LabelEvaluationMetrics.evaluate(Map.of(), policy(), List.of(output("1", Finding.State.KNOWN)));
        assertThat(counts).isEqualTo(new LabelEvaluationMetrics.Counts(0, 0, 0));
        assertThat(counts.matchesAll()).isTrue();
    }
}
