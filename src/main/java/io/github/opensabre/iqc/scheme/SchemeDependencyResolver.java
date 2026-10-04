package io.github.opensabre.iqc.scheme;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.agent.dao.QualityAgentMapper;
import io.github.opensabre.iqc.agent.dao.QualityAgentVersionMapper;
import io.github.opensabre.iqc.agent.model.QualityAgentVersion;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.rule.RuleMatcher;
import io.github.opensabre.iqc.rule.dao.QualityRuleMapper;
import io.github.opensabre.iqc.rule.dao.QualityRuleVersionMapper;
import io.github.opensabre.iqc.rule.model.QualityRuleVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Resolves exact published versions, never the mutable current rule/Agent body. */
@Component
@RequiredArgsConstructor
public class SchemeDependencyResolver {
    private final QualityRuleMapper rules;
    private final QualityRuleVersionMapper ruleVersions;
    private final QualityAgentMapper agents;
    private final QualityAgentVersionMapper agentVersions;
    private final ObjectMapper mapper;
    private final io.github.opensabre.iqc.agent.AgentAssetReferenceValidator assets;
    private final io.github.opensabre.iqc.scheme.dao.InspectionSchemeMapper schemes;
    private final io.github.opensabre.iqc.label.LabelResolutionService labels;

    /** Rechecks kill switches at execution boundaries without replacing any frozen task configuration. */
    public void validateTaskDependencies(JsonNode taskSnapshot) {
        SchemeDefinition definition = SchemeResultEvaluator.definition(taskSnapshot, mapper);
        if (definition == null) return; // Legacy tasks retain their original execution contract.
        if ("DRAFT_TRIAL".equals(taskSnapshot.path("schemeSnapshot").path("kind").asText())) definition.requireTrialExecutable();
        else definition.requireExecutable();
        if (SchemeDefinition.ROUTED_TASK_SCHEMA.equals(definition.schemaVersion())) {
            var frozenAgent = taskSnapshot.at("/schemeSnapshot/release/dependencies/agent");
            validateRouteTaskProjection(taskSnapshot, frozenAgent.isMissingNode() || frozenAgent.isNull() ? null : frozenAgent.toString(), mapper);
        }
        String schemeId = taskSnapshot.path("schemeSnapshot").path("schemeId").asText("");
        if (schemeId.isBlank()) throw IqcException.invalidState("任务缺少业务方案标识，禁止启动");
        // System workers need only the kill switch here; task authorization remains at their existing boundary.
        var scheme = schemes.selectById(schemeId);
        if (scheme == null || !"ACTIVE".equals(scheme.getStatus()))
            throw IqcException.invalidState("业务方案不存在或已停用，不能启动或恢复任务");
        JsonNode release = taskSnapshot.path("schemeSnapshot").path("release");
        if (SchemeDefinition.ROUTED_TASK_SCHEMA.equals(definition.schemaVersion())) {
            try {
                validateReleasedDependencies(mapper.treeToValue(release, InspectionSchemeService.ReleaseSnapshot.class));
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw IqcException.invalidState("逐项路线发布快照无效");
            }
            return;
        }
        var scoped = release.path("definition").path("items");
        boolean hasScope = false;
        for (JsonNode item : scoped) if (item.hasNonNull("inputScope")) {
            hasScope = true;
            String scope = item.path("inputScope").asText();
            if (!java.util.Set.of("MESSAGE", "CONVERSATION").contains(scope))
                throw IqcException.invalidState("任务输入范围无效");
            long matches = 0;
            for (JsonNode rule : taskSnapshot.path("rules"))
                if (item.path("rule").path("id").asText().equals(rule.path("id").asText())
                        && item.path("rule").path("versionNo").asInt() == rule.path("versionNo").asInt()
                        && "LLM".equalsIgnoreCase(rule.path("ruleType").asText())
                        && scope.equals(rule.path("inspectionScope").asText())) matches++;
            if (matches != 1) throw IqcException.invalidState("任务检测输入范围与方案不一致");
        }
        if (hasScope && (!SchemeDefinition.SCOPED_TASK_SCHEMA.equals(release.path("definition").path("schemaVersion").asText())
                || !taskSnapshot.path("rules").equals(release.path("dependencies").path("rules"))))
            throw IqcException.invalidState("显式输入范围任务协议或依赖不一致");
        if (release.path("dependencies").hasNonNull("labels")) {
            try {
                validateReleasedDependencies(mapper.treeToValue(release, InspectionSchemeService.ReleaseSnapshot.class));
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw IqcException.invalidState("业务方案发布快照无效");
            }
        } else resolve(definition); // Historical checks-only releases retain their original availability contract.
    }

