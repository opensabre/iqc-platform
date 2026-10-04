package io.github.opensabre.iqc.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.agent.dao.QualityAgentMapper;
import io.github.opensabre.iqc.agent.model.QualityAgent;
import io.github.opensabre.iqc.rule.dao.QualityRuleMapper;
import io.github.opensabre.iqc.rule.model.QualityRule;
import io.github.opensabre.iqc.rule.QualityRuleSetService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.model.TaskExecution;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.shared.IqcPage;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.LabelResolutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
@RequiredArgsConstructor
public class InspectionTaskService {
    private final InspectionTaskMapper taskMapper;
    private final ConversationMapper conversationMapper;
    private final QualityAgentMapper agentMapper;
    private final QualityRuleMapper ruleMapper;
    private final QualityRuleSetService ruleSetService;
    private final ObjectMapper objectMapper;
    private final TaskExecutionMapper executionMapper;
    private final IqcDataScope dataScope;
    private final LabelResolutionService labelResolutionService;

    /** Creates tasks with an explicit execution strategy while preserving legacy label scoring semantics. */
    @Transactional
    public InspectionTask createConfigured(String name, String taskType, List<String> conversationIds,
                                           ScheduledFilter filter, LocalDateTime scheduledTime, Integer sampleSize, String seed,
                                           String agentId, String ruleSetId, List<String> ruleIds, Integer concurrency,
                                           LabelResolutionService.LabelSelection labels, LabelExecutionOptions options,
                                           String executionMode) {
        LabelResolutionService.ResolvedSelection resolved = labels == null ? null : labelResolutionService.resolve(labels);
        List<String> effectiveRules = ruleIds;
        String effectiveSet = ruleSetId;
        if (resolved != null) {
            boolean hasChecks = (ruleIds != null && !ruleIds.isEmpty()) || (ruleSetId != null && !ruleSetId.isBlank());
            // Combining dependency lists before scheme-level scoring exists would charge label-only rules.
            if (hasChecks) throw IqcException.invalidArgument("检查与标签联合执行需使用业务方案逐项评分，当前兼容入口不支持同时指定规则和标签");
            effectiveRules = resolved.ruleIds();
            effectiveSet = null;
        }
        InspectionTask task;
        if ("SCHEDULED".equalsIgnoreCase(taskType))
            task = createScheduled(name, filter, scheduledTime, agentId, effectiveSet, effectiveRules, concurrency, executionMode);
        else if ("SAMPLE".equalsIgnoreCase(taskType))
            task = createSampled(name, filter, sampleSize == null ? 100 : sampleSize, seed, agentId, effectiveSet, effectiveRules, concurrency, executionMode);
        else if (taskType == null || "BATCH".equalsIgnoreCase(taskType))
            task = createBatch(name, conversationIds, agentId, effectiveSet, effectiveRules, concurrency, executionMode);
        else throw IqcException.invalidArgument("不支持的任务类型: " + taskType);
        return resolved == null ? task : applyLabelConfiguration(task, resolved, options);
    }

    /** Creates a batch whose business label selection is resolved server-side into the existing rule snapshot. */
    @Transactional
    public InspectionTask createBatchWithLabels(String name, List<String> conversationIds, String agentId,
                                                LabelResolutionService.LabelSelection selection, Integer concurrencyLimit,
                                                LabelExecutionOptions options) {
        LabelResolutionService.ResolvedSelection resolved = labelResolutionService.resolve(selection);
        InspectionTask task = createBatch(name, conversationIds, agentId, null, resolved.ruleIds(), concurrencyLimit);
        return applyLabelConfiguration(task, resolved, options);
    }

    /** Creates a scheduled label task while preserving creation-time taxonomy and rule versions. */
    @Transactional
    public InspectionTask createScheduledWithLabels(String name, ScheduledFilter filter, LocalDateTime scheduledTime,
                                                    String agentId, LabelResolutionService.LabelSelection selection,
                                                    Integer concurrencyLimit, LabelExecutionOptions options) {
        LabelResolutionService.ResolvedSelection resolved = labelResolutionService.resolve(selection);
        InspectionTask task = createScheduled(name, filter, scheduledTime, agentId, null, resolved.ruleIds(), concurrencyLimit);
        return applyLabelConfiguration(task, resolved, options);
    }

    /** Creates a reproducible sampled task using a version-pinned label selection. */
    @Transactional
    public InspectionTask createSampledWithLabels(String name, ScheduledFilter filter, int sampleSize, String seed,
                                                  String agentId, LabelResolutionService.LabelSelection selection,
                                                  Integer concurrencyLimit, LabelExecutionOptions options) {
        LabelResolutionService.ResolvedSelection resolved = labelResolutionService.resolve(selection);
        InspectionTask task = createSampled(name, filter, sampleSize, seed, agentId, null, resolved.ruleIds(), concurrencyLimit);
        return applyLabelConfiguration(task, resolved, options);
    }

    private InspectionTask applyLabelConfiguration(InspectionTask task, LabelResolutionService.ResolvedSelection resolved,
                                                    LabelExecutionOptions requested) {
        LabelExecutionOptions options = requested == null ? new LabelExecutionOptions(1, null, false, null) : requested;
        int runCount = options.runCount() == null ? 1 : options.runCount();
        if (runCount < 1 || runCount > 5) throw IqcException.invalidArgument("文档运行次数必须在 1 到 5 之间");
        if (options.confidenceThreshold() != null && (options.confidenceThreshold().compareTo(java.math.BigDecimal.ZERO) < 0 || options.confidenceThreshold().compareTo(java.math.BigDecimal.ONE) > 0)) throw IqcException.invalidArgument("置信度阈值必须在 0 到 1 之间");
        if (options.autoExpandPrompt() != null && options.autoExpandPrompt().length() > 1000) throw IqcException.invalidArgument("自动补充说明不能超过 1000 字符");
        task.setLabelScopeSnapshotJson(writeSnapshot(resolved)); task.setRunCount(runCount);
        task.setConfidenceThreshold(options.confidenceThreshold()); task.setAutoExpandEnabled(Boolean.TRUE.equals(options.autoExpandEnabled()));
        task.setAutoExpandPrompt(Boolean.TRUE.equals(options.autoExpandEnabled()) ? options.autoExpandPrompt() : null);
        task.setQueuePriority(0L); task.setPauseRequested(false); task.setCancelRequested(false); taskMapper.updateById(task); return task;
    }

