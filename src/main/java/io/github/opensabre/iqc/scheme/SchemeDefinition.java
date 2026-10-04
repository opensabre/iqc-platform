package io.github.opensabre.iqc.scheme;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.scoring.InspectionScoring;

import java.util.HashSet;
import java.util.List;

/** Business-owned checks and scoring; detector references always include an explicit published version. */
public record SchemeDefinition(String schemaVersion, List<Item> items, AgentReference agent,
                               InspectionScoring.Policy scoring,
                               @JsonInclude(JsonInclude.Include.NON_NULL)
                               RunLimits runLimits,
                               @JsonInclude(JsonInclude.Include.NON_NULL)
                               List<io.github.opensabre.iqc.label.LabelResolutionService.LabelReference> labels,
                               @JsonInclude(JsonInclude.Include.NON_NULL)
                               SchemeExecutionVariants executionVariants) {
    public static final String SCHEMA = "iqc-scheme-v2";
    /** Older readers reject this marker instead of silently discarding appliesWhen. */
    public static final String CONDITIONAL_TASK_SCHEMA = "iqc-scheme-v2-applicability-v1";
    /** Older readers must reject joint tasks rather than silently discarding their label outputs. */
    public static final String JOINT_TASK_SCHEMA = "iqc-scheme-v2-joint-v1";
    /** Explicit LLM input scopes cannot be interpreted by older task readers. */
    public static final String SCOPED_TASK_SCHEMA = "iqc-scheme-v2-input-scope-v1";
    /** Route-aware readers must not silently execute staged checks as independent detectors. */
    public static final String ROUTED_TASK_SCHEMA = "iqc-scheme-v2-item-routes-v1";
    public enum Route { RULE_ONLY, LLM_ONLY, RULE_THEN_LLM, LLM_THEN_RULE }
    public enum InputScope { MESSAGE, CONVERSATION }
    public enum HitMeaning { VIOLATION, COMPLIANCE }
    public record RuleReference(String id, int versionNo) { }
    public record AgentReference(String id, int versionNo) { }
    /** Finite expert-owned stages; rule on Item remains the final business detector. */
    public record Execution(Route route,
                            @JsonInclude(JsonInclude.Include.NON_NULL) RuleReference prefilter,
                            @JsonInclude(JsonInclude.Include.NON_NULL) RuleReference candidate,
                            @JsonInclude(JsonInclude.Include.NON_NULL) InputScope stageInputScope,
                            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean prefilterCoversViolation) {
        public Execution {
            if (route == null) throw IqcException.invalidArgument("必须指定逐项执行路线");
            if (route == Route.RULE_THEN_LLM) {
                if (prefilter == null || candidate != null || stageInputScope != null || !Boolean.TRUE.equals(prefilterCoversViolation))
                    throw IqcException.invalidArgument("规则初筛路线必须引用初筛规则并确认违规覆盖，不能配置候选阶段或 LLM 初筛范围");
            } else if (route == Route.LLM_THEN_RULE) {
                if (candidate == null || prefilter != null || prefilterCoversViolation != null)
                    throw IqcException.invalidArgument("LLM 候选路线必须引用候选规则，不能配置初筛阶段");
            } else if (prefilter != null || candidate != null || stageInputScope != null || prefilterCoversViolation != null) {
                throw IqcException.invalidArgument("单阶段路线不能携带中间阶段配置");
            }
            var stage = prefilter != null ? prefilter : candidate;
            if (stage != null && (stage.id() == null || stage.id().isBlank() || stage.versionNo() < 1))
                throw IqcException.invalidArgument("中间阶段必须引用明确的规则版本");
        }
    }
    /** Optional business applicability gate; a definite hit enables the check, not a deduction. */
    public record Item(String itemCode, String name, RuleReference rule, HitMeaning hitMeaning,
                       @JsonInclude(JsonInclude.Include.NON_NULL) RuleReference appliesWhen,
                       @JsonInclude(JsonInclude.Include.NON_NULL) InputScope inputScope,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Execution execution) {
        public Item(String itemCode, String name, RuleReference rule, HitMeaning hitMeaning, RuleReference appliesWhen, InputScope inputScope) {
            this(itemCode, name, rule, hitMeaning, appliesWhen, inputScope, null);
        }
        public Item(String itemCode, String name, RuleReference rule, HitMeaning hitMeaning, RuleReference appliesWhen) {
            this(itemCode, name, rule, hitMeaning, appliesWhen, null, null);
        }
        public Item(String itemCode, String name, RuleReference rule, HitMeaning hitMeaning) {
            this(itemCode, name, rule, hitMeaning, null, null, null);
        }
    }

    /** Optional expert-owned limits frozen with the published template, not detector configuration. */
    public record RunLimits(int maxConversations, int defaultConcurrency, int maxConcurrency) {
        public RunLimits {
            if (maxConversations < 1 || maxConversations > 1000)
                throw IqcException.invalidArgument("模板会话上限必须在 1 到 1000 之间");
            if (maxConcurrency < 1 || maxConcurrency > 32 || defaultConcurrency < 1 || defaultConcurrency > maxConcurrency)
                throw IqcException.invalidArgument("模板默认并发必须在 1 到最大并发之间，最大并发不得超过 32");
        }
    }

    /** Preserve historical callers and JSON: absent limits must not alter frozen release hashes. */
    public SchemeDefinition(String schemaVersion, List<Item> items, AgentReference agent, InspectionScoring.Policy scoring) {
        this(schemaVersion, items, agent, scoring, null, null);
    }

    /** Keeps runtime-limit callers compatible while labels remain an optional protocol extension. */
    public SchemeDefinition(String schemaVersion, List<Item> items, AgentReference agent, InspectionScoring.Policy scoring, RunLimits runLimits) {
        this(schemaVersion, items, agent, scoring, runLimits, null);
    }

    /** Absent alternatives preserve the historical definition JSON and all existing callers. */
    public SchemeDefinition(String schemaVersion, List<Item> items, AgentReference agent, InspectionScoring.Policy scoring,
                            RunLimits runLimits, List<io.github.opensabre.iqc.label.LabelResolutionService.LabelReference> labels) {
        this(schemaVersion, items, agent, scoring, runLimits, labels, null);
    }

    /** Reject incomplete task protocols while allowing frozen conditions, scopes and joint outputs. */
    public void requireExecutable() {
        requireTrialExecutable();
    }

    /** The legacy per-message evaluator cannot interpret an item-route execution graph. */
    public void requireSupportedRoutes() {
        if (executionVariants != null || ROUTED_TASK_SCHEMA.equals(schemaVersion) || items.stream().anyMatch(item -> item.execution() != null))
            throw IqcException.invalidState("逐项路线必须通过逐项执行器评估，不能使用旧检测投影");
    }

    /** Routed releases require a complete frozen route map; ordinary scoped and joint protocols remain valid. */
    public void requireTrialExecutable() {
        boolean routed = items.stream().anyMatch(item -> item.execution() != null);
        if (executionVariants != null && !routed)
            throw IqcException.invalidState("允许策略变体尚未展开为明确执行路线");
        if (ROUTED_TASK_SCHEMA.equals(schemaVersion) && !routed)
            throw IqcException.invalidState("逐项路线任务缺少明确执行配置");
    }

    /** Resolve operational defaults without adding a serialized property to historical snapshots. */
    public RunLimits effectiveRunLimits() {
        return runLimits == null ? new RunLimits(1000, 1, 32) : runLimits;
    }

    /** Keep authoring compatible, but make conditional execution an explicit protocol boundary. */
    public SchemeDefinition forTaskSnapshot() {
        // Even an unexpanded authoring definition must retain its variants and fail closed at execution.
        if (executionVariants != null || items.stream().anyMatch(item -> item.execution() != null))
            return ROUTED_TASK_SCHEMA.equals(schemaVersion) ? this
                    : new SchemeDefinition(ROUTED_TASK_SCHEMA, items, agent, scoring, runLimits, labels, executionVariants);
        if (items.stream().anyMatch(item -> item.inputScope() != null))
            return SCOPED_TASK_SCHEMA.equals(schemaVersion) ? this
                    : new SchemeDefinition(SCOPED_TASK_SCHEMA, items, agent, scoring, runLimits, labels);
        if (labels != null && !labels.isEmpty())
            return JOINT_TASK_SCHEMA.equals(schemaVersion) ? this
                    : new SchemeDefinition(JOINT_TASK_SCHEMA, items, agent, scoring, runLimits, labels);
        if (items.stream().noneMatch(item -> item.appliesWhen() != null)) return this;
        return new SchemeDefinition(CONDITIONAL_TASK_SCHEMA, items, agent, scoring, runLimits, labels);
    }

    public SchemeDefinition {
        if (!SCHEMA.equals(schemaVersion) && !CONDITIONAL_TASK_SCHEMA.equals(schemaVersion) && !JOINT_TASK_SCHEMA.equals(schemaVersion)
                && !SCOPED_TASK_SCHEMA.equals(schemaVersion) && !ROUTED_TASK_SCHEMA.equals(schemaVersion))
            throw IqcException.invalidArgument("不支持的业务方案版本");
        // A label-only trial has no business checks or score; an entirely empty scheme is invalid.
        if (items == null || items.size() > 200 || (items.isEmpty() && (labels == null || labels.isEmpty())))
            throw IqcException.invalidArgument("方案必须包含质检项或画像标签，质检项最多 200 个");
        if (items.isEmpty() && scoring != null && !scoring.items().isEmpty())
            throw IqcException.invalidArgument("仅打标方案不能配置评分项目");
        var codes = new HashSet<String>();
        var ruleVersions = new java.util.HashMap<String, Integer>();
        for (Item item : items) {
            if (item == null || item.itemCode() == null || !item.itemCode().matches("[A-Za-z0-9_-]{1,64}") || !codes.add(item.itemCode()))
                throw IqcException.invalidArgument("质检项编码无效或重复");
            if (item.name() == null || item.name().isBlank() || item.name().length() > 100 || item.hitMeaning() == null)
                throw IqcException.invalidArgument("质检项名称与命中语义不能为空");
            if (item.rule() == null || item.rule().id() == null || item.rule().id().isBlank() || item.rule().versionNo() < 1)
                throw IqcException.invalidArgument("每个质检项必须引用明确的规则版本");
            Integer previous = ruleVersions.putIfAbsent(item.rule().id(), item.rule().versionNo());
            if (previous != null && previous != item.rule().versionNo())
                throw IqcException.invalidArgument("同一方案暂不支持引用同一规则的不同版本");
            var execution = item.execution();
            if (execution != null) {
                if (execution.route() == Route.RULE_THEN_LLM && item.hitMeaning() != HitMeaning.VIOLATION)
                    throw IqcException.invalidArgument("规则初筛路线仅适用于违规检测，不能用初筛未命中证明合规");
                var stage = execution.prefilter() != null ? execution.prefilter() : execution.candidate();
                if (stage != null) {
                    if (stage.id().equals(item.rule().id()))
                        throw IqcException.invalidArgument("中间阶段不能引用最终检测规则自身");
                    Integer priorStage = ruleVersions.putIfAbsent(stage.id(), stage.versionNo());
                    if (priorStage != null && priorStage != stage.versionNo())
                        throw IqcException.invalidArgument("同一方案暂不支持引用同一规则的不同版本");
                }
                if ((execution.route() == Route.RULE_ONLY || execution.route() == Route.LLM_THEN_RULE) && item.inputScope() != null)
                    throw IqcException.invalidArgument("确定性最终检测不能配置 LLM 输入范围");
            }
            var condition = item.appliesWhen();
            if (condition != null) {
                if (condition.id() == null || condition.id().isBlank() || condition.versionNo() < 1)
                    throw IqcException.invalidArgument("适用条件必须引用明确的规则版本");
                if (condition.id().equals(item.rule().id()))
                    throw IqcException.invalidArgument("适用条件不能引用该质检项自身的检测规则");
                Integer priorCondition = ruleVersions.putIfAbsent(condition.id(), condition.versionNo());
                if (priorCondition != null && priorCondition != condition.versionNo())
                    throw IqcException.invalidArgument("同一方案暂不支持引用同一规则的不同版本");
            }
        }
        if (agent != null && (agent.id() == null || agent.id().isBlank() || agent.versionNo() < 1))
            throw IqcException.invalidArgument("智能体必须引用明确的已发布版本");
        if (scoring == null) throw IqcException.invalidArgument("必须明确评分政策；不计分时使用空评分项目列表");
        if (scoring.items().stream().anyMatch(item -> !codes.contains(item.itemCode())))
            throw IqcException.invalidArgument("评分项目必须引用方案中的质检项");
        items = List.copyOf(items);
        if (labels != null) {
            var labelIds = new HashSet<String>();
            if (labels.size() > 200) throw IqcException.invalidArgument("方案最多选择 200 个标签");
            for (var label : labels) {
                if (label == null || label.id() == null || label.id().isBlank() || label.versionNo() < 1 || !labelIds.add(label.id()))
                    throw IqcException.invalidArgument("方案标签引用无效或重复");
            }
            labels = List.copyOf(labels);
        }
        // Validate every permitted map against the same business checks without recursive variant expansion.
        if (executionVariants != null)
            executionVariants.validate(new SchemeDefinition(schemaVersion, items, agent, scoring, runLimits, labels));
    }
}