    /** Rejects a joint task whose executable rules or label scope diverge from its frozen release. */
    public static void validateJointTaskProjection(JsonNode taskSnapshot, String labelScopeJson, ObjectMapper mapper) {
        if (taskSnapshot == null || !taskSnapshot.has("schemeSnapshot")) return;
        JsonNode release = taskSnapshot.path("schemeSnapshot").path("release");
        JsonNode references = release.path("definition").path("labels");
        JsonNode frozen = release.path("dependencies").path("labels");
        boolean joint = references.isArray() && !references.isEmpty();
        if (!joint) {
            if ((!frozen.isMissingNode() && !frozen.isNull()) || (labelScopeJson != null && !labelScopeJson.isBlank()))
                throw IqcException.invalidState("无标签方案不能携带联合标签快照");
            return;
        }
        if (!frozen.isObject() || !"2.0".equals(frozen.path("schemaVersion").asText())
                || !frozen.path("labels").isArray() || frozen.path("labels").size() != references.size()
                || !taskSnapshot.path("rules").equals(release.path("dependencies").path("rules")))
            throw IqcException.invalidState("联合任务检测依赖与方案快照不一致");
        try {
            // JSON numbers compare by value; typed decimal weights and parsed numbers may use different nodes.
            if (labelScopeJson == null || !frozen.equals((left, right) ->
                    left.isNumber() && right.isNumber() ? left.decimalValue().compareTo(right.decimalValue())
                            : left.equals(right) ? 0 : 1, mapper.readTree(labelScopeJson)))
                throw IqcException.invalidState("联合任务标签快照与方案不一致");
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw IqcException.invalidState("联合任务标签快照损坏");
        }
        var expected = new java.util.HashMap<String, Integer>();
        for (JsonNode reference : references) {
            String id = reference.path("id").asText();
            int version = reference.path("versionNo").asInt(0);
            if (id.isBlank() || version < 1 || expected.putIfAbsent(id, version) != null)
                throw IqcException.invalidState("联合任务标签引用无效");
        }
        int expectedTargets = 0;
        for (JsonNode label : frozen.path("labels")) {
            String id = label.path("id").asText();
            int version = label.path("versionNo").asInt(0);
            if (id.isBlank() || !java.util.Objects.equals(expected.remove(id), version)
                    || !label.path("bindings").isArray() || label.path("bindings").isEmpty()
                    || !label.path("values").isArray() || label.path("values").isEmpty())
                throw IqcException.invalidState("联合任务标签定义与引用不一致");
            for (JsonNode binding : label.path("bindings")) for (JsonNode value : label.path("values")) {
                expectedTargets++;
                long matches = 0;
                for (JsonNode rule : taskSnapshot.path("rules")) {
                    if (!binding.path("ruleId").asText().equals(rule.path("id").asText())
                            || binding.path("ruleVersionNo").asInt(0) != rule.path("versionNo").asInt(-1)) continue;
                    for (JsonNode target : rule.path("labelFactTargets")) {
                        if (id.equals(target.path("labelId").asText()) && version == target.path("labelVersionNo").asInt(0)
                                && value.path("valueCode").asText().equals(target.path("valueCode").asText())
                                && label.path("targetRole").asText().equals(target.path("subjectRole").asText())) matches++;
                    }
                }
                if (matches != 1) throw IqcException.invalidState("联合任务标签识别目标覆盖不完整或重复");
            }
        }
        if (!expected.isEmpty()) throw IqcException.invalidState("联合任务缺少冻结标签定义");
        int actualTargets = 0;
        for (JsonNode rule : taskSnapshot.path("rules")) actualTargets += rule.path("labelFactTargets").size();
        if (actualTargets != expectedTargets) throw IqcException.invalidState("联合任务包含未引用的标签识别目标");
    }

    /** Checks active dependencies against an immutable release without looking up mutable label definitions again. */
    public void validateReleasedDependencies(InspectionSchemeService.ReleaseSnapshot release) {
        if (release == null || release.definition() == null || release.dependencies() == null)
            throw IqcException.invalidState("方案发布快照缺少依赖");
        var references = release.definition().labels();
        var frozen = release.dependencies().labels();
        if (references == null || references.isEmpty()) {
            if (frozen != null) throw IqcException.invalidState("方案发布快照包含未引用标签");
            resolve(release.definition());
            return;
        }
        if (frozen == null || !"2.0".equals(frozen.schemaVersion()) || frozen.labels() == null
                || frozen.labels().size() != references.size())
            throw IqcException.invalidState("方案发布快照缺少联合标签定义");
        var expected = new java.util.HashMap<String, Integer>();
        references.forEach(reference -> expected.put(reference.id(), reference.versionNo()));
        for (var label : frozen.labels()) {
            if (label == null || label.id() == null || !java.util.Objects.equals(expected.remove(label.id()), label.versionNo()))
                throw IqcException.invalidState("方案发布快照标签版本不一致");
        }
        // Re-resolve rule/Agent kill switches and managed model assets, but use only the frozen label payload.
        resolve(release.definition(), frozen);
    }