    @Transactional
    public InspectionTask create(String name, String conversationId, String agentId, String ruleSetId, List<String> requestedRuleIds) {
        return createBatch(name, List.of(conversationId), agentId, ruleSetId, requestedRuleIds, 1);
    }

    /** Creates one immutable batch over explicitly selected conversations. */
    @Transactional
    public InspectionTask createBatch(String name, List<String> requestedConversationIds, String agentId,
                                      String ruleSetId, List<String> requestedRuleIds, Integer concurrencyLimit) {
        return createBatch(name, requestedConversationIds, agentId, ruleSetId, requestedRuleIds, concurrencyLimit, null);
    }

    /** Freezes a task-owned strategy; a null mode retains the legacy Agent configuration contract. */
    @Transactional
    public InspectionTask createBatch(String name, List<String> requestedConversationIds, String agentId,
                                      String ruleSetId, List<String> requestedRuleIds, Integer concurrencyLimit, String executionMode) {
        return createBatchWithSnapshot(name, requestedConversationIds, concurrencyLimit,
                task -> snapshotAgentAndRules(task, agentId, ruleSetId, requestedRuleIds, executionMode));
    }

    /** Internal entry point: callers must load this immutable version through the scheme data-scope service. */
    @Transactional
    public InspectionTask createFromScheme(String name, List<String> conversationIds, Integer concurrencyLimit,
                                           io.github.opensabre.iqc.scheme.model.InspectionSchemeVersion version) {
        return createFromScheme(name, conversationIds, concurrencyLimit, version, null, null);
    }

    /** Accepts a server-derived request identity so a duplicate insert can safely converge on the existing task. */
    @Transactional
    public InspectionTask createFromScheme(String name, List<String> conversationIds, Integer concurrencyLimit,
                                           io.github.opensabre.iqc.scheme.model.InspectionSchemeVersion version,
                                           String requestTaskId, String requestFingerprint) {
        return createFromScheme(name, conversationIds, concurrencyLimit, version, requestTaskId, requestFingerprint, null);
    }

    /** Selects only a frozen allowed variant before any task insert. */
    @Transactional
    public InspectionTask createFromScheme(String name, List<String> conversationIds, Integer concurrencyLimit,
                                           io.github.opensabre.iqc.scheme.model.InspectionSchemeVersion version,
                                           String requestTaskId, String requestFingerprint, String variantCode) {
        var published = readPublishedSchemeSnapshot(version, requestFingerprint, variantCode);
        return createUsingSchemeSnapshot(name, conversationIds, concurrencyLimit, published.release(), published.scheme(), requestTaskId, 1, null);
    }

    /** Freezes the published template now while resolving matching conversation data only when due. */
    @Transactional
    public InspectionTask createScheduledFromScheme(String name, ScheduledFilter requestedFilter, LocalDateTime scheduledTime,
                                                     Integer concurrencyLimit,
                                                     io.github.opensabre.iqc.scheme.model.InspectionSchemeVersion version,
                                                     String requestTaskId, String requestFingerprint) {
        return createScheduledFromScheme(name, requestedFilter, scheduledTime, concurrencyLimit, version, requestTaskId, requestFingerprint, null);
    }

