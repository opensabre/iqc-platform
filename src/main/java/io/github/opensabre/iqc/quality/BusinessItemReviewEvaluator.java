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
        if (definition == null) throw IqcException.invalidArgument("复核必须指定冻结方案");
        var contracts = definition.items().stream().map(item -> new ItemContract(item.itemCode(), item.name(),
                item.rule().id(), item.rule().versionNo())).toList();
        return evaluateFrozen(sourceResultId, definition.scoring(), contracts, originalItems, decisions, conversationMessageIds);
    }

    /** Frozen display binding; actual Pipeline detector provenance remains in Finding/Fusion traces. */
    public record ItemContract(String itemCode, String name, String ruleId, int ruleVersionNo) { }

    public static Projection evaluateFrozen(String sourceResultId, InspectionScoring.Policy policy,
            List<ItemContract> contracts, List<SchemeResultEvaluator.ItemResult> originalItems,
            List<Decision> decisions, Set<String> conversationMessageIds) {
        if (sourceResultId == null || sourceResultId.isBlank() || policy == null || contracts == null
                || originalItems == null || decisions == null || conversationMessageIds == null)
            throw IqcException.invalidArgument("复核必须指定原始结果、冻结政策和项目结论");
        Map<String, ItemContract> expected = new HashMap<>();
        for (var item : contracts) {
            if (item == null || item.itemCode() == null || item.itemCode().isBlank() || item.name() == null
                    || item.ruleId() == null || item.ruleId().isBlank() || item.ruleVersionNo() < 1
                    || expected.putIfAbsent(item.itemCode(), item) != null)
                throw IqcException.invalidState("冻结复核项目损坏或重复");
        }
        if (policy.items().stream().anyMatch(item -> !expected.containsKey(item.itemCode())))
            throw IqcException.invalidState("评分政策引用了未知复核项目");
        Map<String, SchemeResultEvaluator.ItemResult> original = new HashMap<>();
        for (var item : originalItems) {
            if (item == null) throw IqcException.invalidState("原始业务质检项损坏");
            var configured = expected.get(item.itemCode());
            if (configured == null || item.status() == null || !configured.ruleId().equals(item.ruleId())
                    || configured.ruleVersionNo() != item.ruleVersionNo() || original.putIfAbsent(item.itemCode(), item) != null)
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
        for (var configured : contracts) {
            var item = original.get(configured.itemCode());
            machine.add(new SchemeResultEvaluator.ItemResult(item.itemCode(), configured.name(), item.ruleId(),
                    item.ruleVersionNo(), item.status(), List.copyOf(item.matchedMessageIds())));
            Decision decision = overrides.get(item.itemCode());
            reviewed.add(decision == null ? machine.getLast() : new SchemeResultEvaluator.ItemResult(item.itemCode(),
                    configured.name(), item.ruleId(), item.ruleVersionNo(), decision.finalStatus(), decision.evidenceMessageIds()));
        }
        return new Projection(sourceResultId, SchemeResultEvaluator.scoreItems(policy, machine),
                SchemeResultEvaluator.scoreItems(policy, reviewed), List.copyOf(normalized));
    }

    /** Validates the stored machine facts before applying a separate human business-item overlay. */
    public static Projection evaluatePipeline(String sourceResultId,
            io.github.opensabre.iqc.pipelineasset.PipelineRelease release,
            io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator.Evaluation trace,
            List<SchemeResultEvaluator.ItemResult> originalItems, List<Decision> decisions,
            Set<String> conversationMessageIds) {
        if (release == null || trace == null || !"iqc-pipeline-execution-v1".equals(trace.schemaVersion())
                || trace.run() == null || trace.run().status() == null || trace.mode() == null || trace.inputHash() == null || trace.fusion() == null || trace.items() == null || trace.scoring() == null
                || originalItems == null || !originalItems.equals(trace.items()))
            throw IqcException.invalidState("流水线复核基准不完整或不一致");
        var raw = trace.fusion().stream().flatMap(value -> value.rawFindings().stream()).toList();
        var nodes = release.pipeline().nodes().stream().collect(Collectors.toMap(
                io.github.opensabre.iqc.pipeline.PipelineDefinition.Node::nodeId, node -> node));
        for (var fact : raw) {
            var node = nodes.get(fact.source().nodeId());
            if (node == null || !node.type().equals(fact.source().engineType())
                    || !node.version().equals(fact.source().engineVersion())
                    || !node.config().path("findingDefinition").path("code").asText().equals(fact.code())
                    || !node.config().path("findingDefinition").path("category").asText().equals(fact.category())
                    || !node.config().path("findingDefinition").path("valueType").asText().equals(fact.valueType().name()))
                throw IqcException.invalidState("流水线事实来源与冻结节点不一致");
            var definition = node.config().path("findingDefinition");
            if (fact.state() == io.github.opensabre.iqc.finding.Finding.State.KNOWN
                    && (definition.path("evidenceRequired").asBoolean()
                    || fact.severity() == io.github.opensabre.iqc.finding.Finding.Severity.HIGH
                    || fact.severity() == io.github.opensabre.iqc.finding.Finding.Severity.CRITICAL)
                    && fact.evidence().isEmpty()) throw IqcException.invalidState("确定事实缺少冻结政策要求的证据");
            var evidenceTypes = new java.util.HashSet<String>();
            definition.path("evidenceTypes").forEach(value -> evidenceTypes.add(value.asText()));
            if (!evidenceTypes.isEmpty() && fact.evidence().stream().anyMatch(value -> !evidenceTypes.contains(value.type().name())))
                throw IqcException.invalidState("事实证据类型与冻结定义不一致");
            if (fact.state() == io.github.opensabre.iqc.finding.Finding.State.KNOWN
                    && !trace.run().findings().contains(fact))
                throw IqcException.invalidState("确定事实没有对应原始节点输出");
        }
        var fusion = io.github.opensabre.iqc.fusion.FindingFusion.fuse(raw, release.fusion());
        if (!fusion.equals(trace.fusion())) throw IqcException.invalidState("流水线融合事实无法由原始输出再现");
        var statuses = io.github.opensabre.iqc.pipelinebridge.PipelineDecisionEvaluator.decisions(release.items(), fusion)
                .stream().collect(Collectors.toMap(InspectionScoring.Decision::itemCode, InspectionScoring.Decision::status));
        if (originalItems.stream().anyMatch(item -> item == null || item.status() != statuses.get(item.itemCode())))
            throw IqcException.invalidState("流水线机器项目与冻结事实映射不一致");
        var contracts = release.items().stream().map(mapping -> {
            var producer = release.pipeline().nodes().stream().filter(node -> mapping.findingCode().equals(
                    node.config().path("findingDefinition").path("code").asText())).findFirst()
                    .orElseThrow(() -> IqcException.invalidState("流水线业务项缺少冻结事实生产者"));
            var rule = producer.config().path("ruleSnapshot");
            return new ItemContract(mapping.itemCode(), mapping.itemCode(), rule.path("id").asText(), rule.path("versionNo").asInt());
        }).toList();
        var projection = evaluateFrozen(sourceResultId, release.scoring(), contracts, originalItems, decisions, conversationMessageIds);
        if (!projection.original().scoring().equals(trace.scoring()))
            throw IqcException.invalidState("流水线机器评分与冻结政策不一致");
        return projection;
    }

    private static void validateEvidence(List<String> ids, Set<String> allowed) {
        if (ids == null || ids.stream().anyMatch(id -> id == null || id.isBlank() || !allowed.contains(id))
                || ids.stream().distinct().count() != ids.size())
            throw IqcException.invalidArgument("复核证据必须引用当前会话中的唯一消息");
        // An absence-based judgment can legitimately have no positive evidence; its reason remains mandatory.
    }
}