    /** Captures reusable execution-compatible snapshots while stripping detector-owned scoring fields. */
    public Dependencies resolve(SchemeDefinition definition) {
        var frozenLabels = definition.labels() == null || definition.labels().isEmpty() ? null : labels.resolveVersions(definition.labels());
        boolean routed = definition.items().stream().anyMatch(item -> item.execution() != null);
        return resolve(definition, frozenLabels, routed);
    }

    /** Read-only expert preview; compiling a route plan does not authorize task execution. */
    public Dependencies previewRoutes(SchemeDefinition definition) {
        if (definition.items().stream().noneMatch(item -> item.execution() != null))
            throw IqcException.invalidArgument("逐项路线预览必须包含路线配置");
        var frozenLabels = definition.labels() == null || definition.labels().isEmpty()
                ? null : labels.resolveVersions(definition.labels());
        return resolve(definition, frozenLabels, true);
    }

    private Dependencies resolve(SchemeDefinition definition, io.github.opensabre.iqc.label.LabelResolutionService.ResolvedSelection frozenLabels) {
        return resolve(definition, frozenLabels, false);
    }

    private Dependencies resolve(SchemeDefinition definition, io.github.opensabre.iqc.label.LabelResolutionService.ResolvedSelection frozenLabels,
                                 boolean routePreview) {
        boolean routed = routePreview || definition.items().stream().anyMatch(item -> item.execution() != null);
        if (!routed) definition.requireSupportedRoutes();
        var references = new java.util.LinkedHashMap<String, Integer>();
        for (var item : definition.items()) {
            mergeReference(references, item.rule().id(), item.rule().versionNo());
            if (item.appliesWhen() != null)
                mergeReference(references, item.appliesWhen().id(), item.appliesWhen().versionNo());
            if (item.execution() != null) {
                var stage = item.execution().prefilter() != null ? item.execution().prefilter() : item.execution().candidate();
                if (stage != null) mergeReference(references, stage.id(), stage.versionNo());
            }
        }
        if (frozenLabels != null) for (var label : frozenLabels.labels()) for (var binding : label.bindings())
            mergeReference(references, binding.getRuleId(), binding.getRuleVersionNo());
        List<JsonNode> frozenRules = new ArrayList<>();
        boolean requiresLlm = false;
        for (var reference : references.entrySet()) {
            var current = rules.selectById(reference.getKey());
            if (current == null || "DISABLED".equals(current.getStatus())) throw IqcException.invalidState("规则不存在或已停用: " + reference.getKey());
            var version = ruleVersions.selectOne(Wrappers.<QualityRuleVersion>lambdaQuery()
                    .eq(QualityRuleVersion::getRuleId, reference.getKey())
                    .eq(QualityRuleVersion::getVersionNo, reference.getValue())
                    .eq(QualityRuleVersion::getStatus, "PUBLISHED"));
            if (version == null) throw IqcException.invalidArgument("规则版本未发布: " + reference.getKey() + " V" + reference.getValue());
            RuleMatcher.validate(version.getRuleType(), version.getExpression(), mapper);
            ObjectNode snapshot = mapper.valueToTree(version);
            snapshot.put("id", reference.getKey());
            // Business scoring is authoritative; detector result rows must not accidentally charge old prices.
            snapshot.put("deduction", 0); snapshot.put("veto", false);
            attachLabelTargets(snapshot, frozenLabels);
            var scopedItems = definition.items().stream().filter(item -> item.rule().id().equals(reference.getKey())).toList();
            if (!routed && scopedItems.stream().anyMatch(item -> item.inputScope() != null)) {
                if (!"LLM".equalsIgnoreCase(version.getRuleType()))
                    throw IqcException.invalidArgument("显式输入范围仅用于 LLM 检查，普通规则与 DLS 保持自身范围");
                var scope = scopedItems.getFirst().inputScope() == null ? SchemeDefinition.InputScope.MESSAGE : scopedItems.getFirst().inputScope();
                if (scopedItems.stream().anyMatch(item -> (item.inputScope() == null ? SchemeDefinition.InputScope.MESSAGE : item.inputScope()) != scope)
                        || definition.items().stream().anyMatch(item -> item.appliesWhen() != null && item.appliesWhen().id().equals(reference.getKey()))
                        || snapshot.has("labelFactTargets") && scope != SchemeDefinition.InputScope.CONVERSATION)
                    throw IqcException.invalidArgument("同一检测依赖的输入范围不一致，不能复用不同上下文的结果");
                snapshot.put("inspectionScope", scope.name());
            }
            frozenRules.add(snapshot);
            requiresLlm |= "LLM".equalsIgnoreCase(version.getRuleType());
        }
        JsonNode agent = null;
        if (requiresLlm) {
            if (definition.agent() == null) throw IqcException.invalidArgument("包含 LLM 检查的方案必须引用智能体版本");
            var reference = definition.agent();
            var current = agents.selectById(reference.id());
            if (current == null || "DISABLED".equals(current.getStatus())) throw IqcException.invalidState("智能体不存在或已停用");
            var version = agentVersions.selectOne(Wrappers.<QualityAgentVersion>lambdaQuery()
                    .eq(QualityAgentVersion::getAgentId, reference.id())
                    .eq(QualityAgentVersion::getVersionNo, reference.versionNo())
                    .eq(QualityAgentVersion::getStatus, "PUBLISHED"));
            if (version == null) throw IqcException.invalidArgument("智能体版本未发布");
            try {
                JsonNode config = mapper.readTree(version.getConfigJson());
                if (config == null || !"3.0".equals(config.path("schemaVersion").asText())
                        || !config.path("assetSnapshots").path("primaryModel").isObject())
                    throw IqcException.invalidArgument("业务方案请引用已发布的新版 LLM 能力型智能体");
                assets.validate(mapper.treeToValue(config, io.github.opensabre.iqc.agent.AgentConfiguration.class));
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw IqcException.invalidArgument("智能体版本配置无效");
            }
            ObjectNode snapshot = mapper.valueToTree(version); snapshot.put("id", reference.id()); agent = snapshot;
        } else if (definition.agent() != null && !(routed && sharesLlmCapability(definition))) {
            throw IqcException.invalidArgument("纯规则方案无需绑定智能体，请移除无效引用");
        }
        var plan = routed ? compileRoutes(definition, frozenRules, agent, mapper) : null;
        return new Dependencies(List.copyOf(frozenRules), agent, requiresLlm ? "INDEPENDENT" : "RULE_ONLY", frozenLabels, plan);
    }