    /** Freezes the allowed variant at schedule creation, not when mutable data is selected at execution time. */
    @Transactional
    public InspectionTask createScheduledFromScheme(String name, ScheduledFilter requestedFilter, LocalDateTime scheduledTime,
                                                     Integer concurrencyLimit,
                                                     io.github.opensabre.iqc.scheme.model.InspectionSchemeVersion version,
                                                     String requestTaskId, String requestFingerprint, String variantCode) {
        if (scheduledTime == null || !scheduledTime.isAfter(LocalDateTime.now()))
            throw IqcException.invalidArgument("计划执行时间必须晚于当前时间");
        ScheduledFilter filter = (requestedFilter == null
                ? new ScheduledFilter(null, null, null, "IMPORTED", null, 1000) : requestedFilter).normalized();
        if (!"IMPORTED".equalsIgnoreCase(filter.status()))
            throw IqcException.invalidArgument("业务模板定时任务仅支持筛选已导入会话");
        if (filter.ownerGroupId() != null || filter.employeeId() != null || filter.customerExternalId() != null
                || filter.channel() != null || filter.businessNo() != null)
            throw IqcException.invalidArgument("业务模板定时任务不接受组织或会话身份覆盖条件");
        if (filter.fileName() != null && filter.fileName().length() > 255)
            throw IqcException.invalidArgument("导入文件名筛选不能超过 255 个字符");
        LocalDateTime createdFrom = parseTime(filter.createdFrom(), "开始时间");
        LocalDateTime createdTo = parseTime(filter.createdTo(), "结束时间");
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo))
            throw IqcException.invalidArgument("数据创建开始时间不能晚于结束时间");
        var published = readPublishedSchemeSnapshot(version, requestFingerprint, variantCode);
        var frozen = freezeSchemeTask(published.release(), published.scheme(), concurrencyLimit);
        int frozenLimit = Math.min(filter.limit(), frozen.maxConversations());
        filter = new ScheduledFilter(filter.createdFrom(), filter.createdTo(), filter.fileName(), "IMPORTED",
                null, frozenLimit, null, null, null, null);
        Map<String, Object> selection = new java.util.LinkedHashMap<>();
        selection.put("filter", filter);
        selection.put("scopeAll", dataScope.canViewAll());
        selection.put("scopeOwner", dataScope.owner());
        selection.put("scopeGroupId", dataScope.groupId());

        InspectionTask task = new InspectionTask();
        if (requestTaskId != null) task.setId(requestTaskId);
        task.setName(name == null || name.isBlank() ? "定时质检-" + scheduledTime : name.trim());
        task.setTaskType("SCHEDULED");
        task.setSelectionFilterJson(writeSnapshot(selection));
        task.setScheduledTime(scheduledTime);
        task.setConcurrencyLimit(frozen.concurrency());
        task.setRunCount(1);
        applyFrozenSchemeTask(task, frozen, null, null);
        task.setOwnerGroupId(dataScope.groupId());
        task.setStatus("SCHEDULED");
        task.setConversationIdsJson(writeSnapshot(List.of()));
        task.setTotalMessages(0);
        task.setProcessedMessages(0);
        task.setFailedMessages(0);
        task.setAttemptCount(0);
        taskMapper.insert(task);
        return task;
    }

    /** Internal expert trial: same task engine, an explicit draft identity, and a small data bound. */
    @Transactional
    public InspectionTask createSchemeTrial(String schemeId, int draftRevision, String name, List<String> conversationIds,
                                            io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot release) {
        return createSchemeTrial(schemeId, draftRevision, name, conversationIds, release, null, null);
    }

    /** Accepts a stable task identity for safe trial creation retries without changing the frozen release. */
    @Transactional
    public InspectionTask createSchemeTrial(String schemeId, int draftRevision, String name, List<String> conversationIds,
                                            io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot release,
                                            String requestTaskId, String requestFingerprint) {
        String selected = release == null ? null : release.selectedVariantCode();
        return createSchemeTrial(schemeId, draftRevision, name, conversationIds, release, requestTaskId, requestFingerprint, selected);
    }

    /** Freeze the selected approved code alongside its expanded routes, before any task insertion. */
    @Transactional
    public InspectionTask createSchemeTrial(String schemeId, int draftRevision, String name, List<String> conversationIds,
                                            io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot release,
                                            String requestTaskId, String requestFingerprint, String selectedVariantCode) {
        return createSchemeTrial(schemeId, draftRevision, name, conversationIds, release, requestTaskId,
                requestFingerprint, selectedVariantCode, 1, null);
    }

    /** Freeze expert-selected adjudication parameters with the immutable trial task. */
    @Transactional
    public InspectionTask createSchemeTrial(String schemeId, int draftRevision, String name, List<String> conversationIds,
                                            io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot release,
                                            String requestTaskId, String requestFingerprint, String selectedVariantCode,
                                            Integer requestedRunCount, java.math.BigDecimal confidenceThreshold) {
        if (conversationIds == null || conversationIds.isEmpty() || conversationIds.size() > 20)
            throw IqcException.invalidArgument("草稿试跑请选择 1 到 20 个会话");
        int runCount = requestedRunCount == null ? 1 : requestedRunCount;
        if (runCount < 1 || runCount > 5) throw IqcException.invalidArgument("裁决轮数必须在 1 到 5 之间");
        if (confidenceThreshold != null && (confidenceThreshold.compareTo(java.math.BigDecimal.ZERO) < 0
                || confidenceThreshold.compareTo(java.math.BigDecimal.ONE) > 0))
            throw IqcException.invalidArgument("一致率门槛必须在 0 到 1 之间");
        if (release != null && release.definition() != null) {
            release = release.forTaskSnapshot();
        }
        var scheme = objectMapper.createObjectNode();
        scheme.put("kind", "DRAFT_TRIAL"); scheme.put("schemeId", schemeId); scheme.put("draftRevision", draftRevision);
        if (selectedVariantCode != null) scheme.put("selectedVariantCode", selectedVariantCode);
        try {
            // Freeze and hash the same read-back JSON form used by execution and publication validation.
            scheme.set("release", objectMapper.readTree(writeSnapshot(release)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalidRelease) {
            throw IqcException.invalidState("方案快照无法冻结");
        }
        scheme.put("contentHash", io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(scheme.path("release").toString()));
        if (requestFingerprint != null) scheme.put("requestFingerprint", requestFingerprint);
        return createUsingSchemeSnapshot("[方案试跑] " + name, conversationIds, 1, release, scheme, requestTaskId,
                runCount, confidenceThreshold);
    }

    private InspectionTask createUsingSchemeSnapshot(String name, List<String> conversationIds, Integer concurrencyLimit,
                                                      io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot release,
                                                      com.fasterxml.jackson.databind.node.ObjectNode scheme, String requestTaskId,
                                                      int runCount, java.math.BigDecimal confidenceThreshold) {
        var frozen = freezeSchemeTask(release, scheme, concurrencyLimit);
        long conversationCount = conversationIds == null ? 0 : conversationIds.stream()
                .filter(id -> id != null && !id.isBlank()).distinct().count();
        if (conversationCount > frozen.maxConversations())
            throw IqcException.invalidArgument("超过模板会话上限: " + frozen.maxConversations());
        return createBatchWithSnapshot(name, conversationIds, frozen.concurrency(), task -> {
            if (requestTaskId != null) task.setId(requestTaskId);
            applyFrozenSchemeTask(task, frozen, runCount, confidenceThreshold);
        });
    }

    private FrozenSchemeTask freezeSchemeTask(io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot release,
                                               com.fasterxml.jackson.databind.node.ObjectNode scheme, Integer concurrencyLimit) {
        var dependencies = release == null ? null : release.dependencies();
        if (release == null || release.definition() == null || dependencies == null || dependencies.rules() == null || dependencies.rules().isEmpty())
            throw IqcException.invalidState("方案快照缺少检测依赖");
        if ("DRAFT_TRIAL".equals(scheme.path("kind").asText())) release.definition().requireTrialExecutable();
        else {
            release.definition().requireExecutable();
        }
        var limits = release.definition().effectiveRunLimits();
        int selectedConcurrency = concurrencyLimit == null ? limits.defaultConcurrency() : concurrencyLimit;
        if (selectedConcurrency < 1 || selectedConcurrency > limits.maxConcurrency())
            throw IqcException.invalidArgument("并发数必须在 1 到模板上限 " + limits.maxConcurrency() + " 之间");
        var mode = TaskExecutionStrategy.parseRequested(dependencies.executionMode());
        if (mode != TaskExecutionStrategy.Mode.RULE_ONLY && mode != TaskExecutionStrategy.Mode.INDEPENDENT)
            throw IqcException.invalidState("方案暂仅支持纯规则或独立执行策略");
        var root = objectMapper.createObjectNode(); root.set("schemeSnapshot", scheme);
        root.set("rules", objectMapper.valueToTree(dependencies.rules()));
        root.set("executionStrategy", objectMapper.valueToTree(new TaskExecutionStrategy("1.0", mode)));
        if (io.github.opensabre.iqc.scheme.SchemeDefinition.ROUTED_TASK_SCHEMA.equals(release.definition().schemaVersion()))
            io.github.opensabre.iqc.scheme.SchemeDependencyResolver.validateRouteTaskProjection(root,
                    dependencies.agent() == null ? null : dependencies.agent().toString(), objectMapper);
        else io.github.opensabre.iqc.scheme.SchemeResultEvaluator.evaluate(release.definition(), root, List.of(), List.of(), objectMapper);
        io.github.opensabre.iqc.scheme.SchemeDependencyResolver.validateJointTaskProjection(root,
                dependencies.labels() == null ? null : writeSnapshot(dependencies.labels()), objectMapper);
        return new FrozenSchemeTask(root, writeSnapshot(dependencies.rules().stream().map(rule -> rule.path("id").asText()).toList()),
                dependencies.agent() == null || dependencies.agent().isNull() ? null : dependencies.agent().path("id").asText(),
                dependencies.agent() == null || dependencies.agent().isNull() ? null : dependencies.agent().toString(),
                dependencies.labels() == null ? null : writeSnapshot(dependencies.labels()),
                selectedConcurrency, limits.maxConversations());
    }

    private void applyFrozenSchemeTask(InspectionTask task, FrozenSchemeTask frozen, Integer runCount,
                                      java.math.BigDecimal confidenceThreshold) {
        task.setRuleSnapshotJson(frozen.ruleSnapshot().toString());
        task.setRuleIdsJson(frozen.ruleIdsJson());
        task.setAgentId(frozen.agentId());
        task.setAgentSnapshotJson(frozen.agentSnapshotJson());
        task.setLabelScopeSnapshotJson(frozen.labelSnapshotJson());
        if (runCount != null) task.setRunCount(runCount);
        if (confidenceThreshold != null) task.setConfidenceThreshold(confidenceThreshold);
    }

    private PublishedSchemeSnapshot readPublishedSchemeSnapshot(
            io.github.opensabre.iqc.scheme.model.InspectionSchemeVersion version, String requestFingerprint, String variantCode) {
        if (version == null || version.getSchemeId() == null || version.getVersionNo() == null || version.getVersionNo() < 1)
            throw IqcException.invalidArgument("必须选择明确的方案发布版本");
        try {
            String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(version.getSnapshotJson().getBytes(StandardCharsets.UTF_8)));
            if (!hash.equals(version.getContentHash())) throw IqcException.invalidState("方案发布快照校验失败");
            var release = objectMapper.readValue(version.getSnapshotJson(), io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot.class);
            release = release.selectVariant(variantCode);
            var scheme = objectMapper.createObjectNode();
            scheme.put("kind", "PUBLISHED");
            scheme.put("schemeId", version.getSchemeId()); scheme.put("versionNo", version.getVersionNo());
            scheme.put("contentHash", hash); scheme.set("release", objectMapper.readTree(version.getSnapshotJson()));
            var taskDefinition = release.definition().forTaskSnapshot();
            if (!taskDefinition.equals(release.definition())) {
                release = new io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot(
                        release.name(), release.code(), release.description(), release.businessScene(), taskDefinition,
                        release.dependencies(), release.selectedVariantCode(), release.variantDependencies());
                var selectedJson = objectMapper.readTree(writeSnapshot(release));
                scheme.put("publishedContentHash", hash);
                if (release.selectedVariantCode() != null) scheme.put("selectedVariantCode", release.selectedVariantCode());
                scheme.set("release", selectedJson);
                scheme.put("contentHash", io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(selectedJson.toString()));
            }
            if (requestFingerprint != null) scheme.put("requestFingerprint", requestFingerprint);
            return new PublishedSchemeSnapshot(release, scheme);
        } catch (JsonProcessingException | java.security.NoSuchAlgorithmException exception) {
            throw IqcException.invalidState("方案发布快照无效");
        }
    }

    private record PublishedSchemeSnapshot(io.github.opensabre.iqc.scheme.InspectionSchemeService.ReleaseSnapshot release,
                                           com.fasterxml.jackson.databind.node.ObjectNode scheme) { }
    private record FrozenSchemeTask(com.fasterxml.jackson.databind.node.ObjectNode ruleSnapshot, String ruleIdsJson,
                                    String agentId, String agentSnapshotJson, String labelSnapshotJson,
                                    int concurrency, int maxConversations) { }

    private InspectionTask createBatchWithSnapshot(String name, List<String> requestedConversationIds, Integer concurrencyLimit,
                                                    java.util.function.Consumer<InspectionTask> snapshot) {
        List<String> conversationIds = requestedConversationIds == null ? List.of() : requestedConversationIds.stream()
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (conversationIds.isEmpty()) throw IqcException.invalidArgument("至少选择一个会话");
        if (conversationIds.size() > 1000) throw IqcException.invalidArgument("单个批次最多选择 1000 个会话");
        int safeConcurrency = concurrencyLimit == null ? 1 : concurrencyLimit;
        if (safeConcurrency < 1 || safeConcurrency > 32) throw IqcException.invalidArgument("并发数必须在 1 到 32 之间");
        List<Conversation> conversations = new ArrayList<>();
        for (String conversationId : conversationIds) {
            Conversation conversation = conversationMapper.selectById(conversationId);
            if (conversation == null) throw IqcException.notFound("会话不存在: " + conversationId);
            if (!dataScope.canView(conversation.getCreatedBy(), conversation.getOwnerGroupId())) throw IqcException.accessDenied("无权使用该会话创建任务");
            conversations.add(conversation);
        }

        InspectionTask task = new InspectionTask();
        task.setName(name == null || name.isBlank() ? "批量质检-" + conversations.size() + "个会话" : name.trim());
        task.setTaskType("BATCH");
        task.setConversationId(conversationIds.size() == 1 ? conversationIds.get(0) : null);
        task.setConversationIdsJson(writeSnapshot(conversationIds));
        task.setConcurrencyLimit(safeConcurrency);
        snapshot.accept(task);
        String ownerGroupId = conversations.get(0).getOwnerGroupId();
        task.setOwnerGroupId(conversations.stream().allMatch(item -> java.util.Objects.equals(ownerGroupId, item.getOwnerGroupId())) ? ownerGroupId : null);
        task.setStatus("CREATED");
        task.setTotalMessages(conversations.stream().map(Conversation::getMessageCount).filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum());
        task.setProcessedMessages(0);
        task.setFailedMessages(0);
        task.setAttemptCount(0);
        taskMapper.insert(task);
        return task;
    }

    /** Saves selection criteria now; matching conversations are resolved only when the schedule becomes due. */
    @Transactional
    public InspectionTask createScheduled(String name, ScheduledFilter filter, LocalDateTime scheduledTime,
                                           String agentId, String ruleSetId, List<String> requestedRuleIds,
                                           Integer concurrencyLimit) {
        return createScheduled(name, filter, scheduledTime, agentId, ruleSetId, requestedRuleIds, concurrencyLimit, null);
    }

    /** Freezes the execution strategy now while scheduled data selection is materialized when due. */
    @Transactional
    public InspectionTask createScheduled(String name, ScheduledFilter filter, LocalDateTime scheduledTime,
                                           String agentId, String ruleSetId, List<String> requestedRuleIds,
                                           Integer concurrencyLimit, String executionMode) {
        if (scheduledTime == null || !scheduledTime.isAfter(LocalDateTime.now()))
            throw IqcException.invalidArgument("计划执行时间必须晚于当前时间");
        int safeConcurrency = concurrencyLimit == null ? 1 : concurrencyLimit;
        if (safeConcurrency < 1 || safeConcurrency > 32) throw IqcException.invalidArgument("并发数必须在 1 到 32 之间");
        ScheduledFilter safeFilter = filter == null ? new ScheduledFilter(null, null, null, "IMPORTED", null, 1000) : filter.normalized();
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("filter", safeFilter);
        snapshot.put("scopeAll", dataScope.canViewAll());
        snapshot.put("scopeOwner", dataScope.owner());
        snapshot.put("scopeGroupId", dataScope.groupId());
        InspectionTask task = new InspectionTask();
        task.setName(name == null || name.isBlank() ? "定时质检-" + scheduledTime : name.trim());
        task.setTaskType("SCHEDULED"); task.setSelectionFilterJson(writeSnapshot(snapshot));
        task.setScheduledTime(scheduledTime); task.setConcurrencyLimit(safeConcurrency); task.setAgentId(agentId);
        snapshotAgentAndRules(task, agentId, ruleSetId, requestedRuleIds, executionMode);
        task.setStatus("SCHEDULED"); task.setTotalMessages(0); task.setProcessedMessages(0);
        task.setFailedMessages(0); task.setAttemptCount(0); task.setOwnerGroupId((String) snapshot.get("scopeGroupId"));
        taskMapper.insert(task);
        return task;
    }

    /** Creates an immutable, reproducible random sample from the caller's visible conversations. */
    @Transactional
    public InspectionTask createSampled(String name, ScheduledFilter requestedFilter, int sampleSize, String seed,
                                        String agentId, String ruleSetId, List<String> ruleIds, Integer concurrencyLimit) {
        return createSampled(name, requestedFilter, sampleSize, seed, agentId, ruleSetId, ruleIds, concurrencyLimit, null);
    }

    /** Applies the same strategy contract to reproducible sampling as to explicit batches. */
    @Transactional
    public InspectionTask createSampled(String name, ScheduledFilter requestedFilter, int sampleSize, String seed,
                                        String agentId, String ruleSetId, List<String> ruleIds, Integer concurrencyLimit, String executionMode) {
        if (sampleSize < 1 || sampleSize > 1000) throw IqcException.invalidArgument("抽样数量必须在 1 到 1000 之间");
        ScheduledFilter filter = (requestedFilter == null
                ? new ScheduledFilter(null, null, null, "IMPORTED", null, 1000) : requestedFilter).normalized();
        var query = Wrappers.<Conversation>lambdaQuery()
                .eq(filter.status() != null, Conversation::getStatus, filter.status())
                .like(filter.fileName() != null, Conversation::getSourceFileName, filter.fileName())
                .eq(filter.employeeId() != null, Conversation::getEmployeeId, filter.employeeId())
                .eq(filter.customerExternalId() != null, Conversation::getCustomerExternalId, filter.customerExternalId())
                .eq(filter.channel() != null, Conversation::getChannel, filter.channel())
                .eq(filter.businessNo() != null, Conversation::getBusinessNo, filter.businessNo())
                .eq(filter.ownerGroupId() != null, Conversation::getOwnerGroupId, filter.ownerGroupId())
                .orderByAsc(Conversation::getId).last("LIMIT " + filter.limit());
        if (!dataScope.canViewAll()) query.and(q -> q.eq(Conversation::getCreatedBy, dataScope.owner())
                .or(dataScope.groupId() != null, nested -> nested.eq(Conversation::getOwnerGroupId, dataScope.groupId())));
        String stableSeed = seed == null || seed.isBlank() ? java.time.LocalDate.now().toString() : seed.trim();
        List<String> selected = conversationMapper.selectList(query).stream().map(Conversation::getId)
                .sorted(java.util.Comparator.comparing(id -> sampleKey(stableSeed, id))).limit(sampleSize).toList();
        if (selected.isEmpty()) throw IqcException.invalidArgument("当前筛选条件没有可抽样会话");
        InspectionTask task = createBatch(name == null || name.isBlank() ? "抽样质检-" + selected.size() + "个会话" : name,
                selected, agentId, ruleSetId, ruleIds, concurrencyLimit, executionMode);
        task.setTaskType("SAMPLE");
        task.setSelectionFilterJson(writeSnapshot(Map.of("filter", filter, "sampleSize", sampleSize,
                "seed", stableSeed, "selectedConversationIds", selected)));
        taskMapper.updateById(task);
        return task;
    }

    private String sampleKey(String seed, String id) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((seed + ":" + id).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    /** Atomically claims due schedules and resolves conversations against current data. */
    @Transactional
    public List<InspectionTask> materializeDue(LocalDateTime now) {
        List<InspectionTask> due = taskMapper.selectList(Wrappers.<InspectionTask>lambdaQuery()
                .eq(InspectionTask::getStatus, "SCHEDULED").le(InspectionTask::getScheduledTime, now)
                .orderByDesc(InspectionTask::getQueuePriority)
                .orderByAsc(InspectionTask::getScheduledTime).orderByAsc(InspectionTask::getCreatedTime).last("LIMIT 20"));
        List<InspectionTask> ready = new ArrayList<>();
        for (InspectionTask candidate : due) {
            int claimed = taskMapper.update(null, Wrappers.<InspectionTask>lambdaUpdate()
                    .set(InspectionTask::getStatus, "MATERIALIZING").eq(InspectionTask::getId, candidate.getId())
                    .eq(InspectionTask::getStatus, "SCHEDULED"));
            if (claimed != 1) continue;
            ScheduledSelection selection = readSelection(candidate.getSelectionFilterJson());
            ScheduledFilter filter = selection.filter().normalized();
            var query = Wrappers.<Conversation>lambdaQuery();
            query.eq(filter.status() != null, Conversation::getStatus, filter.status())
                    .like(filter.fileName() != null, Conversation::getSourceFileName, filter.fileName())
                    .eq(filter.employeeId() != null, Conversation::getEmployeeId, filter.employeeId())
                    .eq(filter.customerExternalId() != null, Conversation::getCustomerExternalId, filter.customerExternalId())
                    .eq(filter.channel() != null, Conversation::getChannel, filter.channel())
                    .eq(filter.businessNo() != null, Conversation::getBusinessNo, filter.businessNo())
                    .ge(filter.createdFrom() != null, Conversation::getCreatedTime, parseTime(filter.createdFrom(), "开始时间"))
                    .le(filter.createdTo() != null, Conversation::getCreatedTime, parseTime(filter.createdTo(), "结束时间"))
                    .eq(filter.ownerGroupId() != null, Conversation::getOwnerGroupId, filter.ownerGroupId())
                    .orderByAsc(Conversation::getCreatedTime).last("LIMIT " + filter.limit());
            if (!selection.scopeAll()) query.and(q -> q.eq(Conversation::getCreatedBy, selection.scopeOwner())
                    .or(selection.scopeGroupId() != null, nested -> nested.eq(Conversation::getOwnerGroupId, selection.scopeGroupId())));
            List<Conversation> conversations = conversationMapper.selectList(query);
            candidate.setConversationIdsJson(writeSnapshot(conversations.stream().map(Conversation::getId).toList()));
            candidate.setConversationId(conversations.size() == 1 ? conversations.get(0).getId() : null);
            candidate.setTotalMessages(conversations.stream().map(Conversation::getMessageCount).filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum());
            candidate.setStatus(conversations.isEmpty() ? "NO_DATA" : "CREATED");
            taskMapper.updateById(candidate);
            if (!conversations.isEmpty()) ready.add(candidate);
        }
        return ready;
    }

    private void snapshotAgentAndRules(InspectionTask task, String agentId, String ruleSetId, List<String> requestedRuleIds, String executionMode) {
        TaskExecutionStrategy.Mode mode = TaskExecutionStrategy.parseRequested(executionMode);
        QualityAgent agent = agentId == null || agentId.isBlank() ? null : agentMapper.selectById(agentId);
        if (agent == null && mode != TaskExecutionStrategy.Mode.RULE_ONLY)
            throw IqcException.invalidArgument("LLM 或旧版任务必须选择已发布 Agent，纯规则任务请显式选择 RULE_ONLY 策略");
        if (agentId != null && !agentId.isBlank() && (agent == null || !"PUBLISHED".equals(agent.getStatus())))
            throw IqcException.invalidArgument("只能选择已发布 Agent");
        if (agent != null && mode == null && "3.0".equals(agentConfiguration(agent).path("schemaVersion").asText()))
            throw IqcException.invalidArgument("新版 Agent 不持有质检模式，请在任务中选择执行策略");
        if (agent != null && mode != null && mode != TaskExecutionStrategy.Mode.RULE_ONLY
                && "RULE_ONLY".equalsIgnoreCase(agentConfiguration(agent).path("mode").asText()))
            throw IqcException.invalidArgument("普通规则旧版 Agent 不具备 LLM 能力，请选择模型型 Agent");
        String effectiveRuleSetId = ruleSetId;
        if ((effectiveRuleSetId == null || effectiveRuleSetId.isBlank()) && (requestedRuleIds == null || requestedRuleIds.isEmpty())) {
            effectiveRuleSetId = agent == null ? null : configuredRuleSetId(agent);
        }
        List<String> ruleIds = requestedRuleIds == null ? new ArrayList<>() : requestedRuleIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        QualityRuleSetService.PublishedRuleSet publishedSet = null;
        if (ruleIds.isEmpty() && effectiveRuleSetId != null && !effectiveRuleSetId.isBlank()) { publishedSet = ruleSetService.published(effectiveRuleSetId); ruleIds = publishedSet.ruleIds(); }
        if (ruleIds.isEmpty()) throw IqcException.invalidArgument("至少选择一条已发布规则");
        task.setRuleSetId(effectiveRuleSetId); task.setRuleIdsJson(writeSnapshot(ruleIds));
        task.setAgentId(mode == TaskExecutionStrategy.Mode.RULE_ONLY ? null : agentId);
        task.setAgentSnapshotJson(mode == TaskExecutionStrategy.Mode.RULE_ONLY ? null : writeSnapshot(agent));
        List<QualityRule> rules = new ArrayList<>();
        for (String ruleId : ruleIds) {
            QualityRule rule = ruleMapper.selectById(ruleId);
            if (rule == null || !"PUBLISHED".equals(rule.getStatus())) throw IqcException.invalidArgument("只能选择已发布规则");
            rules.add(rule);
        }
        if (mode == TaskExecutionStrategy.Mode.RULE_ONLY && rules.stream().anyMatch(rule -> "LLM".equalsIgnoreCase(rule.getRuleType())))
            throw IqcException.invalidArgument("纯规则策略不能包含 LLM 检测，请调整规则或执行策略");
        if (mode == TaskExecutionStrategy.Mode.AGENT_LLM && rules.stream().anyMatch(rule -> !"LLM".equalsIgnoreCase(rule.getRuleType())))
            throw IqcException.invalidArgument("纯 LLM 策略不能静默跳过普通规则，请选择逐项独立执行或调整规则");
        if (publishedSet == null) task.setRuleSnapshotJson(writeSnapshot(rules));
        else task.setRuleSnapshotJson(writeSnapshot(Map.of("ruleSetId", publishedSet.id(), "ruleSetName", publishedSet.name(),
                "ruleSetCode", publishedSet.code(), "ruleSetVersion", publishedSet.versionNo(),
                "aggregationMode", publishedSet.aggregationMode(), "rules", rules)));
        if (mode != null) {
            JsonNode current;
            try { current = objectMapper.readTree(task.getRuleSnapshotJson()); }
            catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
            com.fasterxml.jackson.databind.node.ObjectNode snapshot = current.isObject()
                    ? (com.fasterxml.jackson.databind.node.ObjectNode) current : objectMapper.createObjectNode().set("rules", current);
            snapshot.set("executionStrategy", objectMapper.valueToTree(new TaskExecutionStrategy("1.0", mode)));
            task.setRuleSnapshotJson(writeSnapshot(snapshot));
        }
    }

    private JsonNode agentConfiguration(QualityAgent agent) {
        try {
            JsonNode config = agent.getConfigJson() == null ? null : objectMapper.readTree(agent.getConfigJson());
            return config == null ? objectMapper.createObjectNode() : config;
        }
        catch (JsonProcessingException exception) { throw IqcException.invalidArgument("Agent 配置不是有效的结构化配置"); }
    }

    private String configuredRuleSetId(QualityAgent agent) {
        if (agent.getConfigJson() == null || agent.getConfigJson().isBlank()) return null;
        try {
            JsonNode config = objectMapper.readTree(agent.getConfigJson());
            return config == null ? null : config.path("ruleSetId").asText(null);
        } catch (Exception exception) {
            throw IqcException.invalidArgument("Agent 配置不是有效的结构化配置");
        }
    }

    private ScheduledSelection readSelection(String json) {
        try { return objectMapper.readValue(json, ScheduledSelection.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("定时任务筛选快照无效", exception); }
    }

    private LocalDateTime parseTime(String value, String label) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDateTime.parse(value); }
        catch (java.time.format.DateTimeParseException exception) { throw IqcException.invalidArgument(label + "格式无效"); }
    }

    public record ScheduledFilter(String createdFrom, String createdTo, String fileName, String status,
                                  String ownerGroupId, Integer limit, String employeeId,
                                  String customerExternalId, String channel, String businessNo) {
        public ScheduledFilter(String createdFrom, String createdTo, String fileName, String status,
                               String ownerGroupId, Integer limit) {
            this(createdFrom, createdTo, fileName, status, ownerGroupId, limit, null, null, null, null);
        }
        ScheduledFilter normalized() {
            int safeLimit = Math.min(Math.max(limit == null ? 1000 : limit, 1), 1000);
            return new ScheduledFilter(blankToNull(createdFrom), blankToNull(createdTo), blankToNull(fileName),
                    blankToNull(status) == null ? "IMPORTED" : status.trim(), blankToNull(ownerGroupId), safeLimit,
                    blankToNull(employeeId), blankToNull(customerExternalId), upper(channel), blankToNull(businessNo));
        }
        private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
        private static String upper(String value) { String result = blankToNull(value); return result == null ? null : result.toUpperCase(); }
    }
    private record ScheduledSelection(ScheduledFilter filter, boolean scopeAll, String scopeOwner, String scopeGroupId) { }

    private String writeSnapshot(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("任务配置快照生成失败", exception); }
    }

    public record LabelExecutionOptions(Integer runCount, java.math.BigDecimal confidenceThreshold,
                                        Boolean autoExpandEnabled, String autoExpandPrompt) { }

    public List<InspectionTask> list() {
        var query = Wrappers.<InspectionTask>lambdaQuery().ne(InspectionTask::getStatus, "DELETED").orderByDesc(InspectionTask::getCreatedTime);
        if (!dataScope.canViewAll()) {
            String groupId = dataScope.groupId();
            query.and(q -> q.eq(InspectionTask::getCreatedBy, dataScope.owner())
                    .or(groupId != null, nested -> nested.eq(InspectionTask::getOwnerGroupId, groupId)));
        }
        return taskMapper.selectList(query);
    }

    public IqcPage<InspectionTask> page(long current, long size) {
        return page(current, size, null, null, null);
    }

    /** Pages visible tasks and applies the task-list business filters. */
    public IqcPage<InspectionTask> page(long current, long size, String keyword, String status, String taskType) {
        Page<InspectionTask> page = new Page<>(safeCurrent(current), safeSize(size));
        var query = Wrappers.<InspectionTask>lambdaQuery().ne(InspectionTask::getStatus, "DELETED").orderByDesc(InspectionTask::getCreatedTime);
        if (!dataScope.canViewAll()) {
            String groupId = dataScope.groupId();
            query.and(q -> q.eq(InspectionTask::getCreatedBy, dataScope.owner())
                    .or(groupId != null, nested -> nested.eq(InspectionTask::getOwnerGroupId, groupId)));
        }
        if (keyword != null && !keyword.isBlank()) {
            String normalized = keyword.trim();
            query.and(q -> q.eq(InspectionTask::getId, normalized).or().like(InspectionTask::getName, normalized));
        }
        if (status != null && !status.isBlank()) query.eq(InspectionTask::getStatus, status.trim());
        if (taskType != null && !taskType.isBlank()) query.eq(InspectionTask::getTaskType, taskType.trim());
        return IqcPage.from(taskMapper.selectPage(page, query));
    }

    private long safeCurrent(long current) { return Math.max(1, current); }
    private long safeSize(long size) { return Math.min(Math.max(1, size), 100); }

    public void markDispatchFailed(String id) {
        taskMapper.update(null, Wrappers.<InspectionTask>lambdaUpdate().set(InspectionTask::getStatus, "FAILED")
                .eq(InspectionTask::getId, id).in(InspectionTask::getStatus, "CREATED", "MATERIALIZING"));
    }

    /** Converts abandoned queue/running leases into a retryable state after a process crash. */
    @Transactional
    public int recoverStaleExecutions(java.time.Duration timeout) {
        java.util.Date cutoff = java.util.Date.from(java.time.Instant.now().minus(timeout));
        List<InspectionTask> stale = taskMapper.selectList(Wrappers.<InspectionTask>lambdaQuery()
                .in(InspectionTask::getStatus, "QUEUED", "RUNNING")
                .lt(InspectionTask::getUpdatedTime, cutoff).last("LIMIT 100"));
        int recovered = 0;
        for (InspectionTask task : stale) {
            int changed = taskMapper.update(null, Wrappers.<InspectionTask>lambdaUpdate()
                    .set(InspectionTask::getStatus, "FAILED")
                    .eq(InspectionTask::getId, task.getId())
                    .eq(InspectionTask::getStatus, task.getStatus())
                    .lt(InspectionTask::getUpdatedTime, cutoff));
            if (changed != 1) continue;
            recovered++;
            if (task.getCurrentExecutionId() != null) {
                executionMapper.update(null, Wrappers.<TaskExecution>lambdaUpdate()
                        .set(TaskExecution::getStatus, "FAILED")
                        .set(TaskExecution::getErrorMessage, "执行心跳超时，可安全重试失败消息")
                        .eq(TaskExecution::getId, task.getCurrentExecutionId())
                        .in(TaskExecution::getStatus, "QUEUED", "RUNNING"));
            }
        }
        return recovered;
    }

    public InspectionTask get(String id) {
        InspectionTask task = taskMapper.selectById(id);
        if (task == null) throw IqcException.notFound("质检任务不存在: " + id);
        if (!dataScope.canView(task.getCreatedBy(), task.getOwnerGroupId())) throw IqcException.accessDenied("无权查看该质检任务");
        return task;
    }

    /** Lists authorized task attempts for explicit run-level report selection. */
    public List<TaskRunSummary> executions(String taskId) {
        InspectionTask task = get(taskId);
        return executionMapper.selectList(Wrappers.<TaskExecution>lambdaQuery()
                        .eq(TaskExecution::getTaskId, taskId).orderByAsc(TaskExecution::getAttemptNo))
                .stream().map(execution -> new TaskRunSummary(execution.getId(), execution.getAttemptNo(),
                        execution.getStatus(), execution.getProcessedMessages(), execution.getFailedMessages(),
                        execution.getCreatedTime(), execution.getId().equals(task.getCurrentExecutionId())))
                .toList();
    }

    public record TaskRunSummary(String id, Integer attemptNo, String status, Integer processedMessages,
                                 Integer failedMessages, java.util.Date createdTime, boolean current) { }

    @Transactional
    public InspectionTask cancel(String id) {
        InspectionTask task = taskMapper.selectById(id);
        if (task == null) throw IqcException.notFound("质检任务不存在: " + id);
        if (!dataScope.canView(task.getCreatedBy(), task.getOwnerGroupId())) throw IqcException.accessDenied("无权取消该质检任务");
        boolean running = "RUNNING".equals(task.getStatus()) || "PAUSE_REQUESTED".equals(task.getStatus());
        String targetStatus = running ? "CANCEL_REQUESTED" : "CANCELLED";
        int cancelled = taskMapper.update(null, Wrappers.<InspectionTask>lambdaUpdate()
                .set(InspectionTask::getStatus, targetStatus)
                .set(InspectionTask::getCancelRequested, true)
                .eq(InspectionTask::getId, id)
                .in(InspectionTask::getStatus, "SCHEDULED", "MATERIALIZING", "CREATED", "QUEUED", "RUNNING", "PAUSE_REQUESTED", "PAUSED"));
        if (cancelled == 1 && !running && task.getCurrentExecutionId() != null) {
            TaskExecution execution = executionMapper.selectById(task.getCurrentExecutionId());
            if (execution != null) { execution.setStatus("CANCELLED"); executionMapper.updateById(execution); }
        }
        return taskMapper.selectById(id);
    }

    @Transactional
    public InspectionTask requestPause(String id) {
        InspectionTask task = get(id);
        int changed = taskMapper.update(null, Wrappers.<InspectionTask>lambdaUpdate()
                .set(InspectionTask::getStatus, "PAUSE_REQUESTED")
                .set(InspectionTask::getPauseRequested, true)
                .eq(InspectionTask::getId, id)
                .eq(InspectionTask::getStatus, "RUNNING"));
        if (changed != 1) throw IqcException.invalidState("只有执行中的任务可以暂停");
        return taskMapper.selectById(id);
    }

    @Transactional
    public InspectionTask changePriority(String id, long priority) {
        get(id);
        int changed = taskMapper.update(null, Wrappers.<InspectionTask>lambdaUpdate()
                .set(InspectionTask::getQueuePriority, priority)
                .eq(InspectionTask::getId, id)
                .in(InspectionTask::getStatus, "CREATED", "SCHEDULED", "QUEUED", "PAUSED"));
        if (changed != 1) throw IqcException.invalidState("当前任务状态不允许调整优先级");
        return taskMapper.selectById(id);
    }

    @Transactional
    public void deleteTerminal(String id) {
        InspectionTask task = get(id);
        if (!List.of("SUCCEEDED", "PARTIAL_FAILED", "FAILED", "NO_DATA", "CANCELLED").contains(task.getStatus())) {
            throw IqcException.invalidState("只有终态任务可以删除");
        }
        int changed = taskMapper.update(null, Wrappers.<InspectionTask>lambdaUpdate()
                .set(InspectionTask::getStatus, "DELETED")
                .eq(InspectionTask::getId, id).eq(InspectionTask::getStatus, task.getStatus()));
        if (changed != 1) throw IqcException.invalidState("任务状态已变化，请刷新后重试");
    }
}
