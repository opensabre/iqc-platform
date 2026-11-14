package io.github.opensabre.iqc.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.agent.dao.QualityAgentMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.LabelResolutionService;
import io.github.opensabre.iqc.label.dao.InsightLabelMapper;
import io.github.opensabre.iqc.label.model.InsightLabel;
import io.github.opensabre.iqc.rule.dao.QualityRuleVersionMapper;
import io.github.opensabre.iqc.rule.model.QualityRuleVersion;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.processing.EngineExecutionResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Freezes existing published tags and version-pinned rules into one batch stage per selected engine type. */
@Service
public class UnifiedLabelPlanService {
    private final LabelResolutionService resolver;
    private final InsightLabelMapper labels;
    private final QualityRuleVersionMapper versions;
    private final QualityAgentMapper agents;
    private final IqcDataScope scope;
    private final ObjectMapper mapper;
    private final io.github.opensabre.iqc.label.LabelActionService actions;
    public UnifiedLabelPlanService(LabelResolutionService resolver, InsightLabelMapper labels,
                                   QualityRuleVersionMapper versions, QualityAgentMapper agents,
                                   IqcDataScope scope, ObjectMapper mapper, io.github.opensabre.iqc.label.LabelActionService actions) {
        this.resolver = resolver; this.labels = labels; this.versions = versions;
        this.agents = agents; this.scope = scope; this.mapper = mapper; this.actions = actions;
    }

    /** References only: clients cannot inject rule content, Agent snapshots or scoring policies. */
    public record Configuration(List<EngineExecutionResultSet.EngineType> engineSequence,
                                String tagSelectionMode, List<String> selectedTagIds, String agentId) {
        public Configuration {
            if (engineSequence == null || engineSequence.isEmpty() || engineSequence.size() > 3
                    || engineSequence.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(engineSequence).size() != engineSequence.size())
                throw IqcException.invalidArgument("请选择不重复的 REGEX / DLS / LLM 引擎顺序");
            engineSequence = List.copyOf(engineSequence);
            if (tagSelectionMode == null || !List.of("ALL", "SELECTED").contains(tagSelectionMode)) throw IqcException.invalidArgument("标签选择方式无效");
            if (selectedTagIds != null && selectedTagIds.stream().anyMatch(java.util.Objects::isNull))
                throw IqcException.invalidArgument("标签 ID 不能为空");
            selectedTagIds = selectedTagIds == null ? List.of() : List.copyOf(selectedTagIds);
            if ("SELECTED".equals(tagSelectionMode) && (selectedTagIds.isEmpty() || selectedTagIds.size() > 500
                    || selectedTagIds.stream().anyMatch(id -> id == null || id.isBlank())
                    || new HashSet<>(selectedTagIds).size() != selectedTagIds.size()))
                throw IqcException.invalidArgument("请选择 1 到 500 个不重复的有效标签");
            if ("ALL".equals(tagSelectionMode) && !selectedTagIds.isEmpty()) throw IqcException.invalidArgument("全选不能混用标签名单");
        }
    }