    /** Context keys include frozen parameters, role, scope, phase, upstream stage and Agent capability. */
    private static RoutePlan compileRoutes(SchemeDefinition definition, List<JsonNode> rules, JsonNode agent, ObjectMapper mapper) {
        var byId = new java.util.LinkedHashMap<String, JsonNode>();
        rules.forEach(rule -> byId.put(rule.path("id").asText(), rule));
        var contexts = new java.util.LinkedHashMap<String, DetectionContext>();
        var items = new ArrayList<ItemRoute>();
        for (var item : definition.items()) {
            String gate = null;
            if (item.appliesWhen() != null) {
                var gateRule = byId.get(item.appliesWhen().id());
                if (gateRule == null || gateRule.path("versionNo").asInt() != item.appliesWhen().versionNo())
                    throw IqcException.invalidArgument("逐项适用条件规则版本不一致: " + item.itemCode());
                gate = context(contexts, gateRule, null, "APPLICABILITY", null, agent, mapper);
            }
            var finalRule = byId.get(item.rule().id());
            boolean finalLlm = "LLM".equalsIgnoreCase(finalRule.path("ruleType").asText());
            var execution = item.execution();
            var route = execution == null ? (finalLlm ? SchemeDefinition.Route.LLM_ONLY : SchemeDefinition.Route.RULE_ONLY) : execution.route();
            boolean expectsLlm = route == SchemeDefinition.Route.LLM_ONLY || route == SchemeDefinition.Route.RULE_THEN_LLM;
            if (finalLlm != expectsLlm)
                throw IqcException.invalidArgument("逐项路线与最终检测规则类型不一致: " + item.itemCode());
            if (!finalLlm && item.inputScope() != null)
                throw IqcException.invalidArgument("确定性最终检测不能配置 LLM 输入范围");
            String upstream = null;
            if (route == SchemeDefinition.Route.RULE_THEN_LLM || route == SchemeDefinition.Route.LLM_THEN_RULE) {
                var reference = route == SchemeDefinition.Route.RULE_THEN_LLM ? execution.prefilter() : execution.candidate();
                var stageRule = byId.get(reference.id());
                boolean stageLlm = "LLM".equalsIgnoreCase(stageRule.path("ruleType").asText());
                if (stageLlm != (route == SchemeDefinition.Route.LLM_THEN_RULE))
                    throw IqcException.invalidArgument("逐项路线与中间阶段规则类型不一致: " + item.itemCode());
                upstream = context(contexts, stageRule, execution.stageInputScope(),
                        route == SchemeDefinition.Route.RULE_THEN_LLM ? "PREFILTER" : "CANDIDATE", null, agent, mapper);
            }
            String finalKey = context(contexts, finalRule, item.inputScope(),
                    upstream == null ? "DIRECT" : route == SchemeDefinition.Route.RULE_THEN_LLM ? "REVIEW" : "VERIFY", upstream, agent, mapper);
            items.add(new ItemRoute(item.itemCode(), route, upstream, finalKey, gate));
        }
        var labelContextKeys = new ArrayList<String>();
        for (var rule : rules) if (rule.path("labelFactTargets").isArray() && !rule.path("labelFactTargets").isEmpty()) {
            SchemeDefinition.InputScope scope = "LLM".equalsIgnoreCase(rule.path("ruleType").asText())
                    ? SchemeDefinition.InputScope.CONVERSATION : null;
            String key = context(contexts, rule, scope, "DIRECT", null, agent, mapper);
            if (!labelContextKeys.contains(key)) labelContextKeys.add(key);
        }
        String schemaVersion = items.stream().anyMatch(item -> item.applicabilityContextKey() != null)
                ? "iqc-item-execution-plan-v2" : "iqc-item-execution-plan-v1";
        return new RoutePlan(schemaVersion, List.copyOf(contexts.values()), List.copyOf(items), List.copyOf(labelContextKeys));
    }

