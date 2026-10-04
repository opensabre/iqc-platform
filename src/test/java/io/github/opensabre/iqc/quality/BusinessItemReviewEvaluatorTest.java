package io.github.opensabre.iqc.quality;

import io.github.opensabre.iqc.scheme.SchemeDefinition;
import io.github.opensabre.iqc.scheme.SchemeResultEvaluator;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.github.opensabre.iqc.scoring.InspectionScoring.ItemStatus.*;
import static org.assertj.core.api.Assertions.*;

class BusinessItemReviewEvaluatorTest {
    private SchemeDefinition definition(InspectionScoring.Mode mode, boolean veto) {
        return new SchemeDefinition(SchemeDefinition.SCHEMA, List.of(
                new SchemeDefinition.Item("a", "问候", new SchemeDefinition.RuleReference("r1", 2), SchemeDefinition.HitMeaning.COMPLIANCE),
                new SchemeDefinition.Item("b", "承诺", new SchemeDefinition.RuleReference("r2", 3), SchemeDefinition.HitMeaning.VIOLATION),
                new SchemeDefinition.Item("c", "观察项", new SchemeDefinition.RuleReference("r3", 1), SchemeDefinition.HitMeaning.VIOLATION)),
                null, new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION, mode, 100, 60,
                List.of(new InspectionScoring.Item("a", 20, false), new InspectionScoring.Item("b", 40, veto))));
    }

    private List<SchemeResultEvaluator.ItemResult> items(InspectionScoring.ItemStatus a, InspectionScoring.ItemStatus b) {
        return List.of(new SchemeResultEvaluator.ItemResult("a", "问候", "r1", 2, a, List.of("m1")),
                new SchemeResultEvaluator.ItemResult("b", "承诺", "r2", 3, b, List.of()),
                new SchemeResultEvaluator.ItemResult("c", "观察项", "r3", 1, ERROR, List.of()));
    }

    private BusinessItemReviewEvaluator.Decision decision(String code, InspectionScoring.ItemStatus before, InspectionScoring.ItemStatus after) {
        return new BusinessItemReviewEvaluator.Decision("result-1", code, before, after, "人工核对原始会话", List.of("m1"));
    }

    @Test
    void correctionRecalculatesFrozenPolicyWithoutChangingMachineResult() {
        var input = items(FAIL, PASS);
        var projection = BusinessItemReviewEvaluator.evaluate("result-1", definition(InspectionScoring.Mode.DEDUCTION, false),
                input, List.of(decision("a", FAIL, PASS)), Set.of("m1"));
        assertThat(projection.original().scoring().finalScore()).isEqualByComparingTo("80");
        assertThat(projection.reviewed().scoring().finalScore()).isEqualByComparingTo("100");
        assertThat(input.getFirst().status()).isEqualTo(FAIL);
        assertThat(projection.original().items().getFirst().status()).isEqualTo(FAIL);
        assertThat(projection.reviewed().items().get(2).status()).isEqualTo(ERROR);
        assertThat(projection.reviewed().scoring().lines()).hasSize(2);
    }

    @Test
    void pointsModeAndVetoAreStillOwnedByScoringPolicy() {
        var projected = BusinessItemReviewEvaluator.evaluate("result-1", definition(InspectionScoring.Mode.POINTS, true),
                items(FAIL, FAIL), List.of(decision("b", FAIL, PASS)), Set.of("m1"));
        assertThat(projected.original().scoring().finalScore()).isEqualByComparingTo("0");
        assertThat(projected.original().scoring().vetoTriggered()).isTrue();
        assertThat(projected.reviewed().scoring().finalScore()).isEqualByComparingTo("66.67");
        assertThat(projected.reviewed().scoring().vetoTriggered()).isFalse();
    }

    @Test
    void unresolvedOtherItemsRemainPendingAndNotApplicableRemainsUnscored() {
        var projected = BusinessItemReviewEvaluator.evaluate("result-1", definition(InspectionScoring.Mode.DEDUCTION, false),
                items(ERROR, REVIEW_REQUIRED), List.of(decision("a", ERROR, PASS)), Set.of("m1"));
        assertThat(projected.reviewed().scoring().scoreStatus()).isEqualTo(InspectionScoring.ScoreStatus.PENDING);
        assertThat(projected.reviewed().scoring().finalScore()).isNull();
        projected = BusinessItemReviewEvaluator.evaluate("result-1", definition(InspectionScoring.Mode.DEDUCTION, false),
                items(ERROR, NOT_APPLICABLE), List.of(decision("a", ERROR, NOT_APPLICABLE)), Set.of("m1"));
        assertThat(projected.reviewed().scoring().scoreStatus()).isEqualTo(InspectionScoring.ScoreStatus.NOT_APPLICABLE);
        assertThat(projected.reviewed().scoring().finalScore()).isNull();
    }

    @Test
    void rejectsStaleResultUnknownItemAndDuplicateDecisions() {
        var policy = definition(InspectionScoring.Mode.DEDUCTION, false);
        var original = items(FAIL, PASS);
        assertThatThrownBy(() -> BusinessItemReviewEvaluator.evaluate("result-2", policy, original,
                List.of(decision("a", FAIL, PASS)), Set.of("m1"))).hasMessageContaining("已变化");
        assertThatThrownBy(() -> BusinessItemReviewEvaluator.evaluate("result-1", policy, original,
                List.of(decision("unknown", FAIL, PASS)), Set.of("m1"))).hasMessageContaining("未知");
        assertThatThrownBy(() -> BusinessItemReviewEvaluator.evaluate("result-1", policy, original,
                List.of(decision("a", PASS, FAIL)), Set.of("m1"))).hasMessageContaining("预期结论");
        assertThatThrownBy(() -> BusinessItemReviewEvaluator.evaluate("result-1", policy, original,
                List.of(decision("a", FAIL, PASS), decision("a", FAIL, PASS)), Set.of("m1"))).hasMessageContaining("重复");
    }

    @Test
    void reviewingUnscoredItemDoesNotCreatePointsAndAbsenceMayHaveNoMessageEvidence() {
        var change = new BusinessItemReviewEvaluator.Decision("result-1", "c", ERROR, FAIL, "全会话未出现要求的说明", List.of());
        var projection = BusinessItemReviewEvaluator.evaluate("result-1", definition(InspectionScoring.Mode.DEDUCTION, false),
                items(PASS, PASS), List.of(change), Set.of("m1"));
        assertThat(projection.original().scoring()).isEqualTo(projection.reviewed().scoring());
        assertThat(projection.reviewed().items().get(2).status()).isEqualTo(FAIL);
        assertThat(projection.reviewed().items().get(2).matchedMessageIds()).isEmpty();
    }

    @Test
    void rejectsForeignEvidenceUnresolvedHumanDecisionsAndMissingReason() {
        var policy = definition(InspectionScoring.Mode.DEDUCTION, false);
        var original = items(FAIL, PASS);
        for (var status : List.of(ERROR, NOT_EVALUATED, REVIEW_REQUIRED))
            assertThatThrownBy(() -> BusinessItemReviewEvaluator.evaluate("result-1", policy, original,
                    List.of(decision("a", FAIL, status)), Set.of("m1"))).hasMessageContaining("人工结论");
        for (var invalid : List.of(
                new BusinessItemReviewEvaluator.Decision("result-1", "a", FAIL, PASS, "原因", List.of("foreign")),
                new BusinessItemReviewEvaluator.Decision("result-1", "a", FAIL, PASS, "原因", List.of("m1", "m1")),
                new BusinessItemReviewEvaluator.Decision("result-1", "a", FAIL, PASS, " ", List.of())))
            assertThatThrownBy(() -> BusinessItemReviewEvaluator.evaluate("result-1", policy, original,
                    List.of(invalid), Set.of("m1"))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsIncompleteOrMismatchedOriginalAndCopiesMutableEvidence() {
        var policy = definition(InspectionScoring.Mode.DEDUCTION, false);
        var original = new ArrayList<>(items(FAIL, PASS));
        assertThatThrownBy(() -> BusinessItemReviewEvaluator.evaluate("result-1", policy, original.subList(0, 2),
                List.of(decision("a", FAIL, PASS)), Set.of("m1"))).hasMessageContaining("不完整");
        original.set(0, new SchemeResultEvaluator.ItemResult("a", "问候", "r1", 99, FAIL, List.of()));
        assertThatThrownBy(() -> BusinessItemReviewEvaluator.evaluate("result-1", policy, original,
                List.of(decision("a", FAIL, PASS)), Set.of("m1"))).hasMessageContaining("不一致");
        var evidence = new ArrayList<>(List.of("m1"));
        var decision = new BusinessItemReviewEvaluator.Decision("result-1", "a", FAIL, PASS, "确认", evidence);
        var projected = BusinessItemReviewEvaluator.evaluate("result-1", policy, items(FAIL, PASS), List.of(decision), Set.of("m1"));
        evidence.clear();
        assertThat(projected.decisions().getFirst().evidenceMessageIds()).containsExactly("m1");
        assertThatThrownBy(() -> projected.reviewed().items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
