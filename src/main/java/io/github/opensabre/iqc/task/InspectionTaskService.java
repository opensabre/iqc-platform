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

    @Transactional
    public InspectionTask create(String name, String conversationId, String agentId, String ruleSetId, List<String> requestedRuleIds) {
        return createBatch(name, List.of(conversationId), agentId, ruleSetId, requestedRuleIds, 1);
    }

    /** Creates one immutable batch over explicitly selected conversations. */
    @Transactional
    public InspectionTask createBatch(String name, List<String> requestedConversationIds, String agentId,
                                      String ruleSetId, List<String> requestedRuleIds, Integer concurrencyLimit) {
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
        if (agentId == null || agentId.isBlank()) throw IqcException.invalidArgument("必须选择已发布 Agent");

        InspectionTask task = new InspectionTask();
        task.setName(name == null || name.isBlank() ? "批量质检-" + conversations.size() + "个会话" : name.trim());
        task.setTaskType("BATCH");
        task.setConversationId(conversationIds.size() == 1 ? conversationIds.get(0) : null);
        task.setConversationIdsJson(writeSnapshot(conversationIds));
        task.setConcurrencyLimit(safeConcurrency);
        task.setAgentId(agentId);
        snapshotAgentAndRules(task, agentId, ruleSetId, requestedRuleIds);
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
        snapshotAgentAndRules(task, agentId, ruleSetId, requestedRuleIds);
        task.setStatus("SCHEDULED"); task.setTotalMessages(0); task.setProcessedMessages(0);
        task.setFailedMessages(0); task.setAttemptCount(0); task.setOwnerGroupId((String) snapshot.get("scopeGroupId"));
        taskMapper.insert(task);
        return task;
    }

    /** Creates an immutable, reproducible random sample from the caller's visible conversations. */
    @Transactional
    public InspectionTask createSampled(String name, ScheduledFilter requestedFilter, int sampleSize, String seed,
                                        String agentId, String ruleSetId, List<String> ruleIds, Integer concurrencyLimit) {
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
                selected, agentId, ruleSetId, ruleIds, concurrencyLimit);
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
                .orderByAsc(InspectionTask::getScheduledTime).last("LIMIT 20"));
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

    private void snapshotAgentAndRules(InspectionTask task, String agentId, String ruleSetId, List<String> requestedRuleIds) {
        if (agentId == null || agentId.isBlank()) throw IqcException.invalidArgument("必须选择已发布 Agent");
        QualityAgent agent = agentMapper.selectById(agentId);
        if (agent == null || !"PUBLISHED".equals(agent.getStatus())) throw IqcException.invalidArgument("只能选择已发布 Agent");
        String effectiveRuleSetId = ruleSetId;
        if ((effectiveRuleSetId == null || effectiveRuleSetId.isBlank()) && (requestedRuleIds == null || requestedRuleIds.isEmpty())) {
            effectiveRuleSetId = configuredRuleSetId(agent);
        }
        List<String> ruleIds = requestedRuleIds == null ? new ArrayList<>() : requestedRuleIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        QualityRuleSetService.PublishedRuleSet publishedSet = null;
        if (ruleIds.isEmpty() && effectiveRuleSetId != null && !effectiveRuleSetId.isBlank()) { publishedSet = ruleSetService.published(effectiveRuleSetId); ruleIds = publishedSet.ruleIds(); }
        if (ruleIds.isEmpty()) throw IqcException.invalidArgument("至少选择一条已发布规则");
        task.setRuleSetId(effectiveRuleSetId); task.setRuleIdsJson(writeSnapshot(ruleIds));
        task.setAgentSnapshotJson(writeSnapshot(agent));
        List<QualityRule> rules = new ArrayList<>();
        for (String ruleId : ruleIds) {
            QualityRule rule = ruleMapper.selectById(ruleId);
            if (rule == null || !"PUBLISHED".equals(rule.getStatus())) throw IqcException.invalidArgument("只能选择已发布规则");
            rules.add(rule);
        }
        if (publishedSet == null) task.setRuleSnapshotJson(writeSnapshot(rules));
        else task.setRuleSnapshotJson(writeSnapshot(Map.of("ruleSetId", publishedSet.id(), "ruleSetName", publishedSet.name(),
                "ruleSetCode", publishedSet.code(), "ruleSetVersion", publishedSet.versionNo(),
                "aggregationMode", publishedSet.aggregationMode(), "rules", rules)));
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

    public List<InspectionTask> list() {
        var query = Wrappers.<InspectionTask>lambdaQuery().orderByDesc(InspectionTask::getCreatedTime);
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
        var query = Wrappers.<InspectionTask>lambdaQuery().orderByDesc(InspectionTask::getCreatedTime);
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

    @Transactional
    public InspectionTask cancel(String id) {
        InspectionTask task = taskMapper.selectById(id);
        if (task == null) throw IqcException.notFound("质检任务不存在: " + id);
        if (!dataScope.canView(task.getCreatedBy(), task.getOwnerGroupId())) throw IqcException.accessDenied("无权取消该质检任务");
        int cancelled = taskMapper.update(null, Wrappers.<InspectionTask>lambdaUpdate()
                .set(InspectionTask::getStatus, "CANCELLED")
                .eq(InspectionTask::getId, id)
                .in(InspectionTask::getStatus, "SCHEDULED", "MATERIALIZING", "CREATED", "QUEUED", "RUNNING"));
        if (cancelled == 1 && task.getCurrentExecutionId() != null) {
            TaskExecution execution = executionMapper.selectById(task.getCurrentExecutionId());
            if (execution != null) { execution.setStatus("CANCELLED"); executionMapper.updateById(execution); }
        }
        return taskMapper.selectById(id);
    }
}