    private static String context(java.util.Map<String, DetectionContext> contexts, JsonNode rule, SchemeDefinition.InputScope scope,
                                  String phase, String upstream, JsonNode agent, ObjectMapper mapper) {
        boolean llm = "LLM".equalsIgnoreCase(rule.path("ruleType").asText());
        String effectiveScope = llm ? (scope == null ? SchemeDefinition.InputScope.MESSAGE : scope).name()
                : "DLS".equalsIgnoreCase(rule.path("ruleType").asText()) ? "CONVERSATION" : "MESSAGE";
        var payload = mapper.createObjectNode();
        payload.set("rule", rule); payload.put("scope", effectiveScope); payload.put("phase", phase);
        if (upstream != null) payload.put("upstream", upstream);
        if (llm) payload.set("agent", agent);
        String key = "ctx-" + InspectionSchemeService.contentHash(payload.toString());
        contexts.putIfAbsent(key, new DetectionContext(key, rule.path("id").asText(), rule.path("versionNo").asInt(),
                effectiveScope, phase, upstream));
        return key;
    }

    /** Recompiles only frozen inputs; live drafts or asset bodies cannot replace an execution plan. */
    public static void validateRouteTaskProjection(JsonNode snapshot, String agentSnapshotJson, ObjectMapper mapper) {
        if (snapshot == null || !snapshot.has("schemeSnapshot")) return;
        var release = snapshot.path("schemeSnapshot").path("release");
        var dependencies = release.path("dependencies");
        var rawDefinition = release.path("definition");
        boolean routed = SchemeDefinition.ROUTED_TASK_SCHEMA.equals(rawDefinition.path("schemaVersion").asText());
        routed |= rawDefinition.hasNonNull("executionVariants") || release.has("selectedVariantCode")
                || snapshot.path("schemeSnapshot").has("selectedVariantCode");
        for (var item : rawDefinition.path("items")) routed |= item.hasNonNull("execution");
        if (!routed) {
            if (dependencies.hasNonNull("itemExecutionPlan"))
                throw IqcException.invalidState("无逐项路线方案不能携带执行计划");
            return;
        }
        var definition = SchemeResultEvaluator.definition(snapshot, mapper);
        // Validate the complete frozen dependency set without re-resolving mutable rule versions.
        if (release.hasNonNull("variantDependencies")) {
            try {
                mapper.treeToValue(release, InspectionSchemeService.ReleaseSnapshot.class);
            } catch (Exception invalidDependencies) {
                throw IqcException.invalidState("变体依赖快照不完整或与选中变体不一致");
            }
        }
        var selectedCode = snapshot.path("schemeSnapshot").path("selectedVariantCode");
        if (definition.executionVariants() != null) {
            if (!selectedCode.isTextual() || selectedCode.asText().isBlank())
                throw IqcException.invalidState("策略变体任务缺少冻结的所选编码");
            if (!selectedCode.equals(release.path("selectedVariantCode")))
                throw IqcException.invalidState("所选策略编码与冻结方案摘要不一致");
            var expanded = definition.executionVariants().expand(definition, selectedCode.asText());
            if (!expanded.items().equals(definition.items()))
                throw IqcException.invalidState("任务路线与所选允许策略变体不一致");
        } else if (!selectedCode.isMissingNode() || release.has("selectedVariantCode")) {
            throw IqcException.invalidState("未配置允许变体的任务不能携带所选编码");
        }
        if (!SchemeDefinition.ROUTED_TASK_SCHEMA.equals(definition.schemaVersion())
                || definition.items().stream().noneMatch(item -> item.execution() != null))
            throw IqcException.invalidState("逐项路线任务协议与配置不一致");
        if (!dependencies.path("rules").isArray() || !snapshot.path("rules").equals(dependencies.path("rules")))
            throw IqcException.invalidState("逐项路线任务检测依赖与冻结方案不一致");
        boolean hasLabels = definition.labels() != null && !definition.labels().isEmpty();
        var frozenLabels = dependencies.path("labels");
        if (hasLabels != frozenLabels.isObject())
            throw IqcException.invalidState("逐项路线联合标签依赖与方案引用不一致");
        if (hasLabels) {
            var labelScope = frozenLabels.toString();
            validateJointTaskProjection(snapshot, labelScope, mapper);
        }
        var expected = new java.util.LinkedHashMap<String, Integer>();
        for (var item : definition.items()) {
            expected.put(item.rule().id(), item.rule().versionNo());
            if (item.appliesWhen() != null) expected.put(item.appliesWhen().id(), item.appliesWhen().versionNo());
            if (item.execution() != null) {
                var stage = item.execution().prefilter() != null ? item.execution().prefilter() : item.execution().candidate();
                if (stage != null) expected.put(stage.id(), stage.versionNo());
            }
        }
        if (hasLabels) for (var label : frozenLabels.path("labels")) for (var binding : label.path("bindings")) {
            String id = binding.path("ruleId").asText();
            int version = binding.path("ruleVersionNo").asInt(0);
            Integer previous = expected.putIfAbsent(id, version);
            if (id.isBlank() || version < 1 || previous != null && previous != version)
                throw IqcException.invalidState("逐项路线标签检测规则版本无效或冲突");
        }
        var frozenRules = new ArrayList<JsonNode>();
        for (var rule : dependencies.path("rules")) {
            String id = rule.path("id").asText();
            boolean hasTargets = rule.path("labelFactTargets").isArray() && !rule.path("labelFactTargets").isEmpty();
            if (!rule.isObject() || !java.util.Objects.equals(expected.remove(id), rule.path("versionNo").asInt())
                    || !"PUBLISHED".equals(rule.path("status").asText()) || !rule.path("deduction").isNumber()
                    || rule.path("deduction").decimalValue().signum() != 0 || !rule.path("veto").isBoolean()
                    || rule.path("veto").asBoolean() || hasTargets && !hasLabels)
                throw IqcException.invalidState("逐项路线检测版本缺失、重复或包含额外消费职责");
            RuleMatcher.validate(rule.path("ruleType").asText(), rule.path("expression").asText(), mapper);
            frozenRules.add(rule);
        }
        if (!expected.isEmpty()) throw IqcException.invalidState("逐项路线缺少检测阶段");
        var agent = dependencies.path("agent");
        boolean llm = frozenRules.stream().anyMatch(rule -> "LLM".equalsIgnoreCase(rule.path("ruleType").asText()));
        try {
            var actualAgent = agentSnapshotJson == null || agentSnapshotJson.isBlank() ? null : mapper.readTree(agentSnapshotJson);
            if (llm) {
                var reference = definition.agent();
                if (reference == null || !agent.isObject() || !reference.id().equals(agent.path("id").asText())
                        || reference.versionNo() != agent.path("versionNo").asInt() || !"PUBLISHED".equals(agent.path("status").asText())
                        || !agent.equals(actualAgent))
                    throw IqcException.invalidState("逐项路线智能体版本或任务快照不一致");
                var config = mapper.readTree(agent.path("configJson").asText());
                if (config == null || !"3.0".equals(config.path("schemaVersion").asText())
                        || !config.path("assetSnapshots").path("primaryModel").isObject())
                    throw IqcException.invalidState("逐项路线冻结智能体能力无效");
            } else if (definition.agent() != null && !sharesLlmCapability(definition)
                    || !agent.isMissingNode() && !agent.isNull() || actualAgent != null && !actualAgent.isNull()) {
                throw IqcException.invalidState("纯规则逐项路线不能携带智能体");
            }
            var recomputed = mapper.valueToTree(compileRoutes(definition, frozenRules, llm ? agent : null, mapper));
            if (!recomputed.equals(dependencies.path("itemExecutionPlan")))
                throw IqcException.invalidState("逐项路线执行计划与冻结输入不一致");
            if (!InspectionSchemeService.contentHash(release.toString()).equals(snapshot.path("schemeSnapshot").path("contentHash").asText()))
                throw IqcException.invalidState("逐项路线方案快照摘要不一致");
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw IqcException.invalidState("逐项路线智能体快照无法读取");
        }
        if (release.hasNonNull("variantDependencies")) validateFrozenAlternatives(snapshot, release, mapper);
    }