    /** A repeatable-read transaction fixes labels, exact rule versions and Agent configuration together. */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public TaskWizardSnapshot freeze(Configuration request) {
        if (request == null) throw IqcException.invalidArgument("统一标签配置不能为空");
        var ids = request.selectedTagIds();
        if ("ALL".equals(request.tagSelectionMode())) {
            var query = Wrappers.<InsightLabel>lambdaQuery().eq(InsightLabel::getStatus, "PUBLISHED");
            if (!scope.canViewAll()) {
                String group = scope.groupId();
                query.and(q -> q.eq(InsightLabel::getCreatedBy, scope.owner())
                        .or(group != null, nested -> nested.eq(InsightLabel::getOwnerGroupId, group)));
            }
            ids = labels.selectList(query.orderByAsc(InsightLabel::getId).last("LIMIT 501"))
                    .stream().map(InsightLabel::getId).toList();
        }
        if (ids.isEmpty() || ids.size() > 500) throw IqcException.invalidArgument("MVP 每次需选择 1 到 500 个可见已发布标签，超限不截断");
        var resolved = resolver.resolve(new LabelResolutionService.LabelSelection(null, null, ids, null));
        var snapshot = mapper.valueToTree(resolved);
        for (var label : snapshot.path("labels")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) label).set("actions", mapper.valueToTree(actions.snapshots(label.path("id").asText())));
        }
        var stages = new ArrayList<TaskEnginePlanService.FrozenEngine>();
        var byType = new java.util.EnumMap<EngineExecutionResultSet.EngineType, com.fasterxml.jackson.databind.node.ArrayNode>(EngineExecutionResultSet.EngineType.class);
        for (var type : request.engineSequence()) byType.put(type, mapper.createArrayNode());
        for (var label : resolved.labels()) {
            var seen = new HashSet<String>();
            for (var binding : label.bindings()) {
                if (binding.getRuleId() == null || binding.getRuleVersionNo() == null || binding.getRuleVersionNo() < 1)
                    throw IqcException.invalidArgument("标签规则绑定缺少明确版本: " + label.code());
                var version = versions.selectOne(Wrappers.<QualityRuleVersion>lambdaQuery()
                        .eq(QualityRuleVersion::getRuleId, binding.getRuleId())
                        .eq(QualityRuleVersion::getVersionNo, binding.getRuleVersionNo()));
                if (version == null || !"PUBLISHED".equals(version.getStatus()))
                    throw IqcException.invalidArgument("标签引用的规则版本不可用: " + label.code());
                if (binding.getRuleType() != null && !binding.getRuleType().equals(version.getRuleType()))
                    throw IqcException.invalidArgument("绑定类型与规则版本不一致: " + label.code());
                if (!seen.add(version.getRuleType())) throw IqcException.invalidArgument("标签存在同类型多规则，请先调整绑定: " + label.code());
                final EngineExecutionResultSet.EngineType type;
                try { type = EngineExecutionResultSet.EngineType.valueOf(version.getRuleType()); }
                catch (IllegalArgumentException invalid) { throw IqcException.invalidArgument("统一模式仅支持 REGEX / DLS / LLM，请调整标签: " + label.code()); }
                var tags = byType.get(type);
                if (tags == null) continue;
                var row = tags.addObject().put("tagId", label.id()).put("tagCode", label.code()).put("name", label.name())
                        .put("ruleId", version.getRuleId()).put("ruleVersionNo", version.getVersionNo())
                        .put("expression", version.getExpression()).put("speakerRole", version.getTargetRole());
                row.set("rule", mapper.valueToTree(version));
            }
        }
        com.fasterxml.jackson.databind.JsonNode agentSnapshot = mapper.getNodeFactory().nullNode();
        if (request.engineSequence().contains(EngineExecutionResultSet.EngineType.LLM)) {
            var agent = request.agentId() == null ? null : agents.selectById(request.agentId());
            if (agent == null || !"PUBLISHED".equals(agent.getStatus())) throw IqcException.invalidArgument("请选择已发布的智能体配置");
            if (!scope.canView(agent.getCreatedBy(), null)) throw IqcException.accessDenied("智能体配置超出数据权限");
            agentSnapshot = mapper.valueToTree(agent);
        }
        int position = 0;
        for (var type : request.engineSequence()) {
            var config = mapper.createObjectNode().put("schemaVersion", "iqc-unified-tags-v1").put("engineName", type.name());
            config.set("tags", byType.get(type));
            if (type == EngineExecutionResultSet.EngineType.LLM) config.set("agentSnapshot", agentSnapshot);
            stages.add(new TaskEnginePlanService.FrozenEngine(EngineExecutionResultSet.Operation.LABELING,
                    "tags-" + type.name(), type, ++position, config));
        }
        return new TaskWizardSnapshot(TaskWizardSnapshot.SCHEMA,
                new TaskEnginePlanService.FrozenPlan(TaskEnginePlanService.FrozenPlan.SCHEMA, stages), null, snapshot);
    }
}
