package io.github.opensabre.iqc.scoring;

import io.github.opensabre.iqc.governance.IqcException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Deterministic V2 scoring over business item decisions, independent of detector rule fields. */
public final class InspectionScoring {
    public static final String POLICY_VERSION = "iqc-score-v2";

    private InspectionScoring() { }

    public enum Mode { DEDUCTION, POINTS }
    public enum ItemStatus { PASS, FAIL, NOT_APPLICABLE, REVIEW_REQUIRED, ERROR, NOT_EVALUATED }
    public enum ScoreStatus { FINAL, PENDING, NOT_APPLICABLE }
    public enum Conclusion { QUALIFIED, UNQUALIFIED, PENDING, NOT_APPLICABLE }

    /** One scoring responsibility; detector reuse never creates another entry implicitly. */
    public record Item(String itemCode, int points, boolean veto) { }

    /** Frozen policy owned by a scheme version, not by a detection rule or Agent. */
    public record Policy(String version, Mode mode, int baseScore, int passingScore, List<Item> items) {
        public Policy {
            if (!POLICY_VERSION.equals(version)) throw IqcException.invalidArgument("不支持的评分政策版本");
            if (mode == null) throw IqcException.invalidArgument("必须选择评分方式");
            if (baseScore < 1 || baseScore > 100) throw IqcException.invalidArgument("基础分必须在 1 到 100 之间");
            int maximum = mode == Mode.POINTS ? 100 : baseScore;
            if (passingScore < 0 || passingScore > maximum) throw IqcException.invalidArgument("合格分数超出评分范围");
            if (items == null) throw IqcException.invalidArgument("评分项目不能为空，仅打标方案请使用空列表");
            var codes = new HashSet<String>();
            for (Item item : items) {
                if (item == null || item.itemCode() == null || item.itemCode().isBlank()
                        || !item.itemCode().equals(item.itemCode().trim()) || !codes.add(item.itemCode()))
                    throw IqcException.invalidArgument("评分项目编码不能为空、重复或包含首尾空格");
                if (item.points() < 0 || item.points() > 100 || (mode == Mode.POINTS && item.points() == 0))
                    throw IqcException.invalidArgument("项目分值超出范围: " + item.itemCode());
            }
            items = List.copyOf(items);
        }
    }

    public record Decision(String itemCode, ItemStatus status) { }

    /** Contribution is deducted points in DEDUCTION mode and earned points in POINTS mode. */
    public record Line(String itemCode, ItemStatus status, int configuredPoints, Integer contribution, boolean vetoTriggered) { }
    public record Coverage(int expected, int completed, int notApplicable, int pending) { }
    public record Result(String policyVersion, Mode mode, ScoreStatus scoreStatus, BigDecimal finalScore,
                         Conclusion conclusion, boolean vetoTriggered, List<Line> lines, Coverage coverage) { }

    /** Scores exactly one decision per item; missing decisions stay pending instead of becoming passes. */
    public static Result evaluate(Policy policy, List<Decision> decisions) {
        if (policy == null || decisions == null) throw IqcException.invalidArgument("评分政策和检查结果不能为空");
        Map<String, ItemStatus> statuses = new HashMap<>();
        var expected = new HashSet<String>();
        policy.items().forEach(item -> expected.add(item.itemCode()));
        for (Decision decision : decisions) {
            if (decision == null || decision.itemCode() == null || decision.status() == null
                    || !expected.contains(decision.itemCode())) throw IqcException.invalidArgument("评分结果引用了未知项目");
            if (statuses.putIfAbsent(decision.itemCode(), decision.status()) != null)
                throw IqcException.invalidArgument("同一质检项只能提交一个最终结论: " + decision.itemCode());
        }
        List<Line> lines = new ArrayList<>();
        long total = 0, denominator = 0;
        int completed = 0, notApplicable = 0, pending = 0;
        boolean veto = false;
        for (Item item : policy.items()) {
            ItemStatus status = statuses.getOrDefault(item.itemCode(), ItemStatus.NOT_EVALUATED);
            Integer contribution = null;
            boolean triggered = status == ItemStatus.FAIL && item.veto();
            veto |= triggered;
            switch (status) {
                case PASS, FAIL -> {
                    completed++;
                    denominator += item.points();
                    contribution = policy.mode() == Mode.DEDUCTION
                            ? (status == ItemStatus.FAIL ? item.points() : 0)
                            : (status == ItemStatus.PASS ? item.points() : 0);
                    total += contribution;
                }
                case NOT_APPLICABLE -> { notApplicable++; contribution = 0; }
                default -> pending++;
            }
            lines.add(new Line(item.itemCode(), status, item.points(), contribution, triggered));
        }
        ScoreStatus scoreStatus = pending > 0 ? ScoreStatus.PENDING
                : completed == 0 ? ScoreStatus.NOT_APPLICABLE : ScoreStatus.FINAL;
        BigDecimal score = null;
        if (scoreStatus == ScoreStatus.FINAL) {
            score = veto ? BigDecimal.ZERO : policy.mode() == Mode.DEDUCTION
                    ? BigDecimal.valueOf(Math.max(0L, policy.baseScore() - total))
                    : BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
        }
        Conclusion conclusion = veto ? Conclusion.UNQUALIFIED
                : scoreStatus == ScoreStatus.PENDING ? Conclusion.PENDING
                : scoreStatus == ScoreStatus.NOT_APPLICABLE ? Conclusion.NOT_APPLICABLE
                : score.compareTo(BigDecimal.valueOf(policy.passingScore())) >= 0 ? Conclusion.QUALIFIED : Conclusion.UNQUALIFIED;
        return new Result(policy.version(), policy.mode(), scoreStatus, score, conclusion, veto, List.copyOf(lines),
                new Coverage(policy.items().size(), completed, notApplicable, pending));
    }
}