    /** A template capability reference is not an executed Agent when the selected alternative contains no LLM detector. */
    private static boolean sharesLlmCapability(SchemeDefinition definition) {
        return definition.executionVariants() != null && definition.executionVariants().variants().stream()
                .flatMap(variant -> variant.routes().values().stream())
                .anyMatch(execution -> execution.route() != SchemeDefinition.Route.RULE_ONLY);
    }

    /** Reuse route projection validation for every alternative, with no database reads or recursive dependency map. */
    private static void validateFrozenAlternatives(JsonNode snapshot, JsonNode release, ObjectMapper mapper) {
        try {
            var frozen = mapper.treeToValue(release, InspectionSchemeService.ReleaseSnapshot.class);
            for (var variant : frozen.definition().executionVariants().variants()) {
                var selected = frozen.selectVariant(variant.code());
                var projectionRelease = new InspectionSchemeService.ReleaseSnapshot(selected.name(), selected.code(),
                        selected.description(), selected.businessScene(), selected.definition(), selected.dependencies(), selected.selectedVariantCode());
                ObjectNode projection = snapshot.deepCopy();
                ObjectNode scheme = (ObjectNode) projection.path("schemeSnapshot");
                // Read back through the persisted JSON path so decimal nodes retain their original comparison semantics.
                var projectedRelease = mapper.readTree(mapper.writeValueAsString(projectionRelease));
                scheme.set("release", projectedRelease);
                scheme.put("selectedVariantCode", selected.selectedVariantCode());
                scheme.put("contentHash", InspectionSchemeService.contentHash(projectedRelease.toString()));
                projection.set("rules", projectedRelease.path("dependencies").path("rules"));
                var agent = selected.dependencies().agent();
                validateRouteTaskProjection(projection, agent == null || agent.isNull() ? null : agent.toString(), mapper);
            }
        } catch (Exception invalidAlternative) {
            throw IqcException.invalidState("允许变体的冻结检测依赖或执行计划无效");
        }
    }

