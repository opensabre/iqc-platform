package io.github.opensabre.iqc.scoring;

import org.junit.jupiter.api.Test;
import java.util.List;
import static io.github.opensabre.iqc.scoring.InspectionScoring.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Verifies advanced business scoring without invoking detection again. */
class AdvancedInspectionScoringTest {
    @Test
    void tinyAdjustmentBelowHalfCentNeverBecomesHalfCentThroughIntermediateRounding() {
        var advanced = new Advanced(Advanced.SCHEMA, "0", "100",
                List.of(new ItemAdjustment("a", "1", "0.004999999999999999", "0", null, null)),
                List.of(), List.of());
        var policy = new Policy(ADVANCED_VERSION, Mode.POINTS, 100, 50,
                List.of(new Item("a", 1, false), new Item("b", 1, false)), advanced);
        var result = evaluate(policy, List.of(new Decision("a", ItemStatus.PASS), new Decision("b", ItemStatus.FAIL)));
        assertThat(result.finalScore()).isEqualByComparingTo("50.00");
        assertThat(result.advanced().rawNumerator()).isEqualByComparingTo("100.009999999999999998");
        assertThat(result.advanced().rawDenominator()).isEqualByComparingTo("2");
    }

    @Test
    void frozenBusinessConditionDistinguishesMissingNonmatchingAndMatchingInput() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var condition = new io.github.opensabre.iqc.action.ActionPolicy.Condition(
                io.github.opensabre.iqc.action.ActionPolicy.Kind.BUSINESS_EQUALS,
                "/amount", null, List.of(), "99.999999999999999999");
        var advanced = new Advanced(Advanced.SCHEMA, "0", "100",
                List.of(new ItemAdjustment("a", "1.000000000000000001", "0", "0", null, condition)),
                List.of(), List.of());
        var policy = new Policy(ADVANCED_VERSION, Mode.DEDUCTION, 100, 60,
                List.of(new Item("a", 10, false)), advanced);
        var frozen = json.writeValueAsString(policy);
        var restored = json.readValue(frozen, Policy.class);
        assertThat(json.writeValueAsString(restored)).isEqualTo(frozen);
        var decisions = List.of(new Decision("a", ItemStatus.FAIL));
        var missing = evaluate(restored, decisions);
        assertThat(missing.scoreStatus()).isEqualTo(ScoreStatus.PENDING);
        assertThat(missing.finalScore()).isNull();
        var business = json.createObjectNode().put("amount", new java.math.BigDecimal("100"));
        var context = new io.github.opensabre.iqc.action.ActionPolicy.Context(java.util.Map.of(), java.util.Map.of(),
                ScoreStatus.PENDING, null, null, null, java.util.Map.of(), business);
        assertThat(evaluate(restored, decisions, context).scoreStatus()).isEqualTo(ScoreStatus.NOT_APPLICABLE);
        business.put("amount", new java.math.BigDecimal("99.999999999999999999"));
        context = new io.github.opensabre.iqc.action.ActionPolicy.Context(java.util.Map.of(), java.util.Map.of(),
                ScoreStatus.PENDING, null, null, null, java.util.Map.of(), business);
        var matched = evaluate(restored, decisions, context);
        assertThat(matched.finalScore()).isEqualByComparingTo("90");
        assertThat(matched.advanced().rawNumerator()).isEqualByComparingTo("89.999999999999999990");
    }

    @Test
    void pointsKeepExactWeightedRatioAndApplyAdjustmentBeforeRounding() {
        var advanced = new Advanced(Advanced.SCHEMA, "0", "100",
                List.of(new ItemAdjustment("a", "0.1", "0.001", "0", null, null),
                        new ItemAdjustment("b", "0.2", "0", "0", null, null)),
                List.of(), List.of(new Grade("good", "60"), new Grade("low", "0")));
        var policy = new Policy(ADVANCED_VERSION, Mode.POINTS, 100, 60,
                List.of(new Item("a", 1, false), new Item("b", 1, false)), advanced);
        var result = evaluate(policy, List.of(new Decision("a", ItemStatus.PASS), new Decision("b", ItemStatus.FAIL)));
        assertThat(result.finalScore()).isEqualByComparingTo("33.33");
        assertThat(result.advanced().rawNumerator()).isEqualByComparingTo("10.00003");
        assertThat(result.advanced().rawDenominator()).isEqualByComparingTo("0.3");
        assertThat(result.advanced().grade()).isEqualTo("low");
    }

    @Test
    void unresolvedOverallPreservesCompletedDimensionWithoutInventingGrade() {
        var advanced = new Advanced(Advanced.SCHEMA, "0", "100",
                List.of(new ItemAdjustment("a", "2", "3", "-1", "service", null)),
                List.of(new Dimension("service", 80, 60, "0", "100")), List.of(new Grade("any", "0")));
        var policy = new Policy(ADVANCED_VERSION, Mode.DEDUCTION, 100, 60,
                List.of(new Item("a", 10, false), new Item("b", 10, false)), advanced);
        var result = evaluate(policy, List.of(new Decision("a", ItemStatus.PASS)));
        assertThat(result.scoreStatus()).isEqualTo(ScoreStatus.PENDING);
        assertThat(result.finalScore()).isNull();
        assertThat(result.advanced().grade()).isNull();
        assertThat(result.advanced().dimensions().getFirst().scoreStatus()).isEqualTo(ScoreStatus.FINAL);
        assertThat(result.advanced().dimensions().getFirst().finalScore()).isEqualByComparingTo("86");
    }

    @Test
    void confirmedVetoOverridesPositiveMinimumAndPositiveAdjustment() {
        var advanced = new Advanced(Advanced.SCHEMA, "20", "100",
                List.of(new ItemAdjustment("a", "1", "0", "100", null, null)), List.of(), List.of());
        var policy = new Policy(ADVANCED_VERSION, Mode.DEDUCTION, 100, 60,
                List.of(new Item("a", 10, true)), advanced);
        var result = evaluate(policy, List.of(new Decision("a", ItemStatus.FAIL)));
        assertThat(result.finalScore()).isEqualByComparingTo("0");
        assertThat(result.conclusion()).isEqualTo(Conclusion.UNQUALIFIED);
        assertThat(result.advanced().rawNumerator()).isEqualByComparingTo("190");
    }
}
