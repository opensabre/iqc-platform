package io.github.opensabre.iqc.quality;

import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.scheme.SchemeDefinition;
import io.github.opensabre.iqc.scheme.SchemeResultEvaluator;
import io.github.opensabre.iqc.scoring.InspectionScoring;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Pure review projection: keeps machine findings intact and reuses the frozen scoring policy. */
public final class BusinessItemReviewEvaluator {
    private BusinessItemReviewEvaluator() { }

    /** Human input contains business decisions and evidence, never an editable total score. */
    public record Decision(String sourceResultId, String itemCode, InspectionScoring.ItemStatus expectedStatus,
                           InspectionScoring.ItemStatus finalStatus, String reason, List<String> evidenceMessageIds) { }

    /** Both projections are returned so callers cannot accidentally present a review as a machine result. */
    public record Projection(String sourceResultId, SchemeResultEvaluator.Evaluation original,
                             SchemeResultEvaluator.Evaluation reviewed, List<Decision> decisions) { }

    /**
     * Applies one effective decision per item to a specific canonical result, not to a mutable task pointer.
     * The caller must authorize/lock that result and supply its frozen definition and conversation message IDs.
     * An empty overlay validates the unchanged machine projection; review submission still requires decisions in the service.
     * This function performs no persistence, permission checks or review lifecycle transitions.
     */
    public static Projection evaluate(String sourceResultId, SchemeDefinition definition,
                                      List<SchemeResultEvaluator.ItemResult> originalItems,
                                      List<Decision> decisions, Set<String> conversationMessageIds) {
        if (sourceResultId == null || sourceResultId.isBlank() || definition == null || originalItems == null
                || decisions == null || conversationMessageIds == null)
            throw IqcException.invalidArgument("复核必须指定原始结果、冻结方案和项目结论");
        Map<String, SchemeDefinition.Item> expected = definition.items().stream()
                .collect(Collectors.toMap(SchemeDefinition.Item::itemCode, item -> item));
        Map<String, SchemeResultEvaluator.ItemResult> original = new HashMap<>();
        for (var item : originalItems) {
            if (item == null) throw IqcException.invalidState("原始业务质检项损坏");
            var configured = expected.get(item.itemCode());
            if (configured == null || item.status() == null || !configured.rule().id().equals(item.ruleId())
                    || configured.rule().versionNo() != item.ruleVersionNo() || original.putIfAbsent(item.itemCode(), item) != null)
                throw IqcException.invalidState("原始业务质检项与冻结方案不一致");
            validateEvidence(item.matchedMessageIds(), conversationMessageIds);
        }
        if (original.size() != expected.size()) throw IqcException.invalidState("原始业务质检项不完整");
        Map<String, Decision> overrides = new HashMap<>();
        List<Decision> normalized = new ArrayList<>();
        for (Decision decision : decisions) {
            if (decision == null || !sourceResultId.equals(decision.sourceResultId()))
                throw IqcException.invalidArgument("复核原始结果已变化，请刷新后重新复核");
            var item = original.get(decision.itemCode());
            if (item == null) throw IqcException.invalidArgument("复核引用未知质检项");
            if (decision.expectedStatus() != item.status()) throw IqcException.invalidArgument("复核预期结论与原始结果不一致");
            if (decision.finalStatus() != InspectionScoring.ItemStatus.PASS
                    && decision.finalStatus() != InspectionScoring.ItemStatus.FAIL
                    && decision.finalStatus() != InspectionScoring.ItemStatus.NOT_APPLICABLE)
                throw IqcException.invalidArgument("人工结论必须是满足、不满足或不适用");
            if (decision.reason() == null || decision.reason().isBlank() || decision.reason().trim().length() > 1000)
                throw IqcException.invalidArgument("复核原因不能为空且不得超过 1000 字");
            validateEvidence(decision.evidenceMessageIds(), conversationMessageIds);
            Decision copy = new Decision(sourceResultId, decision.itemCode(), decision.expectedStatus(), decision.finalStatus(),
                    decision.reason().trim(), List.copyOf(decision.evidenceMessageIds()));
            if (overrides.putIfAbsent(copy.itemCode(), copy) != null) throw IqcException.invalidArgument("同一质检项不能重复复核");
            normalized.add(copy);
        }
        List<SchemeResultEvaluator.ItemResult> machine = new ArrayList<>(), reviewed = new ArrayList<>();
        for (var configured : definition.items()) {
            var item = original.get(configured.itemCode());
            machine.add(new SchemeResultEvaluator.ItemResult(item.itemCode(), configured.name(), item.ruleId(),
                    item.ruleVersionNo(), item.status(), List.copyOf(item.matchedMessageIds())));
            Decision decision = overrides.get(item.itemCode());
            reviewed.add(decision == null ? machine.getLast() : new SchemeResultEvaluator.ItemResult(item.itemCode(),
                    configured.name(), item.ruleId(), item.ruleVersionNo(), decision.finalStatus(), decision.evidenceMessageIds()));
        }
        return new Projection(sourceResultId, SchemeResultEvaluator.scoreItems(definition, machine),
                SchemeResultEvaluator.scoreItems(definition, reviewed), List.copyOf(normalized));
    }

    private static void validateEvidence(List<String> ids, Set<String> allowed) {
        if (ids == null || ids.stream().anyMatch(id -> id == null || id.isBlank() || !allowed.contains(id))
                || ids.stream().distinct().count() != ids.size())
            throw IqcException.invalidArgument("复核证据必须引用当前会话中的唯一消息");
        // An absence-based judgment can legitimately have no positive evidence; its reason remains mandatory.
    }
}