    /** Frozen preview records link business consumers to context-sensitive detector stages. */
    public record DetectionContext(String contextKey, String ruleId, int versionNo, String inputScope, String phase,
                                   @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                   String upstreamContextKey) { }
    public record ItemRoute(String itemCode, SchemeDefinition.Route route,
                            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                            String stageContextKey, String finalContextKey,
                            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                            String applicabilityContextKey) {
        /** Keeps historical plans without a condition byte-for-byte compatible. */
        public ItemRoute(String itemCode, SchemeDefinition.Route route, String stageContextKey, String finalContextKey) {
            this(itemCode, route, stageContextKey, finalContextKey, null);
        }
    }
    public record RoutePlan(String schemaVersion, List<DetectionContext> contexts, List<ItemRoute> items,
                            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                            List<String> labelContextKeys) {
        public RoutePlan {
            labelContextKeys = labelContextKeys == null ? List.of() : List.copyOf(labelContextKeys);
        }
        public RoutePlan(String schemaVersion, List<DetectionContext> contexts, List<ItemRoute> items) {
            this(schemaVersion, contexts, items, List.of());
        }
    }

    /** Frozen recognition targets belong to detector input, not Agent configuration or mutable label lookups at run time. */
    private void attachLabelTargets(ObjectNode rule, io.github.opensabre.iqc.label.LabelResolutionService.ResolvedSelection selection) {
        if (selection == null) return;
        var targets = mapper.createArrayNode();
        String ruleType = rule.path("ruleType").asText("KEYWORD").toUpperCase(java.util.Locale.ROOT);
        boolean modelRule = "LLM".equals(ruleType);
        for (var label : selection.labels()) {
            boolean bound = label.bindings().stream().anyMatch(binding -> rule.path("id").asText().equals(binding.getRuleId()));
            if (!bound) continue;
            if (!List.of("user", "customer", "agent").contains(label.targetRole()))
                throw IqcException.invalidArgument("联合标签必须明确识别客户或坐席主体: " + label.id());
            if (label.values() == null || label.values().isEmpty() || label.values().size() > 10)
                throw IqcException.invalidArgument("联合标签必须定义 1 到 10 个识别值: " + label.id());
            if (!modelRule && !List.of("KEYWORD", "CONTAINS", "FORBIDDEN_CONTAINS", "REGEX", "FORBIDDEN_REGEX",
                    "STARTS_WITH", "ENDS_WITH").contains(ruleType))
                throw IqcException.invalidArgument("联合标签普通规则必须提供可定位原文的正向匹配: " + rule.path("id").asText());
            String targetRole = rule.path("targetRole").asText("all");
            if (!modelRule && !targetRole.isBlank() && !"all".equalsIgnoreCase(targetRole)
                    && !label.targetRole().equalsIgnoreCase(targetRole))
                throw IqcException.invalidArgument("联合标签与普通规则的说话人范围不一致: " + label.id());
            var codes = new java.util.HashSet<String>();
            int mappedValues = 0;
            for (var value : label.values()) {
                if (value == null || value.getValueCode() == null || value.getValueCode().isBlank()
                        || value.getValueCode().length() > 64 || !codes.add(value.getValueCode()))
                    throw IqcException.invalidArgument("标签值编码无效或重复: " + label.id());
                if (value.getValueType() == null || !List.of("FIXED", "BOOLEAN", "PERCENTAGE", "DURATION_MONTHS", "MONTH", "DATE").contains(value.getValueType()))
                    throw IqcException.invalidArgument("标签值类型不受支持: " + label.id());
                var target = targets.addObject().put("labelId", label.id()).put("labelVersionNo", label.versionNo())
                        .put("labelName", label.name()).put("valueCode", value.getValueCode()).put("valueType", value.getValueType())
                        .put("description", value.getDescription()).put("subjectRole", label.targetRole())
                        .put("subjectKind", "CURRENT_PARTICIPANT").put("ruleId", rule.path("id").asText());
                // Config defaults are legacy hit projection behavior, never facts or model instructions.
                target.put("ruleVersionNo", rule.path("versionNo").asInt());
                if (!modelRule) {
                    JsonNode mapped = mappedHitValue(value, rule.path("id").asText());
                    if (mapped != null) { target.set("onRuleHitValue", mapped.deepCopy()); mappedValues++; }
                }
            }
            if (!modelRule && mappedValues != 1)
                throw IqcException.invalidArgument("联合标签普通规则必须明确且仅映射一个命中值: " + label.id() + "/" + rule.path("id").asText());
        }
        // Preserve exact legacy snapshot shape when no recognition target uses this detector.
        if (!targets.isEmpty()) rule.set("labelFactTargets", targets);
    }

