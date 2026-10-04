package io.github.opensabre.iqc.scoring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static io.github.opensabre.iqc.scoring.InspectionScoring.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionScoringTest {
    @Test
    void deductionUsesPolicyNotDetectorFields() {
        var result = evaluate(policy(Mode.DEDUCTION, new Item("risk", 20, false), new Item("followup", 10, false)),
                List.of(new Decision("risk", ItemStatus.FAIL), new Decision("followup", ItemStatus.FAIL)));
        assertThat(result.finalScore()).isEqualByComparingTo("70");
        assertThat(result.scoreStatus()).isEqualTo(ScoreStatus.FINAL);
        assertThat(result.lines()).extracting(Line::contribution).containsExactly(20, 10);
    }

    @Test
    void sameFactCanHaveDifferentBusinessPrices() {
        var decisions = List.of(new Decision("risk", ItemStatus.FAIL));
        assertThat(evaluate(policy(Mode.DEDUCTION, new Item("risk", 20, false)), decisions).finalScore()).isEqualByComparingTo("80");
        assertThat(evaluate(policy(Mode.DEDUCTION, new Item("risk", 40, false)), decisions).finalScore()).isEqualByComparingTo("60");
    }

    @Test
    void pointsExcludeNotApplicableFromDenominator() {
        var result = evaluate(policy(Mode.POINTS, new Item("a", 20, false), new Item("b", 10, false), new Item("c", 70, false)),
                List.of(new Decision("a", ItemStatus.PASS), new Decision("b", ItemStatus.FAIL), new Decision("c", ItemStatus.NOT_APPLICABLE)));
        assertThat(result.finalScore()).isEqualByComparingTo("66.67");
        assertThat(result.coverage()).isEqualTo(new Coverage(3, 2, 1, 0));
    }

    @ParameterizedTest
    @EnumSource(value = ItemStatus.class, names = {"ERROR", "REVIEW_REQUIRED", "NOT_EVALUATED"})
    void unresolvedItemsNeverReceiveFinalScore(ItemStatus status) {
        var result = evaluate(policy(Mode.DEDUCTION, new Item("a", 20, false)), List.of(new Decision("a", status)));
        assertThat(result.finalScore()).isNull();
        assertThat(result.scoreStatus()).isEqualTo(ScoreStatus.PENDING);
        assertThat(result.conclusion()).isEqualTo(Conclusion.PENDING);
    }

    @Test
    void missingResultRemainsNotEvaluated() {
        var result = evaluate(policy(Mode.DEDUCTION, new Item("a", 20, false)), List.of());
        assertThat(result.finalScore()).isNull();
        assertThat(result.lines().getFirst().status()).isEqualTo(ItemStatus.NOT_EVALUATED);
    }

    @Test
    void confirmedVetoDoesNotHidePendingCoverage() {
        var policy = policy(Mode.DEDUCTION, new Item("veto", 0, true), new Item("other", 20, false));
        var pending = evaluate(policy, List.of(new Decision("veto", ItemStatus.FAIL)));
        assertThat(pending.conclusion()).isEqualTo(Conclusion.UNQUALIFIED);
        assertThat(pending.finalScore()).isNull();
        assertThat(pending.coverage().pending()).isEqualTo(1);
        var complete = evaluate(policy, List.of(new Decision("veto", ItemStatus.FAIL), new Decision("other", ItemStatus.PASS)));
        assertThat(complete.finalScore()).isEqualByComparingTo("0");
    }

    @Test
    void allNotApplicableAndTagOnlyHaveNoScore() {
        var allNa = evaluate(policy(Mode.POINTS, new Item("a", 20, false)), List.of(new Decision("a", ItemStatus.NOT_APPLICABLE)));
        var tagsOnly = evaluate(policy(Mode.DEDUCTION), List.of());
        for (Result result : List.of(allNa, tagsOnly)) {
            assertThat(result.finalScore()).isNull();
            assertThat(result.scoreStatus()).isEqualTo(ScoreStatus.NOT_APPLICABLE);
        }
    }

    @Test
    void deductionsCannotProduceNegativeScore() {
        assertThat(evaluate(policy(Mode.DEDUCTION, new Item("a", 80, false), new Item("b", 80, false)),
                List.of(new Decision("a", ItemStatus.FAIL), new Decision("b", ItemStatus.FAIL))).finalScore()).isEqualByComparingTo("0");
    }

    @Test
    void duplicateAndUnknownItemsAreRejectedRatherThanDoubleCharged() {
        assertThatThrownBy(() -> policy(Mode.DEDUCTION, new Item("a", 10, false), new Item("a", 20, false))).hasMessageContaining("重复");
        var policy = policy(Mode.DEDUCTION, new Item("a", 10, false));
        assertThatThrownBy(() -> evaluate(policy, List.of(new Decision("a", ItemStatus.FAIL), new Decision("a", ItemStatus.FAIL)))).hasMessageContaining("一个最终结论");
        assertThatThrownBy(() -> evaluate(policy, List.of(new Decision("unknown", ItemStatus.FAIL)))).hasMessageContaining("未知项目");
    }

    @Test
    void invalidPoliciesCannotEnterVersionHistory() {
        assertThatThrownBy(() -> policy(Mode.POINTS, new Item("a", 0, false))).hasMessageContaining("分值");
        assertThatThrownBy(() -> policy(Mode.DEDUCTION, new Item("a", -1, false))).hasMessageContaining("分值");
        assertThatThrownBy(() -> new Policy("legacy", Mode.DEDUCTION, 100, 60, List.of())).hasMessageContaining("版本");
        assertThatThrownBy(() -> new Policy(POLICY_VERSION, Mode.DEDUCTION, 100, 101, List.of())).hasMessageContaining("合格分数");
    }

    @Test
    void policyCopiesItsMembers() {
        var items = new ArrayList<>(List.of(new Item("a", 10, false)));
        var policy = new Policy(POLICY_VERSION, Mode.DEDUCTION, 100, 60, items);
        items.clear();
        assertThat(policy.items()).hasSize(1);
        assertThatThrownBy(() -> policy.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private Policy policy(Mode mode, Item... items) {
        return new Policy(POLICY_VERSION, mode, 100, 60, List.of(items));
    }
}