    private JsonNode mappedHitValue(io.github.opensabre.iqc.label.model.LabelValueDefinition value, String ruleId) {
        if (value.getConfigJson() == null || value.getConfigJson().isBlank()) return null;
        try {
            JsonNode values = mapper.readTree(value.getConfigJson()).path("onRuleHit");
            JsonNode mapped = values.get(ruleId);
            if (mapped != null && !io.github.opensabre.iqc.label.LabelFactEvaluator.validValue(value.getValueType(), mapped))
                throw IqcException.invalidArgument("标签命中值类型与定义不一致: " + value.getValueCode());
            return mapped;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw IqcException.invalidArgument("标签命中值配置无效: " + value.getValueCode());
        }
    }

    private void mergeReference(java.util.Map<String, Integer> references, String id, Integer versionNo) {
        if (id == null || id.isBlank() || versionNo == null || versionNo < 1)
            throw IqcException.invalidArgument("检测依赖缺少明确规则版本");
        Integer previous = references.putIfAbsent(id, versionNo);
        if (previous != null && !previous.equals(versionNo))
            throw IqcException.invalidArgument("检查与标签不能引用同一规则的不同版本: " + id);
    }

    public record Dependencies(List<JsonNode> rules, JsonNode agent, String executionMode,
                               @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                               io.github.opensabre.iqc.label.LabelResolutionService.ResolvedSelection labels,
                               @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                               RoutePlan itemExecutionPlan) {
        public Dependencies(List<JsonNode> rules, JsonNode agent, String executionMode,
                            io.github.opensabre.iqc.label.LabelResolutionService.ResolvedSelection labels) {
            this(rules, agent, executionMode, labels, null);
        }
        public Dependencies(List<JsonNode> rules, JsonNode agent, String executionMode) {
            this(rules, agent, executionMode, null, null);
        }
    }
}
