package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.conversation.ConversationSpeakerRole;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.result.dao.InspectionResultMapper;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.scheme.SchemeDefinition;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.model.TaskExecution;
import io.github.opensabre.iqc.task.dao.TaskItemMapper;
import io.github.opensabre.iqc.task.model.TaskItem;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.shared.IqcPage;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.result.llm.LlmQualityProvider;
import io.github.opensabre.iqc.result.llm.LlmTextSanitizer;
import io.github.opensabre.iqc.label.LabelCandidateService;
import io.github.opensabre.iqc.rule.RuleMatcher;
import io.github.opensabre.iqc.rule.dls.DlsEngine;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import io.github.opensabre.governance.usage.UsageOutcome;
import io.github.opensabre.governance.usage.UsageRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Collections;
import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class InspectionExecutionService {
    private final InspectionTaskMapper taskMapper;
    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final InspectionResultMapper resultMapper;
    private final ObjectMapper objectMapper;
    private final TaskExecutionMapper executionMapper;
    private final TaskItemMapper taskItemMapper;
    private final IqcDataScope dataScope;
    private final LlmQualityProvider llmQualityProvider;
    private final UsageCounterRecorder usageCounterRecorder;
    private final HierarchicalResultService hierarchicalResultService;
    private final LabelCandidateService labelCandidateService;
    private final io.github.opensabre.iqc.scheme.SchemeDependencyResolver schemeDependencies;
    private final Map<String, DlsEngine.Compiled> dlsCompileCache = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, DlsEngine.Compiled> eldest) {
                    return size() > 128;
                }
            });

    @Transactional
    public InspectionTask queue(String taskId) {
        return queue(taskId, true);
    }

    /** Internal scheduler entry; creation-time scope has already been snapshotted and enforced. */
    @Transactional
    public InspectionTask queueSystem(String taskId) {
        return queue(taskId, false);
    }

    private InspectionTask queue(String taskId, boolean enforceDataScope) {
        InspectionTask task = taskMapper.selectById(taskId);
        if (task == null) throw IqcException.notFound("质检任务不存在: " + taskId);
        if (enforceDataScope && !dataScope.canView(task.getCreatedBy(), task.getOwnerGroupId())) throw IqcException.accessDenied("无权执行该质检任务");
        if ("CREATED".equals(task.getStatus()) || "FAILED".equals(task.getStatus()) || "PARTIAL_FAILED".equals(task.getStatus())) {
            JsonNode snapshot = readSnapshot(task.getRuleSnapshotJson());
            requireSupportedRouteRunCount(task, snapshot);
            io.github.opensabre.iqc.scheme.SchemeDependencyResolver.validateRouteTaskProjection(snapshot,
                    task.getAgentSnapshotJson(), objectMapper);
            io.github.opensabre.iqc.scheme.SchemeDependencyResolver.validateJointTaskProjection(snapshot,
                    task.getLabelScopeSnapshotJson(), objectMapper);
            schemeDependencies.validateTaskDependencies(snapshot);
            String previousStatus = task.getStatus();
            String previousExecutionId = task.getCurrentExecutionId();
            Set<String> retryMessageIds = null;
            int successfulMessages = 0;
            if (previousExecutionId != null && ("FAILED".equals(previousStatus) || "PARTIAL_FAILED".equals(previousStatus))) {
                List<TaskItem> previousItems = taskItemMapper.selectList(Wrappers.<TaskItem>lambdaQuery()
                        .eq(TaskItem::getExecutionId, previousExecutionId));
                if (!previousItems.isEmpty()) {
                    retryMessageIds = new HashSet<>();
                    for (TaskItem previousItem : previousItems) {
                        if (!"SUCCEEDED".equals(previousItem.getStatus())) retryMessageIds.add(previousItem.getMessageId());
                    }
                    // A retry execution contains only the preceding attempt's unfinished subset.
                    // Derive cumulative progress from the immutable task total so earlier successful
                    // attempts are not lost after a second or later retry.
                    successfulMessages = Math.max(0,
                            (task.getTotalMessages() == null ? 0 : task.getTotalMessages()) - retryMessageIds.size());
                }
            }
            int claimed = taskMapper.update(null, Wrappers.<InspectionTask>lambdaUpdate()
                    .set(InspectionTask::getStatus, "QUEUED")
                    .eq(InspectionTask::getId, taskId)
                    .eq(InspectionTask::getStatus, previousStatus));
            if (claimed != 1) throw IqcException.invalidState("任务正在被其他请求处理");
            int attempt = task.getAttemptCount() == null ? 1 : task.getAttemptCount() + 1;
            TaskExecution execution = new TaskExecution();
            execution.setTaskId(taskId); execution.setAttemptNo(attempt); execution.setStatus("QUEUED"); execution.setProcessedMessages(0); execution.setFailedMessages(0);
            execution.setProcessedMessages(successfulMessages);
            executionMapper.insert(execution);
            List<String> conversationIds = conversationIds(task);
            List<ConversationMessage> messages = conversationIds.isEmpty() ? List.of() : messageMapper.selectList(Wrappers.<ConversationMessage>lambdaQuery()
                    .in(ConversationMessage::getConversationId, conversationIds)
                    .orderByAsc(ConversationMessage::getConversationId).orderByAsc(ConversationMessage::getSequenceNo));
            int batchSequence = 0;
            for (ConversationMessage message : messages) {
                if (retryMessageIds != null && !retryMessageIds.contains(message.getId())) continue;
                TaskItem item = new TaskItem();
                item.setTaskId(taskId); item.setExecutionId(execution.getId()); item.setMessageId(message.getId());
                item.setConversationId(message.getConversationId()); item.setSequenceNo(++batchSequence); item.setStatus("PENDING"); item.setAttemptCount(0);
                taskItemMapper.insert(item);
            }
            task = taskMapper.selectById(taskId);
            task.setCurrentExecutionId(execution.getId()); task.setAttemptCount(attempt);
            task.setProcessedMessages(successfulMessages); task.setFailedMessages(0);
            taskMapper.updateById(task);
            return task;
        } else {
            throw IqcException.taskNotExecutable("当前任务状态不可重复执行: " + task.getStatus());
        }
    }

    @Async("iqcTaskExecutor")
    public void executeAsync(String taskId, String executionId) {
        long startedAt = System.nanoTime();
        try {
            run(taskId, executionId);
            InspectionTask completed = taskMapper.selectById(taskId);
            log.info("event=iqc_task_execution taskId={} executionId={} status={} processedMessages={} failedMessages={} elapsedMs={}",
                    taskId, executionId, completed == null ? "UNKNOWN" : completed.getStatus(),
                    completed == null ? null : completed.getProcessedMessages(), completed == null ? null : completed.getFailedMessages(), elapsedMillis(startedAt));
        } catch (RuntimeException exception) {
            InspectionTask failed = taskMapper.selectById(taskId);
            if (failed != null) {
                failed.setStatus("FAILED");
                failed.setFailedMessages((failed.getTotalMessages() == null ? 0 : failed.getTotalMessages()) - (failed.getProcessedMessages() == null ? 0 : failed.getProcessedMessages()));
                taskMapper.updateById(failed);
            }
            TaskExecution execution = executionMapper.selectById(executionId);
            if (execution != null) { execution.setStatus("FAILED"); execution.setErrorMessage(persistedErrorMessage(exception.getMessage())); executionMapper.updateById(execution); }
            log.error("event=iqc_task_execution taskId={} executionId={} status=FAILED errorType={} elapsedMs={}",
                    taskId, executionId, exception.getClass().getSimpleName(), elapsedMillis(startedAt), exception);
        }
    }

    /** Validate route vote configuration before claiming a lease or creating execution progress. */
    private void requireSupportedRouteRunCount(InspectionTask task, JsonNode snapshot) {
        if (snapshot == null || !SchemeDefinition.ROUTED_TASK_SCHEMA.equals(
                snapshot.at("/schemeSnapshot/release/definition/schemaVersion").asText())) return;
        int runCount = task.getRunCount() == null ? 1 : task.getRunCount();
        if (runCount < 1 || runCount > 5) throw IqcException.invalidState("逐项裁决轮数必须为 1 到 5");
        var threshold = task.getConfidenceThreshold();
        if (threshold != null && (threshold.compareTo(java.math.BigDecimal.ZERO) < 0
                || threshold.compareTo(java.math.BigDecimal.ONE) > 0))
            throw IqcException.invalidState("逐项裁决置信门槛必须在 0 到 1 之间");
    }

    private long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    public InspectionTask run(String taskId, String executionId) {
        InspectionTask task = taskMapper.selectById(taskId);
        if (task == null) throw IqcException.notFound("质检任务不存在: " + taskId);
        if ("RUNNING".equals(task.getStatus()) || "SUCCEEDED".equals(task.getStatus())) return task;
        requireSupportedRouteRunCount(task, readSnapshot(task.getRuleSnapshotJson()));

        TaskExecution execution = executionMapper.selectById(executionId);
        if (execution == null) throw IqcException.notFound("执行实例不存在: " + executionId);
        if (List.of("CANCELLED", "CANCEL_REQUESTED").contains(task.getStatus())) { execution.setStatus("CANCELLED"); executionMapper.updateById(execution); return task; }
        int claimed = taskMapper.update(null, Wrappers.<InspectionTask>lambdaUpdate()
                .set(InspectionTask::getStatus, "RUNNING")
                .eq(InspectionTask::getId, taskId)
                .eq(InspectionTask::getCurrentExecutionId, executionId)
                .eq(InspectionTask::getStatus, "QUEUED"));
        if (claimed != 1) {
            InspectionTask current = taskMapper.selectById(taskId);
            if (current != null && List.of("RUNNING", "SUCCEEDED").contains(current.getStatus())) return current;
            throw IqcException.invalidState("任务执行实例未能取得运行租约");
        }
        task.setStatus("RUNNING");
        execution.setStatus("RUNNING"); executionMapper.updateById(execution);
        JsonNode ruleSnapshot = readSnapshot(task.getRuleSnapshotJson());
        // An asset can be disabled while this task waits in the executor queue. Check before any conversation work.
        io.github.opensabre.iqc.scheme.SchemeDependencyResolver.validateRouteTaskProjection(ruleSnapshot,
                task.getAgentSnapshotJson(), objectMapper);
        io.github.opensabre.iqc.scheme.SchemeDependencyResolver.validateJointTaskProjection(ruleSnapshot,
                task.getLabelScopeSnapshotJson(), objectMapper);
        schemeDependencies.validateTaskDependencies(ruleSnapshot);
        List<TaskItem> items = taskItemMapper.selectList(Wrappers.<TaskItem>lambdaQuery()
                .eq(TaskItem::getExecutionId, executionId).orderByAsc(TaskItem::getSequenceNo));
        int previouslyProcessed = Math.max(0, (task.getTotalMessages() == null ? items.size() : task.getTotalMessages()) - items.size());
        Map<String, List<TaskItem>> byConversation = items.stream().collect(java.util.stream.Collectors.groupingBy(
                item -> item.getConversationId() == null ? "legacy" : item.getConversationId(), LinkedHashMap::new, java.util.stream.Collectors.toList()));
        int concurrency = Math.min(Math.max(1, task.getConcurrencyLimit() == null ? 1 : task.getConcurrencyLimit()), Math.max(1, byConversation.size()));
        InspectionTask executionTask = task;
        // A batch parallelizes conversations, while messages inside one conversation keep their original order.
        try (ExecutorService workers = Executors.newFixedThreadPool(concurrency, Thread.ofPlatform().name("iqc-conversation-", 0).factory())) {
            List<Future<?>> futures = new ArrayList<>();
            for (List<TaskItem> conversationItems : byConversation.values()) {
                futures.add(workers.submit(() -> processConversation(executionTask, executionId, ruleSnapshot, conversationItems)));
            }
            for (Future<?> future : futures) {
                try { future.get(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException("质检批次执行被中断", exception); }
                catch (java.util.concurrent.ExecutionException exception) { throw new IllegalStateException("质检批次并发执行失败", exception.getCause()); }
            }
        }
        task = taskMapper.selectById(taskId);
        if (task == null || List.of("CANCELLED", "CANCEL_REQUESTED").contains(task.getStatus())) {
            if (task != null && "CANCEL_REQUESTED".equals(task.getStatus())) {
                task.setStatus("CANCELLED");
                taskMapper.updateById(task);
            }
            execution.setStatus("CANCELLED"); executionMapper.updateById(execution); return task;
        }
        // Batch commits update locked database rows, not the worker's original objects.
        // Aggregate committed progress so both legacy and item-route execution use the same source of truth.
        List<TaskItem> committedItems = taskItemMapper.selectList(Wrappers.<TaskItem>lambdaQuery()
                .eq(TaskItem::getExecutionId, executionId).orderByAsc(TaskItem::getSequenceNo));
        int processed = previouslyProcessed + (int) committedItems.stream().filter(item -> List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(item.getStatus())).count();
        int failed = (int) committedItems.stream().filter(item -> "FAILED".equals(item.getStatus())).count();
        task.setProcessedMessages(processed); task.setFailedMessages(failed);
        if ("PAUSE_REQUESTED".equals(task.getStatus())) {
            task.setStatus("PAUSED"); taskMapper.updateById(task);
            execution.setProcessedMessages(processed); execution.setFailedMessages(failed); execution.setStatus("PAUSED"); executionMapper.updateById(execution);
            return task;
        }
        task.setStatus(failed > 0 ? "PARTIAL_FAILED" : "SUCCEEDED"); taskMapper.updateById(task);
        execution.setProcessedMessages(processed); execution.setFailedMessages(failed); execution.setStatus(failed > 0 ? "PARTIAL_FAILED" : "SUCCEEDED"); executionMapper.updateById(execution);
        return task;
    }

    @Transactional
    public InspectionTask resume(String taskId) {
        InspectionTask task = taskMapper.selectById(taskId);
        if (task == null) throw IqcException.notFound("质检任务不存在: " + taskId);
        if (!dataScope.canView(task.getCreatedBy(), task.getOwnerGroupId())) throw IqcException.accessDenied("无权恢复该质检任务");
        if (task.getCurrentExecutionId() == null) throw IqcException.invalidState("任务没有可恢复的执行实例");
        JsonNode snapshot = readSnapshot(task.getRuleSnapshotJson());
        requireSupportedRouteRunCount(task, snapshot);
        io.github.opensabre.iqc.scheme.SchemeDependencyResolver.validateRouteTaskProjection(snapshot,
                task.getAgentSnapshotJson(), objectMapper);
        io.github.opensabre.iqc.scheme.SchemeDependencyResolver.validateJointTaskProjection(snapshot,
                task.getLabelScopeSnapshotJson(), objectMapper);
        schemeDependencies.validateTaskDependencies(snapshot);
        List<TaskItem> previousItems = taskItemMapper.selectList(Wrappers.<TaskItem>lambdaQuery().eq(TaskItem::getExecutionId, task.getCurrentExecutionId()));
        List<TaskItem> unfinished = previousItems.stream().filter(item -> !List.of("SUCCEEDED", "CANCELLED").contains(item.getStatus())).toList();
        int changed = taskMapper.update(null, Wrappers.<InspectionTask>lambdaUpdate()
                .set(InspectionTask::getStatus, "QUEUED")
                .set(InspectionTask::getPauseRequested, false)
                .eq(InspectionTask::getId, taskId)
                .eq(InspectionTask::getStatus, "PAUSED"));
        if (changed != 1) throw IqcException.invalidState("只有已暂停任务可以恢复");
        int attempt = task.getAttemptCount() == null ? 1 : task.getAttemptCount() + 1;
        TaskExecution execution = new TaskExecution(); execution.setTaskId(taskId); execution.setAttemptNo(attempt); execution.setStatus("QUEUED");
        execution.setProcessedMessages(Math.max(0, (task.getTotalMessages() == null ? previousItems.size() : task.getTotalMessages()) - unfinished.size())); execution.setFailedMessages(0);
        executionMapper.insert(execution);
        int sequence = 0;
        for (TaskItem previous : unfinished) {
            TaskItem item = new TaskItem(); item.setTaskId(taskId); item.setExecutionId(execution.getId()); item.setMessageId(previous.getMessageId());
            item.setConversationId(previous.getConversationId()); item.setSequenceNo(++sequence); item.setStatus("PENDING"); item.setAttemptCount(previous.getAttemptCount() == null ? 0 : previous.getAttemptCount());
            taskItemMapper.insert(item);
        }
        task = taskMapper.selectById(taskId); task.setCurrentExecutionId(execution.getId()); task.setAttemptCount(attempt);
        task.setProcessedMessages(execution.getProcessedMessages()); task.setFailedMessages(0); taskMapper.updateById(task); return task;
    }

    private void processConversation(InspectionTask task, String executionId, JsonNode ruleSnapshot, List<TaskItem> items) {
        Map<String, ConversationMessage> conversationMessagesById = new LinkedHashMap<>();
        for (TaskItem item : items) {
            ConversationMessage loaded = messageMapper.selectById(item.getMessageId());
            if (loaded != null) conversationMessagesById.put(item.getMessageId(), loaded);
        }
        List<ConversationMessage> conversationMessages = new ArrayList<>(conversationMessagesById.values());
        ConversationSpeakerRole.canonicalize(conversationMessages);
        boolean schemeTask = ruleSnapshot != null && ruleSnapshot.has("schemeSnapshot");
        if (schemeTask && !conversationMessages.isEmpty()) {
            io.github.opensabre.iqc.scheme.SchemeResultEvaluator.definition(ruleSnapshot, objectMapper);
            // Retries must evaluate conversation-scoped detectors with the complete conversation, not only failed messages.
            List<ConversationMessage> fullContext = messageMapper.selectList(Wrappers.<ConversationMessage>lambdaQuery()
                    .eq(ConversationMessage::getConversationId, conversationMessages.get(0).getConversationId())
                    .orderByAsc(ConversationMessage::getSequenceNo));
            if (fullContext != null && !fullContext.isEmpty()) {
                ConversationSpeakerRole.canonicalize(fullContext);
                conversationMessages = fullContext;
            }
        }
        if (schemeTask && SchemeDefinition.ROUTED_TASK_SCHEMA.equals(ruleSnapshot.at("/schemeSnapshot/release/definition/schemaVersion").asText())) {
            var current = taskMapper.selectById(task.getId());
            if (current == null || !"RUNNING".equals(current.getStatus())
                    || !java.util.Objects.equals(executionId, current.getCurrentExecutionId())) return;
            var pending = items.stream().filter(item -> !List.of("SUCCEEDED", "CANCELLED").contains(item.getStatus())).toList();
            if (!pending.isEmpty()) materializeItemRouteConversation(task, executionId, ruleSnapshot, conversationMessages, pending);
            return;
        }
        List<InspectionResult> conversationResults = new ArrayList<>();
        // Local to this conversation execution; each configured run is shared by its message projections.
        Map<String, List<LlmQualityProvider.LlmEvaluation>> conversationLlmRuns = new LinkedHashMap<>();
        Map<String, String> conversationFactOwners = new LinkedHashMap<>();
        for (TaskItem item : items) {
            InspectionTask current = taskMapper.selectById(task.getId());
            if (current == null || List.of("CANCELLED", "CANCEL_REQUESTED").contains(current.getStatus())) {
                item.setStatus("CANCELLED"); taskItemMapper.updateById(item); continue;
            }
            if (List.of("PAUSE_REQUESTED", "PAUSED").contains(current.getStatus())) break;
            if ("SUCCEEDED".equals(item.getStatus()) || "CANCELLED".equals(item.getStatus())) continue;
            item.setStatus("RUNNING"); item.setAttemptCount((item.getAttemptCount() == null ? 0 : item.getAttemptCount()) + 1); taskItemMapper.updateById(item);
            String usageRecordId = "inspection-message:" + task.getId() + ":" + item.getMessageId();
            usageCounterRecorder.record(new UsageRecord(usageRecordId + ":attempt", null, "iqc-platform", "INSPECTION_MESSAGE", item.getMessageId(), "QUALITY_CHECK", UsageOutcome.ATTEMPT));
            try {
                ConversationMessage message = conversationMessagesById.get(item.getMessageId());
                if (message == null) throw IqcException.notFound("会话消息不存在: " + item.getMessageId());
                InspectionResult result = evaluateWithRuns(task, message, ruleSnapshot, conversationMessages,
                        conversationLlmRuns, conversationFactOwners);
                if (schemeTask) {
                    // Message results are observations, not business scores. Conversation materialization owns V2 scoring.
                    result.setScore(null); result.setDeduction(0);
                    JsonNode breakdown = readSnapshot(result.getRuleBreakdownJson());
                    if (breakdown != null && breakdown.isArray()) {
                        for (JsonNode slice : breakdown) if (slice instanceof ObjectNode object) {
                            object.putNull("score"); object.put("deduction", 0);
                        }
                        result.setRuleBreakdownJson(writeJson(breakdown));
                    }
                }
                result.setExecutionId(executionId); resultMapper.insert(result); item.setResultId(result.getId());
                maybeProposeCandidate(task, message, result);
                conversationResults.add(result);
                if (result.getResultStatus() != null && result.getResultStatus().endsWith("ERROR")) {
                    item.setStatus("FAILED"); item.setErrorMessage(persistedErrorMessage(result.getReason()));
                    usageCounterRecorder.record(new UsageRecord(usageRecordId + ":failure", null, "iqc-platform", "INSPECTION_MESSAGE", item.getMessageId(), "QUALITY_CHECK", UsageOutcome.FAILURE));
                } else {
                    item.setStatus("SUCCEEDED");
                    usageCounterRecorder.record(new UsageRecord(usageRecordId + ":success", null, "iqc-platform", "INSPECTION_MESSAGE", item.getMessageId(), "QUALITY_CHECK", UsageOutcome.SUCCESS));
                }
            } catch (RuntimeException exception) {
                item.setStatus("FAILED"); item.setErrorMessage(persistedErrorMessage(exception.getMessage()));
                usageCounterRecorder.record(new UsageRecord(usageRecordId + ":failure", null, "iqc-platform", "INSPECTION_MESSAGE", item.getMessageId(), "QUALITY_CHECK", UsageOutcome.FAILURE));
            }
            taskItemMapper.updateById(item);
        }
        if (!conversationMessages.isEmpty() && (schemeTask || !conversationResults.isEmpty())) {
            String conversationId = conversationMessages.get(0).getConversationId();
            List<ConversationMessage> fullMessages = messageMapper.selectList(Wrappers.<ConversationMessage>lambdaQuery()
                    .eq(ConversationMessage::getConversationId, conversationId).orderByAsc(ConversationMessage::getSequenceNo));
            if (fullMessages == null || fullMessages.isEmpty()) fullMessages = conversationMessages;
            ConversationSpeakerRole.canonicalize(fullMessages);
            List<InspectionResult> previousResults = resultMapper.selectList(Wrappers.<InspectionResult>lambdaQuery()
                    .eq(InspectionResult::getTaskId, task.getId()).eq(InspectionResult::getConversationId, conversationId).orderByAsc(InspectionResult::getCreatedTime));
            Map<String, InspectionResult> latestByMessage = new LinkedHashMap<>();
            if (previousResults != null) previousResults.forEach(value -> latestByMessage.put(value.getMessageId(), value));
            conversationResults.forEach(value -> latestByMessage.put(value.getMessageId(), value));
            hierarchicalResultService.materialize(task, executionId, ruleSnapshot, fullMessages, new ArrayList<>(latestByMessage.values()));
        }
    }

    public List<InspectionResult> list(String taskId) {
        return list(taskId, null, null, null, null, null, null, null, null);
    }

    /** Failure recording must fit the existing varchar(1000) contract and must not fail the task again. */
    private String persistedErrorMessage(String message) {
        String sanitized = LlmTextSanitizer.sanitize(message);
        if (sanitized == null || sanitized.length() <= 1000) return sanitized;
        int end = Character.isHighSurrogate(sanitized.charAt(999)) ? 999 : 1000;
        return sanitized.substring(0, end);
    }

    public List<InspectionResult> list(String taskId, String status, Integer minScore, Integer maxScore, String speakerRole) {
        return list(taskId, null, null, null, status, minScore, maxScore, speakerRole, null);
    }

    public List<InspectionResult> list(String taskId, String status, Integer minScore, Integer maxScore, String speakerRole, String riskLevel) {
        return list(taskId, null, null, null, status, minScore, maxScore, speakerRole, riskLevel);
    }

    public List<InspectionResult> list(String taskId, String agentId, String status, Integer minScore, Integer maxScore, String speakerRole, String riskLevel) {
        return list(taskId, agentId, null, null, status, minScore, maxScore, speakerRole, riskLevel);
    }

    public List<InspectionResult> list(String taskId, String agentId, String ownerId, String groupId,
                                       String status, Integer minScore, Integer maxScore, String speakerRole, String riskLevel) {
        var taskQuery = Wrappers.<InspectionTask>lambdaQuery().select(InspectionTask::getId);
        if (!dataScope.canViewAll()) {
            String currentGroupId = dataScope.groupId();
            taskQuery.and(q -> q.eq(InspectionTask::getCreatedBy, dataScope.owner())
                    .or(currentGroupId != null, nested -> nested.eq(InspectionTask::getOwnerGroupId, currentGroupId)));
        }
        if (taskId != null && !taskId.isBlank()) taskQuery.eq(InspectionTask::getId, taskId);
        if (agentId != null && !agentId.isBlank()) taskQuery.eq(InspectionTask::getAgentId, agentId);
        if (ownerId != null && !ownerId.isBlank()) taskQuery.eq(InspectionTask::getCreatedBy, ownerId);
        if (groupId != null && !groupId.isBlank()) taskQuery.eq(InspectionTask::getOwnerGroupId, groupId);
        List<String> visibleTaskIds = taskMapper.selectList(taskQuery).stream().map(InspectionTask::getId).toList();
        if (visibleTaskIds.isEmpty()) return List.of();
        var resultQuery = Wrappers.<InspectionResult>lambdaQuery().in(InspectionResult::getTaskId, visibleTaskIds);
        if (status != null && !status.isBlank()) resultQuery.eq(InspectionResult::getResultStatus, status);
        if (minScore != null) resultQuery.ge(InspectionResult::getScore, minScore);
        if (maxScore != null) resultQuery.le(InspectionResult::getScore, maxScore);
        if (speakerRole != null && !speakerRole.isBlank()) resultQuery.eq(InspectionResult::getSpeakerRole, speakerRole);
        if (riskLevel != null && !riskLevel.isBlank()) resultQuery.eq(InspectionResult::getRiskLevel, riskLevel);
        return resultMapper.selectList(resultQuery.orderByAsc(InspectionResult::getCreatedTime));
    }

    public IqcPage<InspectionResult> page(long current, long size, String taskId, String agentId, String ownerId, String groupId,
                                          String status, Integer minScore, Integer maxScore, String speakerRole, String riskLevel) {
        var taskQuery = Wrappers.<InspectionTask>lambdaQuery().select(InspectionTask::getId);
        if (!dataScope.canViewAll()) {
            String currentGroupId = dataScope.groupId();
            taskQuery.and(q -> q.eq(InspectionTask::getCreatedBy, dataScope.owner())
                    .or(currentGroupId != null, nested -> nested.eq(InspectionTask::getOwnerGroupId, currentGroupId)));
        }
        if (taskId != null && !taskId.isBlank()) taskQuery.eq(InspectionTask::getId, taskId);
        if (agentId != null && !agentId.isBlank()) taskQuery.eq(InspectionTask::getAgentId, agentId);
        if (ownerId != null && !ownerId.isBlank()) taskQuery.eq(InspectionTask::getCreatedBy, ownerId);
        if (groupId != null && !groupId.isBlank()) taskQuery.eq(InspectionTask::getOwnerGroupId, groupId);
        List<String> visibleTaskIds = taskMapper.selectList(taskQuery).stream().map(InspectionTask::getId).toList();
        if (visibleTaskIds.isEmpty()) return new IqcPage<>(List.of(), Math.max(1, current), Math.min(Math.max(1, size), 100), 0);
        var resultQuery = Wrappers.<InspectionResult>lambdaQuery().in(InspectionResult::getTaskId, visibleTaskIds);
        if (status != null && !status.isBlank()) resultQuery.eq(InspectionResult::getResultStatus, status);
        if (minScore != null) resultQuery.ge(InspectionResult::getScore, minScore);
        if (maxScore != null) resultQuery.le(InspectionResult::getScore, maxScore);
        if (speakerRole != null && !speakerRole.isBlank()) resultQuery.eq(InspectionResult::getSpeakerRole, speakerRole);
        if (riskLevel != null && !riskLevel.isBlank()) resultQuery.eq(InspectionResult::getRiskLevel, riskLevel);
        var resultPage = resultMapper.selectPage(new Page<>(Math.max(1, current), Math.min(Math.max(1, size), 100)), resultQuery.orderByAsc(InspectionResult::getCreatedTime));
        List<String> conversationIds = resultPage.getRecords().stream().map(InspectionResult::getConversationId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (!conversationIds.isEmpty()) {
            Map<String, String> names = conversationMapper.selectBatchIds(conversationIds).stream()
                    .collect(java.util.stream.Collectors.toMap(Conversation::getId, Conversation::getSourceFileName, (left, right) -> left));
            resultPage.getRecords().forEach(result -> result.setSourceFileName(names.get(result.getConversationId())));
        }
        return IqcPage.from(resultPage);
    }

    public String exportCsv(String taskId) {
        return exportCsv(taskId, null, null, null, null, null, null, null, null);
    }

    public String exportCsv(String taskId, String status, Integer minScore, Integer maxScore, String speakerRole) {
        return exportCsv(taskId, null, null, null, status, minScore, maxScore, speakerRole, null);
    }

    public String exportCsv(String taskId, String status, Integer minScore, Integer maxScore, String speakerRole, String riskLevel) {
        return exportCsv(taskId, null, null, null, status, minScore, maxScore, speakerRole, riskLevel);
    }

    public String exportCsv(String taskId, String agentId, String status, Integer minScore, Integer maxScore, String speakerRole, String riskLevel) {
        return exportCsv(taskId, agentId, null, null, status, minScore, maxScore, speakerRole, riskLevel);
    }

    public String exportCsv(String taskId, String agentId, String ownerId, String groupId,
                            String status, Integer minScore, Integer maxScore, String speakerRole, String riskLevel) {
        StringBuilder csv = new StringBuilder("结果ID,任务ID,执行实例,消息ID,角色,状态,风险,扣分,分数,原因,证据,规则明细\n");
        for (InspectionResult result : list(taskId, agentId, ownerId, groupId, status, minScore, maxScore, speakerRole, riskLevel)) {
            csv.append(row(result.getId())).append(',').append(row(result.getTaskId())).append(',').append(row(result.getExecutionId()))
                    .append(',').append(row(result.getMessageId())).append(',').append(row(result.getSpeakerRole())).append(',')
                    .append(row(result.getResultStatus())).append(',').append(row(result.getRiskLevel())).append(',')
                    .append(result.getDeduction() == null ? "" : result.getDeduction()).append(',')
                    .append(result.getScore() == null ? "" : result.getScore()).append(',')
                    .append(row(LlmTextSanitizer.sanitize(result.getReason()))).append(',')
                    .append(row(LlmTextSanitizer.sanitize(result.getEvidence()))).append(',')
                    .append(row(LlmTextSanitizer.sanitize(result.getRuleBreakdownJson()))).append('\n');
        }
        return csv.toString();
    }

    /** Shared CSV cell escaping, including spreadsheet formula injection protection. */
    public static String row(String value) {
        String text = value == null ? "" : value;
        String leading = text.stripLeading();
        if (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0
                || text.startsWith("\t") || text.startsWith("\r") || text.startsWith("\n")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    public Map<String, Object> detail(String resultId) {
        InspectionResult result = resultMapper.selectById(resultId);
        if (result == null) throw IqcException.notFound("质检结果不存在: " + resultId);
        InspectionTask task = taskMapper.selectById(result.getTaskId());
        if (task == null || !dataScope.canView(task.getCreatedBy(), task.getOwnerGroupId())) {
            throw IqcException.accessDenied("无权查看该质检结果");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        result.setReason(LlmTextSanitizer.sanitize(result.getReason()));
        result.setEvidence(LlmTextSanitizer.sanitize(result.getEvidence()));
        result.setEvidenceJson(LlmTextSanitizer.sanitize(result.getEvidenceJson()));
        result.setFindingJson(LlmTextSanitizer.sanitize(result.getFindingJson()));
        result.setSuggestionJson(LlmTextSanitizer.sanitize(result.getSuggestionJson()));
        result.setRuleBreakdownJson(LlmTextSanitizer.sanitize(result.getRuleBreakdownJson()));
        ConversationMessage message = messageMapper.selectById(result.getMessageId());
        if (message != null) {
            message.setContent(LlmTextSanitizer.sanitize(message.getContent()));
            message.setRawLine(LlmTextSanitizer.sanitize(message.getRawLine()));
        }
        detail.put("result", result);
        detail.put("message", message);
        detail.put("task", task);
        detail.put("execution", result.getExecutionId() == null ? null : executionMapper.selectById(result.getExecutionId()));
        return detail;
    }

    private InspectionResult evaluate(InspectionTask task, ConversationMessage message, JsonNode ruleSnapshot) {
        return evaluate(task, message, ruleSnapshot, List.of(message));
    }

    private InspectionResult evaluateWithRuns(InspectionTask task, ConversationMessage message, JsonNode ruleSnapshot,
                                              List<ConversationMessage> conversationMessages) {
        return evaluateWithRuns(task, message, ruleSnapshot, conversationMessages, new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    private InspectionResult evaluateWithRuns(InspectionTask task, ConversationMessage message, JsonNode ruleSnapshot,
                                              List<ConversationMessage> conversationMessages,
                                              Map<String, List<LlmQualityProvider.LlmEvaluation>> conversationLlmRuns,
                                              Map<String, String> conversationFactOwners) {
        int runs = Math.min(5, Math.max(1, task.getRunCount() == null ? 1 : task.getRunCount()));
        List<InspectionResult> decisions = new ArrayList<>(runs);
        for (int index = 0; index < runs; index++) decisions.add(evaluate(task, message, ruleSnapshot, conversationMessages,
                conversationLlmRuns, conversationFactOwners, index));
        long hitCount = decisions.stream().filter(value -> "HIT".equals(value.getResultStatus())).count();
        double confidence = Math.max(hitCount, runs - hitCount) / (double) runs;
        boolean hit = hitCount * 2 >= runs;
        InspectionResult selected = decisions.stream().filter(value -> hit == "HIT".equals(value.getResultStatus()))
                .findFirst().orElse(decisions.get(0));
        ObjectNode finding;
        try {
            JsonNode existing = objectMapper.readTree(selected.getFindingJson() == null ? "{}" : selected.getFindingJson());
            finding = existing != null && existing.isObject() ? (ObjectNode) existing.deepCopy() : objectMapper.createObjectNode();
            if (existing != null && existing.isArray()) finding.set("findings", existing);
        } catch (Exception ignored) { finding = objectMapper.createObjectNode(); }
        ArrayNode runDetails = objectMapper.createArrayNode();
        for (int index = 0; index < decisions.size(); index++) {
            InspectionResult decision = decisions.get(index);
            ObjectNode detail = objectMapper.createObjectNode().put("runIndex", index + 1)
                    .put("status", decision.getResultStatus()).put("reason", decision.getReason());
            if (decision.getFindingJson() != null && !decision.getFindingJson().isBlank()) {
                try { detail.set("finding", objectMapper.readTree(decision.getFindingJson())); }
                catch (Exception exception) { detail.put("findingRaw", decision.getFindingJson()); }
            }
            runDetails.add(detail);
        }
        finding.set("runs", runDetails);
        finding.put("runCount", runs); finding.put("hitCount", hitCount); finding.put("confidence", confidence);
        selected.setFindingJson(finding.toString());
        if (runs % 2 == 0 && hitCount * 2 == runs) {
            selected.setResultStatus("REVIEW_REQUIRED"); selected.setReason("多轮判断出现平票，需要人工复核");
            updateBreakdownStatus(selected, "REVIEW_REQUIRED");
        } else if (task.getConfidenceThreshold() != null && confidence < task.getConfidenceThreshold().doubleValue()) {
            selected.setResultStatus("NOT_HIT"); selected.setReason("多轮判断置信度低于任务阈值");
            updateBreakdownStatus(selected, "NOT_HIT");
        }
        return selected;
    }

    private void updateBreakdownStatus(InspectionResult result, String status) {
        try {
            JsonNode breakdown = objectMapper.readTree(result.getRuleBreakdownJson());
            if (breakdown.isArray()) breakdown.forEach(item -> { if (item.isObject()) ((ObjectNode) item).put("status", status); });
            result.setRuleBreakdownJson(breakdown.toString());
        } catch (Exception ignored) { /* The aggregate status remains authoritative for legacy malformed detail. */ }
    }

    private void maybeProposeCandidate(InspectionTask task, ConversationMessage message, InspectionResult result) {
        if (!Boolean.TRUE.equals(task.getAutoExpandEnabled()) || !"LLM_THEN_RULE".equals(taskQualityMode(task))
                || !"NOT_HIT".equals(result.getResultStatus())) return;
        try {
            JsonNode snapshot = readSnapshot(task.getLabelScopeSnapshotJson());
            String reason = result.getReason() == null || result.getReason().isBlank() ? "模型发现的新业务语义" : result.getReason();
            JsonNode finding = readSnapshot(result.getFindingJson());
            JsonNode proposal = firstCandidate(finding);
            JsonNode targetLabel = autoExpandTarget(snapshot, proposal);
            if (targetLabel == null) { recordCandidateDiagnostic(result, "没有唯一且允许自动扩展的目标标签群组"); return; }
            String proposedName = proposal == null ? null : proposal.path("name").asText(null);
            String name = proposedName == null || proposedName.isBlank() ? (reason.length() > 50 ? reason.substring(0, 50) : reason) : proposedName.substring(0, Math.min(50, proposedName.length()));
            String proposedCode = proposal == null ? null : proposal.path("code").asText(null);
            String code = proposedCode == null || proposedCode.isBlank() ? "ai_" + Integer.toUnsignedString((message.getConversationId() + ":" + name).hashCode(), 36) : proposedCode;
            BigDecimal confidence = BigDecimal.ZERO;
            if (finding != null && finding.has("confidence")) confidence = finding.path("confidence").decimalValue();
            if (proposal != null && proposal.path("confidence").isNumber()) confidence = proposal.path("confidence").decimalValue();
            if (task.getConfidenceThreshold() != null && confidence.compareTo(task.getConfidenceThreshold()) >= 0) {
                recordCandidateDiagnostic(result, "候选置信度已达到正式判定阈值，不进入低置信候选池"); return;
            }
            labelCandidateService.propose(new LabelCandidateService.Proposal(task.getId(), message.getConversationId(), null,
                    targetLabel.path("groupId").asText(null), name, code,
                    proposal == null ? task.getAutoExpandPrompt() : proposal.path("description").asText(task.getAutoExpandPrompt()),
                    proposal == null || proposal.path("value").isMissingNode() ? null : proposal.path("value").toString(),
                    result.getEvidenceJson(), confidence, task.getAgentSnapshotJson()));
        } catch (RuntimeException exception) {
            recordCandidateDiagnostic(result, "候选生成失败: " + exception.getClass().getSimpleName());
            log.warn("event=iqc_label_candidate taskId={} conversationId={} status=SKIPPED errorType={}",
                    task.getId(), message.getConversationId(), exception.getClass().getSimpleName());
        }
    }

    private JsonNode autoExpandTarget(JsonNode snapshot, JsonNode proposal) {
        if (snapshot == null) return null;
        String groupId = proposal == null ? "" : proposal.path("groupId").asText();
        String groupCode = proposal == null ? "" : proposal.path("groupCode").asText();
        Map<String, JsonNode> allowedGroups = new LinkedHashMap<>();
        for (JsonNode label : snapshot.path("labels")) if (label.path("groupAllowAutoExpand").asBoolean(false))
            allowedGroups.putIfAbsent(label.path("groupId").asText(), label);
        if (!groupId.isBlank()) return allowedGroups.get(groupId);
        if (!groupCode.isBlank()) return allowedGroups.values().stream().filter(label -> groupCode.equals(label.path("groupCode").asText())).findFirst().orElse(null);
        return allowedGroups.size() == 1 ? allowedGroups.values().iterator().next() : null;
    }

    private void recordCandidateDiagnostic(InspectionResult result, String message) {
        try {
            JsonNode parsed = readSnapshot(result.getFindingJson());
            ObjectNode finding = parsed != null && parsed.isObject() ? (ObjectNode) parsed.deepCopy() : objectMapper.createObjectNode();
            finding.put("candidateDiagnostic", message); result.setFindingJson(finding.toString()); resultMapper.updateById(result);
        } catch (RuntimeException exception) {
            log.warn("event=iqc_label_candidate_diagnostic resultId={} errorType={}", result.getId(), exception.getClass().getSimpleName());
        }
    }

    private JsonNode firstCandidate(JsonNode node) {
        if (node == null) return null;
        if (node.isObject() && node.path("candidates").isArray() && !node.path("candidates").isEmpty()) return node.path("candidates").path(0);
        if (node.isContainerNode()) for (JsonNode child : node) { JsonNode found = firstCandidate(child); if (found != null) return found; }
        return null;
    }

    private InspectionResult evaluate(InspectionTask task, ConversationMessage message, JsonNode ruleSnapshot,
                                      List<ConversationMessage> conversationMessages) {
        return evaluate(task, message, ruleSnapshot, conversationMessages, new LinkedHashMap<>(), new LinkedHashMap<>(), 0);
    }

    private InspectionResult evaluate(InspectionTask task, ConversationMessage message, JsonNode ruleSnapshot,
                                      List<ConversationMessage> conversationMessages,
                                      Map<String, List<LlmQualityProvider.LlmEvaluation>> conversationLlmRuns,
                                      Map<String, String> conversationFactOwners, int runIndex) {
        List<JsonNode> rules = new ArrayList<>();
        String aggregationMode = "ANY";
        if (ruleSnapshot != null) {
            if (ruleSnapshot.isArray()) ruleSnapshot.forEach(rules::add);
            else if (ruleSnapshot.has("rules") && ruleSnapshot.get("rules").isArray()) {
                ruleSnapshot.get("rules").forEach(rules::add);
                aggregationMode = ruleSnapshot.path("aggregationMode").asText("ANY").toUpperCase();
            }
            else rules.add(ruleSnapshot);
        }
        if (rules.isEmpty()) return evaluateSingle(task, message, null, null, conversationMessages);

        String mode = taskQualityMode(task);
        List<JsonNode> localRules = rules.stream().filter(rule -> !"LLM".equalsIgnoreCase(rule.path("ruleType").asText())).toList();
        List<JsonNode> llmRules = rules.stream().filter(rule -> "LLM".equalsIgnoreCase(rule.path("ruleType").asText())).toList();
        List<InspectionResult> evaluated = new ArrayList<>();

        if ("RULE_ONLY".equals(mode)) {
            localRules.forEach(rule -> evaluated.add(evaluateSingle(task, message, rule, null, conversationMessages)));
        } else if ("RULE_THEN_LLM".equals(mode)) {
            localRules.forEach(rule -> evaluated.add(evaluateSingle(task, message, rule, null, conversationMessages)));
            ArrayNode preRuleFindings = objectMapper.createArrayNode();
            evaluated.forEach(item -> {
                if ("HIT".equals(item.getResultStatus())) {
                    ObjectNode finding = objectMapper.createObjectNode();
                    finding.put("ruleId", item.getRuleId()); finding.put("status", item.getResultStatus());
                    finding.put("reason", item.getReason()); finding.put("evidence", item.getEvidence());
                    preRuleFindings.add(finding);
                }
            });
            if (preRuleFindings.isEmpty()) {
                llmRules.forEach(rule -> evaluated.add(notEvaluated(task, message, rule, "本地规则未命中，跳过 LLM 复核")));
            } else {
                // A local hit is only a candidate in this mode; final scoring is decided by LLM confirmation.
                evaluated.stream().filter(item -> "HIT".equals(item.getResultStatus()))
                        .forEach(this::markCandidate);
                if (llmRules.isEmpty()) {
                    // Rule+Agent mode always uses the Agent model to review local candidates.
                    ObjectNode agentReviewRule = agentReviewRule(task, "复核本地规则候选结果");
                    rules.add(agentReviewRule);
                    evaluated.add(evaluateSingle(task, message, agentReviewRule, preRuleFindings, conversationMessages,
                            conversationLlmRuns, conversationFactOwners, runIndex));
                } else {
                    llmRules.forEach(rule -> evaluated.add(evaluateSingle(task, message, rule, preRuleFindings, conversationMessages,
                            conversationLlmRuns, conversationFactOwners, runIndex)));
                }
            }
        } else if ("LLM_THEN_RULE".equals(mode)) {
            ObjectNode extractionRule = agentReviewRule(task, "先提取可能命中的业务标签、标签值、证据和置信度");
            InspectionResult candidate = evaluateSingle(task, message, extractionRule, null, conversationMessages);
            if ("HIT".equals(candidate.getResultStatus())) {
                markCandidate(candidate);
                evaluated.add(candidate);
                Set<String> candidateRuleIds = candidateRuleIds(task, candidate);
                localRules.forEach(rule -> evaluated.add(candidateRuleIds.contains(rule.path("id").asText())
                        ? evaluateSingle(task, message, rule, null, conversationMessages)
                        : notEvaluated(task, message, rule, "规则未关联到 LLM 提取的候选标签")));
            } else {
                evaluated.add(candidate);
                localRules.forEach(rule -> evaluated.add(notEvaluated(task, message, rule, "LLM 未提取到候选，跳过确定性规则复核")));
            }
        } else if ("AGENT_LLM".equals(mode)) {
            // Agent mode delegates semantic checks to LLM rules; deterministic rules are not part of this mode.
            if (llmRules.isEmpty()) {
                ObjectNode agentRule = agentReviewRule(task, "根据 Agent 提示词、Skill 和可用能力进行综合质检");
                rules.clear();
                rules.add(agentRule);
                evaluated.add(evaluateSingle(task, message, agentRule, null, conversationMessages));
            } else {
                llmRules.forEach(rule -> evaluated.add(evaluateSingle(task, message, rule, null, conversationMessages,
                        conversationLlmRuns, conversationFactOwners, runIndex)));
            }
        } else {
            // Legacy tasks retain the historical independent rule execution semantics.
            rules.forEach(rule -> evaluated.add(evaluateSingle(task, message, rule, null, conversationMessages,
                    conversationLlmRuns, conversationFactOwners, runIndex)));
        }
        if (evaluated.isEmpty()) return notEvaluated(task, message, null, "当前模式没有可执行规则");
        InspectionResult aggregate = evaluated.get(0);
        boolean anyHit = evaluated.stream().anyMatch(item -> "HIT".equals(item.getResultStatus()));
        boolean allHit = evaluated.stream().allMatch(item -> "HIT".equals(item.getResultStatus()));
        boolean aggregateHit = "ALL".equals(aggregationMode) ? allHit : anyHit;
        boolean anyError = evaluated.stream().anyMatch(item -> item.getResultStatus() != null && item.getResultStatus().endsWith("ERROR"));
        int deduction = evaluated.stream().mapToInt(item -> item.getDeduction() == null ? 0 : item.getDeduction()).sum();
        boolean veto = rules.stream().anyMatch(rule -> rule.path("veto").asBoolean(false)
                && evaluated.stream().anyMatch(item -> rule.path("id").asText().equals(item.getRuleId()) && "HIT".equals(item.getResultStatus())));
        List<String> ruleIds = evaluated.stream().map(InspectionResult::getRuleId).filter(id -> id != null && !id.isBlank()).distinct().toList();
        ArrayNode findings = objectMapper.createArrayNode();
        ObjectNode ruleFindings = objectMapper.createObjectNode();
        ArrayNode evidences = objectMapper.createArrayNode();
        ArrayNode suggestions = objectMapper.createArrayNode();
        ArrayNode breakdown = objectMapper.createArrayNode();
        evaluated.forEach(item -> {
            mergeJsonArray(findings, item.getFindingJson()); mergeJsonArray(evidences, item.getEvidenceJson()); mergeJsonArray(suggestions, item.getSuggestionJson());
            if (item.getRuleId() != null && item.getFindingJson() != null) {
                try { ruleFindings.set(item.getRuleId(), objectMapper.readTree(item.getFindingJson())); }
                catch (com.fasterxml.jackson.core.JsonProcessingException exception) { ruleFindings.putNull(item.getRuleId()); }
            }
        });
        for (int index = 0; index < evaluated.size(); index++) {
            InspectionResult item = evaluated.get(index);
            JsonNode evaluatedRule = rules.stream().filter(rule -> java.util.Objects.equals(rule.path("id").asText(), item.getRuleId()))
                    .findFirst().orElseGet(() -> syntheticRule(item));
            breakdown.add(objectMapper.valueToTree(breakdownDetail(item, evaluatedRule, item.getResultStatus(),
                    item.getDeduction() == null ? 0 : item.getDeduction(), item.getReason())));
        }
        aggregate.setRuleId(String.join(",", ruleIds));
        aggregate.setResultStatus(anyError ? (aggregateHit ? "PARTIAL_ERROR" : "ERROR") : aggregateHit ? "HIT" : "NOT_HIT");
        aggregate.setDeduction(aggregateHit ? Math.min(100, deduction) : 0);
        aggregate.setScore(anyError ? 0 : aggregateHit ? (veto ? 0 : Math.max(0, 100 - aggregate.getDeduction())) : 100);
        aggregate.setRiskLevel(evaluated.stream().map(InspectionResult::getRiskLevel).max(this::compareRisk).orElse("LOW"));
        aggregate.setReason(evaluated.stream().map(InspectionResult::getReason).filter(reason -> reason != null && !reason.isBlank()).reduce((a, b) -> a + "；" + b).orElse("未选择规则"));
        // A conversation verdict is not a citation to every projected message. Preserve legacy evidence otherwise.
        aggregate.setEvidence(rules.stream().anyMatch(rule -> "CONVERSATION".equals(rule.path("inspectionScope").asText()))
                ? aggregateHit ? evaluated.stream().filter(item -> "HIT".equals(item.getResultStatus()))
                    .map(InspectionResult::getEvidence).filter(java.util.Objects::nonNull).distinct()
                    .reduce((left, right) -> left + "；" + right).orElse(null) : null
                : aggregateHit ? message.getContent() : null);
        if (rules.stream().anyMatch(rule -> rule.path("labelFactTargets").isArray())) {
            aggregate.setFindingJson(objectMapper.createObjectNode().set("ruleFindings", ruleFindings).toString());
        } else aggregate.setFindingJson(findings.toString());
        aggregate.setEvidenceJson(evidences.toString()); aggregate.setSuggestionJson(suggestions.toString());
        aggregate.setRuleBreakdownJson(breakdown.toString());
        return aggregate;
    }

    private ObjectNode syntheticRule(InspectionResult result) {
        ObjectNode rule = objectMapper.createObjectNode();
        rule.put("id", result.getRuleId()); rule.put("name", "Agent 候选提取");
        rule.put("ruleType", "LLM"); rule.put("targetRole", result.getSpeakerRole() == null ? "all" : result.getSpeakerRole());
        rule.put("deduction", result.getDeduction() == null ? 0 : result.getDeduction());
        rule.put("riskLevel", result.getRiskLevel());
        return rule;
    }

    private ObjectNode agentReviewRule(InspectionTask task, String expression) {
        ObjectNode rule = objectMapper.createObjectNode();
        rule.put("id", "agent:" + task.getAgentId());
        rule.put("name", "Agent 综合质检"); rule.put("ruleType", "LLM");
        rule.put("expression", expression);
        rule.put("targetRole", "all"); rule.put("deduction", 0); rule.put("riskLevel", "MEDIUM");
        return rule;
    }

    private Set<String> candidateRuleIds(InspectionTask task, InspectionResult candidate) {
        JsonNode snapshot = readSnapshot(task.getLabelScopeSnapshotJson());
        JsonNode finding = readSnapshot(candidate.getFindingJson());
        if (snapshot == null || finding == null) return Set.of();
        Set<String> labelIds = new java.util.HashSet<>();
        Set<String> labelCodes = new java.util.HashSet<>();
        collectCandidateLabels(finding, labelIds, labelCodes);
        Set<String> ruleIds = new java.util.HashSet<>();
        for (JsonNode label : snapshot.path("labels")) {
            if (!labelIds.contains(label.path("id").asText()) && !labelCodes.contains(label.path("code").asText())) continue;
            label.path("bindings").forEach(binding -> ruleIds.add(binding.path("ruleId").asText()));
        }
        return ruleIds;
    }

    private void collectCandidateLabels(JsonNode node, Set<String> ids, Set<String> codes) {
        if (node == null) return;
        if (node.isObject() && node.path("candidates").isArray()) node.path("candidates").forEach(candidate -> {
            String id = candidate.path("labelId").asText(); if (!id.isBlank()) ids.add(id);
            String code = candidate.path("labelCode").asText(); if (!code.isBlank()) codes.add(code);
        });
        if (node.isContainerNode()) node.forEach(child -> collectCandidateLabels(child, ids, codes));
    }

    private int compareRisk(String left, String right) {
        return Integer.compare(riskRank(left), riskRank(right));
    }

    private int riskRank(String risk) {
        return switch (risk == null ? "LOW" : risk.toUpperCase()) { case "HIGH" -> 3; case "MEDIUM" -> 2; default -> 1; };
    }

    private void mergeJsonArray(ArrayNode target, String json) {
        if (json == null || json.isBlank()) return;
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isArray()) target.addAll((ArrayNode) node);
            else target.add(node);
        } catch (Exception ignored) {
            // 单条结果的解释字段损坏不能阻断同一消息的其他规则结果。
        }
    }

    /** Internal conversation pipeline; queue integration must fence execution ownership and cancellation separately. */
    io.github.opensabre.iqc.result.model.ConversationInspectionResult materializeItemRouteConversation(
            InspectionTask task, String executionId, JsonNode snapshot, List<ConversationMessage> messages) {
        return materializeItemRouteConversation(task, executionId, snapshot, messages, null);
    }

    private io.github.opensabre.iqc.result.model.ConversationInspectionResult materializeItemRouteConversation(
            InspectionTask task, String executionId, JsonNode snapshot, List<ConversationMessage> messages, List<TaskItem> batch) {
        io.github.opensabre.iqc.scheme.SchemeDependencyResolver.validateRouteTaskProjection(snapshot, task.getAgentSnapshotJson(), objectMapper);
        var definition = io.github.opensabre.iqc.scheme.SchemeResultEvaluator.definition(snapshot, objectMapper);
        if (definition == null || !SchemeDefinition.ROUTED_TASK_SCHEMA.equals(definition.schemaVersion()))
            throw IqcException.invalidState("逐项会话执行需要明确的冻结路线协议");
        requireSupportedRouteRunCount(task, snapshot);
        final io.github.opensabre.iqc.scheme.SchemeDependencyResolver.RoutePlan plan;
        try {
            plan = objectMapper.treeToValue(snapshot.at("/schemeSnapshot/release/dependencies/itemExecutionPlan"),
                    io.github.opensabre.iqc.scheme.SchemeDependencyResolver.RoutePlan.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw IqcException.invalidState("逐项冻结执行计划无法读取");
        }
        var rules = new ArrayList<JsonNode>(); snapshot.path("rules").forEach(rules::add);
        if (batch != null) for (var item : batch)
            if (!task.getId().equals(item.getTaskId()) || !executionId.equals(item.getExecutionId())
                    || messages.stream().noneMatch(message -> item.getMessageId().equals(message.getId())
                    && java.util.Objects.equals(item.getConversationId(), message.getConversationId())))
                throw IqcException.invalidState("逐项会话处理进度或源消息不一致");
        var ownedTask = new InspectionTask(); org.springframework.beans.BeanUtils.copyProperties(task, ownedTask);
        ownedTask.setCurrentExecutionId(executionId);
        var run = evaluateItemRoutes(ownedTask, plan, rules, messages);
        var business = scoreItemRoutes(definition, plan, run, messages, rules);
        if (batch != null) {
            var observations = new ArrayList<InspectionResult>();
            for (var item : batch) {
                var message = messages.stream().filter(value -> item.getMessageId().equals(value.getId())).findFirst().orElseThrow();
                var result = aggregateItemStage(run.items().stream().map(this::terminalObservation).toList());
                result.setTaskId(task.getId()); result.setExecutionId(executionId); result.setConversationId(message.getConversationId());
                result.setMessageId(message.getId()); result.setSpeakerRole(message.getSpeakerRole()); result.setScore(null);
                result.setRiskLevel("ERROR".equals(result.getResultStatus()) ? "HIGH" : "LOW");
                result.setReason("会话逐项检测的消息观察投影，不代表当前消息违规，业务结论见质检项目");
                var evidence = objectMapper.createArrayNode();
                var values = readSnapshot(result.getEvidenceJson());
                if (values != null && values.isArray()) values.forEach(value -> {
                    if (message.getId().equals(value.path("messageId").asText())) evidence.add(value);
                });
                result.setEvidenceJson(evidence.toString());
                var payload = objectMapper.createObjectNode().put("schemaVersion", "iqc-item-route-observation-v1").put("projectionOnly", true);
                payload.set("items", objectMapper.valueToTree(plan.items())); result.setFindingJson(payload.toString());
                result.setRuleBreakdownJson("[]"); result.setSuggestionJson("[]"); observations.add(result);
            }
            var committed = hierarchicalResultService.materializeItemRouteBatch(task, executionId, snapshot, messages, plan, run, business, observations, batch);
            // Emit only after the transactional collaborator returns: rejected or rolled-back batches
            // must not count as completed inspections. Reuse legacy stable identities for deduplication.
            if (committed != null) for (var observation : observations) {
                String recordId = "inspection-message:" + task.getId() + ":" + observation.getMessageId();
                usageCounterRecorder.record(new UsageRecord(recordId + ":attempt", null, "iqc-platform", "INSPECTION_MESSAGE",
                        observation.getMessageId(), "QUALITY_CHECK", UsageOutcome.ATTEMPT));
                boolean failed = "ERROR".equals(observation.getResultStatus());
                usageCounterRecorder.record(new UsageRecord(recordId + (failed ? ":failure" : ":success"), null,
                        "iqc-platform", "INSPECTION_MESSAGE", observation.getMessageId(), "QUALITY_CHECK",
                        failed ? UsageOutcome.FAILURE : UsageOutcome.SUCCESS));
            }
            return committed;
        }
        return hierarchicalResultService.materializeItemRoutes(task, executionId, snapshot, messages, plan, run, business);
    }

    /** Bridges frozen per-item contexts to existing detectors; caller owns terminal persistence and scoring. */
    ItemRouteRunner.Run evaluateItemRoutes(InspectionTask task,
            io.github.opensabre.iqc.scheme.SchemeDependencyResolver.RoutePlan plan,
            List<JsonNode> frozenRules, List<ConversationMessage> messages) {
        var rules = new LinkedHashMap<String, JsonNode>();
        frozenRules.forEach(rule -> {
            String id = rule.path("id").asText();
            if (id.isBlank() || rules.putIfAbsent(id, rule) != null)
                throw io.github.opensabre.iqc.governance.IqcException.invalidState("逐项冻结规则缺失或重复");
        });
        var quotes = new LinkedHashMap<String, List<ConversationMessage>>();
        ItemRouteRunner.Detector detector = new ItemRouteRunner.Detector() {
            @Override public InspectionResult evaluate(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.DetectionContext context,
                    List<ConversationMessage> inputs, InspectionResult upstream) {
                return evaluateItemRouteStage(task, rules, quotes, context, inputs, upstream, 0);
            }
            @Override public InspectionResult evaluate(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.DetectionContext context,
                    List<ConversationMessage> inputs, InspectionResult upstream, int runIndex) {
                return evaluateItemRouteStage(task, rules, quotes, context, inputs, upstream, runIndex);
            }
        };
        int runCount = task.getRunCount() == null ? 1 : task.getRunCount();
        return ItemRouteRunner.execute(plan, messages, runCount, task.getConfidenceThreshold(), detector,
                candidate -> quotes.get(readSnapshot(candidate.getFindingJson()).path("contextKey").asText()));
    }

    private InspectionResult evaluateItemRouteStage(InspectionTask task, Map<String, JsonNode> rules,
            Map<String, List<ConversationMessage>> quotes,
            io.github.opensabre.iqc.scheme.SchemeDependencyResolver.DetectionContext context,
            List<ConversationMessage> inputs, InspectionResult upstream, int runIndex) {
            JsonNode original = rules.get(context.ruleId());
            if (original == null) throw io.github.opensabre.iqc.governance.IqcException.invalidState("逐项冻结规则不存在");
            var rule = (ObjectNode) original.deepCopy(); rule.put("deduction", 0); rule.put("veto", false);
            rule.put("inspectionScope", context.inputScope());
            var stageTask = new InspectionTask(); org.springframework.beans.BeanUtils.copyProperties(task, stageTask);
            stageTask.setCurrentExecutionId(task.getCurrentExecutionId() + ":route-run:" + (runIndex + 1));
            // Usage/cache identity includes the frozen context, not only a rule shared by different consumers.
            stageTask.setId(task.getId() + ":context:" + context.contextKey() + ":route-run:" + (runIndex + 1));
            String recordId = "inspection-candidate:" + stageTask.getId() + ":" + stageTask.getCurrentExecutionId()
                    + ":" + (inputs.isEmpty() ? "empty" : inputs.getFirst().getConversationId());
            var applicable = inputs.stream().filter(message -> {
                String role = rule.path("targetRole").asText("all");
                return role.isBlank() || "all".equalsIgnoreCase(role) || role.equalsIgnoreCase(message.getSpeakerRole());
            }).toList();
            if (applicable.isEmpty()) {
                var outcome = itemStageOutcome("NOT_HIT", "无适用说话人消息");
                if (rule.path("labelFactTargets").isArray()) {
                    var finding = objectMapper.createObjectNode().put("schemaVersion", "iqc-label-facts-v2");
                    finding.putArray("facts"); outcome.setFindingJson(finding.toString());
                }
                return outcome;
            }
            if ("VERIFY".equals(context.phase()) && "DLS".equalsIgnoreCase(rule.path("ruleType").asText()))
                return itemStageOutcome("REVIEW_REQUIRED", "DLS 候选切片的时序语义尚未验证");
            JsonNode findings = null;
            if (upstream != null && "REVIEW".equals(context.phase())) {
                var array = objectMapper.createArrayNode();
                var finding = array.addObject().put("ruleId", upstream.getRuleId()).put("status", upstream.getResultStatus())
                        .put("reason", upstream.getReason());
                finding.set("evidence", readSnapshot(upstream.getEvidenceJson())); findings = array;
            }
            var results = new ArrayList<InspectionResult>();
            var batches = "CONVERSATION".equals(context.inputScope()) ? List.of(inputs)
                    : applicable.stream().map(List::of).toList();
            var decodedQuotes = new ArrayList<ConversationMessage>();
            for (var batch : batches) {
                if ("CANDIDATE".equals(context.phase())) {
                    var decoded = ItemCandidateOutput.decode(objectMapper, llmQualityProvider.evaluateCandidates(batch, rule,
                            readSnapshot(task.getAgentSnapshotJson()), recordId + ":" + batch.getFirst().getId()));
                    results.add(decoded.result()); decodedQuotes.addAll(decoded.quotes());
                } else {
                    var anchor = "CONVERSATION".equals(context.inputScope()) ? applicable.getFirst() : batch.getFirst();
                    results.add(evaluateSingle(stageTask, anchor, rule, findings, batch,
                            new LinkedHashMap<>(), new LinkedHashMap<>(), 0));
                }
            }
            var aggregate = aggregateItemStage(results, rule.path("labelFactTargets").isArray());
            aggregate.setRuleId(context.ruleId()); aggregate.setTaskId(task.getId());
            aggregate.setConversationId(inputs.getFirst().getConversationId());
            if ("CANDIDATE".equals(context.phase())) {
                quotes.put(context.contextKey(), List.copyOf(decodedQuotes));
                aggregate.setFindingJson(objectMapper.createObjectNode().put("contextKey", context.contextKey()).toString());
            }
            return aggregate;
    }

    /** Projects only item terminal contexts into the existing independent business scoring engine. */
    io.github.opensabre.iqc.scheme.SchemeResultEvaluator.Evaluation scoreItemRoutes(
            SchemeDefinition definition, io.github.opensabre.iqc.scheme.SchemeDependencyResolver.RoutePlan plan,
            ItemRouteRunner.Run run, List<ConversationMessage> messages, List<JsonNode> frozenRules) {
        var frozenById = new LinkedHashMap<String, JsonNode>();
        for (var rule : frozenRules) if (frozenById.putIfAbsent(rule.path("id").asText(), rule) != null)
            throw IqcException.invalidState("逐项评分冻结规则重复");
        var contexts = plan.contexts().stream().collect(java.util.stream.Collectors.toMap(
                io.github.opensabre.iqc.scheme.SchemeDependencyResolver.DetectionContext::contextKey, context -> context));
        var routes = plan.items().stream().collect(java.util.stream.Collectors.toMap(
                io.github.opensabre.iqc.scheme.SchemeDependencyResolver.ItemRoute::itemCode, route -> route));
        var terminals = new LinkedHashMap<String, ItemRouteRunner.Item>();
        var expected = definition.items().stream().map(SchemeDefinition.Item::itemCode).collect(java.util.stream.Collectors.toSet());
        if (!routes.keySet().equals(expected)) throw io.github.opensabre.iqc.governance.IqcException.invalidState("逐项计划与业务项目不一致");
        for (var item : run.items()) {
            if (!expected.contains(item.itemCode()) || terminals.putIfAbsent(item.itemCode(), item) != null)
                throw io.github.opensabre.iqc.governance.IqcException.invalidState("逐项终态缺少身份或重复");
        }
        var decisions = new ArrayList<io.github.opensabre.iqc.scheme.SchemeResultEvaluator.ItemResult>();
        var messageIds = messages.stream().map(ConversationMessage::getId).collect(java.util.stream.Collectors.toSet());
        for (var item : definition.items()) {
            var route = routes.get(item.itemCode()); var context = contexts.get(route.finalContextKey());
            if (context == null || !item.rule().id().equals(context.ruleId()) || item.rule().versionNo() != context.versionNo())
                throw io.github.opensabre.iqc.governance.IqcException.invalidState("逐项终态与业务规则版本不一致");
            var routeResult = terminals.get(item.itemCode());
            if (routeResult == null) {
                decisions.add(new io.github.opensabre.iqc.scheme.SchemeResultEvaluator.ItemResult(item.itemCode(), item.name(),
                        item.rule().id(), item.rule().versionNo(), InspectionScoring.ItemStatus.NOT_EVALUATED, List.of()));
                continue;
            }
            var gateContext = route.applicabilityContextKey() == null ? null : contexts.get(route.applicabilityContextKey());
            if (item.appliesWhen() == null) {
                if (route.applicabilityContextKey() != null || routeResult.applicability() != null)
                    throw io.github.opensabre.iqc.governance.IqcException.invalidState("逐项计划包含未声明的适用条件");
            } else if (gateContext == null || routeResult.applicability() == null
                    || !route.applicabilityContextKey().equals(routeResult.applicability().contextKey())
                    || !"APPLICABILITY".equals(gateContext.phase())
                    || !item.appliesWhen().id().equals(gateContext.ruleId())
                    || item.appliesWhen().versionNo() != gateContext.versionNo()
                    || routeResult.applicability().result() == null
                    || !item.appliesWhen().id().equals(routeResult.applicability().result().getRuleId()))
                throw io.github.opensabre.iqc.governance.IqcException.invalidState("逐项适用条件与冻结执行计划不一致");
            var frozenRule = frozenById.get(context.ruleId());
            if (frozenRule == null || frozenRule.path("versionNo").asInt() != context.versionNo())
                throw IqcException.invalidState("逐项评分冻结规则版本不一致");
            String role = frozenRule.path("targetRole").asText("all");
            var applicableIds = messages.stream().filter(message -> role.isBlank() || "all".equalsIgnoreCase(role)
                    || role.equalsIgnoreCase(message.getSpeakerRole())).map(ConversationMessage::getId)
                    .collect(java.util.stream.Collectors.toSet());
            var terminal = routeResult.terminal();
            String adjudicated = routeResult.adjudicatedStatus();
            InspectionScoring.ItemStatus status = InspectionScoring.ItemStatus.NOT_EVALUATED;
            var matched = new java.util.LinkedHashSet<String>();
            if ("HIT".equals(adjudicated) || "NOT_HIT".equals(adjudicated)) {
                if (terminal == null || !route.finalContextKey().equals(terminal.contextKey())
                        || terminal.result() == null || !context.ruleId().equals(terminal.result().getRuleId()))
                    throw io.github.opensabre.iqc.governance.IqcException.invalidState("逐项评分不能消费中间或其他上下文结果");
                var result = terminal.result();
                if (!adjudicated.equals(result.getResultStatus()))
                    throw IqcException.invalidState("逐项最终投票与证据上下文不一致");
                boolean failed = item.hitMeaning() == SchemeDefinition.HitMeaning.VIOLATION
                        ? "HIT".equals(adjudicated) : "NOT_HIT".equals(adjudicated);
                status = failed ? InspectionScoring.ItemStatus.FAIL : InspectionScoring.ItemStatus.PASS;
                if ("HIT".equals(adjudicated)) {
                    var evidence = readSnapshot(result.getEvidenceJson());
                    if (evidence != null && evidence.isArray()) evidence.forEach(value -> {
                        String id = value.path("messageId").asText();
                        if (context.ruleId().equals(value.path("ruleId").asText()) && messageIds.contains(id)
                                && applicableIds.contains(id)) matched.add(id);
                    });
                }
            } else status = switch (adjudicated) {
                case "NOT_APPLICABLE" -> InspectionScoring.ItemStatus.NOT_APPLICABLE;
                case "ERROR" -> InspectionScoring.ItemStatus.ERROR;
                case "REVIEW_REQUIRED" -> InspectionScoring.ItemStatus.REVIEW_REQUIRED;
                default -> InspectionScoring.ItemStatus.NOT_EVALUATED;
            };
            // Applicability is determined from immutable rule roles, never inferred from a missing detector verdict.
            if (messages.isEmpty()) status = InspectionScoring.ItemStatus.NOT_EVALUATED;
            else if (status != InspectionScoring.ItemStatus.ERROR && status != InspectionScoring.ItemStatus.REVIEW_REQUIRED
                    && status != InspectionScoring.ItemStatus.NOT_EVALUATED && applicableIds.isEmpty()) {
                status = InspectionScoring.ItemStatus.NOT_APPLICABLE; matched.clear();
            }
            decisions.add(new io.github.opensabre.iqc.scheme.SchemeResultEvaluator.ItemResult(item.itemCode(), item.name(),
                    item.rule().id(), item.rule().versionNo(), status, List.copyOf(matched)));
        }
        return io.github.opensabre.iqc.scheme.SchemeResultEvaluator.scoreItems(definition, decisions);
    }

    private InspectionResult aggregateItemStage(List<InspectionResult> results) {
        return aggregateItemStage(results, false);
    }

    private InspectionResult aggregateItemStage(List<InspectionResult> results, boolean labelFacts) {
        String status = results.stream().anyMatch(result -> "ERROR".equals(result.getResultStatus())) ? "ERROR"
                : results.stream().anyMatch(result -> "REVIEW_REQUIRED".equals(result.getResultStatus())) ? "REVIEW_REQUIRED"
                : results.stream().anyMatch(result -> "NOT_EVALUATED".equals(result.getResultStatus())) ? "NOT_EVALUATED"
                : results.stream().anyMatch(result -> "HIT".equals(result.getResultStatus())) ? "HIT" : "NOT_HIT";
        var aggregate = itemStageOutcome(status, "逐项检测阶段完成");
        var evidence = objectMapper.createArrayNode();
        results.forEach(result -> {
            var values = readSnapshot(result.getEvidenceJson());
            if (values != null && values.isArray()) values.forEach(evidence::add);
        });
        aggregate.setEvidenceJson(evidence.toString());
        if (labelFacts) {
            var payload = objectMapper.createObjectNode().put("schemaVersion", "iqc-rule-observations-v2");
            var observations = payload.putArray("observations");
            for (var result : results) {
                var observation = observations.addObject().put("status", result.getResultStatus());
                if (result.getMessageId() != null) observation.put("messageId", result.getMessageId());
                var finding = readSnapshot(result.getFindingJson());
                if (finding != null && "iqc-label-facts-v2".equals(finding.path("schemaVersion").asText())
                        && finding.path("facts").isArray()) observation.set("finding", finding.deepCopy());
                else if ("HIT".equals(result.getResultStatus()) || "NOT_HIT".equals(result.getResultStatus()))
                    observation.put("findingError", "MISSING_FINDING");
            }
            aggregate.setFindingJson(payload.toString());
        }
        return aggregate;
    }

    /** A definite applicability miss intentionally has no terminal detector stage. */
    private InspectionResult terminalObservation(ItemRouteRunner.Item item) {
        if (item.terminal() != null && item.terminal().result() != null) return item.terminal().result();
        String status = switch (item.adjudicatedStatus() == null ? "" : item.adjudicatedStatus()) {
            case "ERROR" -> "ERROR";
            case "REVIEW_REQUIRED" -> "REVIEW_REQUIRED";
            default -> "NOT_EVALUATED";
        };
        String reason = "NOT_APPLICABLE".equals(item.adjudicatedStatus())
                ? "适用条件未命中，最终检测阶段未执行" : "逐项执行没有产生最终阶段观察";
        return itemStageOutcome(status, reason);
    }

    private InspectionResult itemStageOutcome(String status, String reason) {
        var result = new InspectionResult(); result.setResultStatus(status); result.setReason(reason);
        result.setDeduction(0); result.setEvidenceJson("[]"); result.setFindingJson("[]"); return result;
    }

    private InspectionResult evaluateSingle(InspectionTask task, ConversationMessage message, JsonNode rule) {
        return evaluateSingle(task, message, rule, null, List.of(message));
    }

    private InspectionResult evaluateSingle(InspectionTask task, ConversationMessage message, JsonNode rule,
                                            JsonNode preRuleFindings) {
        return evaluateSingle(task, message, rule, preRuleFindings, List.of(message));
    }

    private InspectionResult evaluateSingle(InspectionTask task, ConversationMessage message, JsonNode rule,
                                            JsonNode preRuleFindings, List<ConversationMessage> conversationMessages) {
        return evaluateSingle(task, message, rule, preRuleFindings, conversationMessages,
                new LinkedHashMap<>(), new LinkedHashMap<>(), 0);
    }

    private InspectionResult evaluateSingle(InspectionTask task, ConversationMessage message, JsonNode rule,
                                            JsonNode preRuleFindings, List<ConversationMessage> conversationMessages,
                                            Map<String, List<LlmQualityProvider.LlmEvaluation>> conversationLlmRuns,
                                            Map<String, String> conversationFactOwners, int runIndex) {
        InspectionResult result = new InspectionResult();
        result.setTaskId(task.getId()); result.setConversationId(message.getConversationId()); result.setMessageId(message.getId());
        result.setRuleId(rule == null ? null : rule.path("id").asText(null)); result.setSpeakerRole(message.getSpeakerRole());
        if (rule == null) { result.setResultStatus("NOT_EVALUATED"); result.setScore(0); result.setRiskLevel("LOW"); result.setDeduction(0); result.setReason("未选择规则"); result.setRuleBreakdownJson("[]"); return result; }
        try {
            String targetRole = rule.path("targetRole").asText("all");
            if (!targetRole.isBlank() && !"all".equalsIgnoreCase(targetRole)
                    && !targetRole.equalsIgnoreCase(message.getSpeakerRole())) {
                result.setResultStatus("NOT_HIT"); result.setScore(100); result.setRiskLevel("LOW"); result.setDeduction(0);
                result.setReason("规则不适用当前说话人");
                result.setFindingJson(rule.path("labelFactTargets").isArray()
                        ? objectMapper.createObjectNode().put("schemaVersion", "iqc-label-facts-v2").putArray("facts").toString()
                        : writeJson(List.of(finding(new Match(false, null, -1, -1), rule))));
                result.setEvidenceJson("[]"); result.setSuggestionJson("[]");
                if (rule.path("labelFactTargets").isArray())
                    result.setRuleBreakdownJson(writeJson(List.of(breakdownDetail(result, rule, "NOT_HIT", 0, result.getReason()))));
                return result;
            }
            String ruleType = rule.path("ruleType").asText();
            String expression = rule.path("expression").asText();
            if ("DLS".equalsIgnoreCase(ruleType)) {
                DlsEngine.Evaluation evaluation = DlsEngine.evaluate(compiledDls(expression), conversationMessages);
                DlsEngine.Hit anchor = evaluation.evidence().isEmpty() ? null : evaluation.evidence().get(0);
                JsonNode taskSnapshot = readSnapshot(task.getRuleSnapshotJson());
                boolean schemeTask = taskSnapshot != null && taskSnapshot.has("schemeSnapshot");
                // A V2 conversation match may be an absence condition with no positive message anchor.
                boolean hit = evaluation.hit() && (schemeTask || anchor != null && java.util.Objects.equals(anchor.messageId(), message.getId()));
                Match match = hit && anchor != null ? new Match(true, anchor.text(), anchor.start(), anchor.end()) : new Match(hit, null, -1, -1);
                int deduction = hit ? Math.max(0, Math.min(100, rule.path("deduction").asInt(10))) : 0;
                boolean veto = hit && rule.path("veto").asBoolean(false);
                result.setResultStatus(hit ? "HIT" : "NOT_HIT"); result.setScore(hit ? (veto ? 0 : 100 - deduction) : 100);
                result.setRiskLevel(hit ? rule.path("riskLevel").asText("MEDIUM") : "LOW"); result.setDeduction(deduction);
                result.setReason(evaluation.reason());
                result.setEvidence(hit ? evaluation.evidence().stream().map(DlsEngine.Hit::text).distinct()
                        .reduce((left, right) -> left + "；" + right).orElse(message.getContent()) : null);
                result.setFindingJson(writeJson(List.of(finding(match, rule))));
                if (schemeTask && hit) {
                    ArrayNode evidence = objectMapper.valueToTree(evaluation.evidence());
                    evidence.forEach(value -> ((ObjectNode) value).put("ruleId", rule.path("id").asText()));
                    result.setEvidenceJson(evidence.toString());
                } else result.setEvidenceJson(writeJson(hit ? evaluation.evidence() : List.of()));
                result.setSuggestionJson(writeJson(hit ? List.of(suggestion(rule)) : List.of()));
                result.setRuleBreakdownJson(writeJson(List.of(breakdownDetail(result, rule, result.getResultStatus(), deduction, result.getReason()))));
                return result;
            }
            if ("LLM".equalsIgnoreCase(ruleType)) {
                String recordId = "inspection-message:" + task.getId() + ":" + message.getId() + ":rule:" + rule.path("id").asText("unknown");
                boolean conversationFacts = rule.path("labelFactTargets").isArray();
                boolean conversationEvaluation = conversationFacts || "CONVERSATION".equals(rule.path("inspectionScope").asText());
                LlmQualityProvider.LlmEvaluation evaluation;
                if (conversationEvaluation) {
                    String ruleId = rule.path("id").asText();
                    String contextKey = preRuleFindings == null ? ruleId : ruleId + ":prefilter:"
                            + io.github.opensabre.iqc.scheme.InspectionSchemeService.contentHash(preRuleFindings.toString());
                    var priorRuns = conversationLlmRuns.computeIfAbsent(contextKey, ignored -> new ArrayList<>());
                    conversationFactOwners.putIfAbsent(ruleId, message.getId());
                    while (priorRuns.size() <= runIndex) {
                        int nextRun = priorRuns.size();
                        String conversationRecordId = "inspection-conversation:" + task.getId() + ":"
                                + task.getCurrentExecutionId() + ":" + message.getConversationId() + ":rule:" + contextKey + ":run:" + nextRun;
                        priorRuns.add(preRuleFindings == null ? llmQualityProvider.evaluateConversation(conversationMessages, rule,
                                readSnapshot(task.getAgentSnapshotJson()), conversationRecordId)
                                : llmQualityProvider.evaluateConversation(conversationMessages, rule,
                                readSnapshot(task.getAgentSnapshotJson()), preRuleFindings, conversationRecordId));
                    }
                    evaluation = priorRuns.get(runIndex);
                } else evaluation = preRuleFindings == null
                        ? llmQualityProvider.evaluate(message.getContent(), rule, readSnapshot(task.getAgentSnapshotJson()), recordId)
                        : llmQualityProvider.evaluate(message.getContent(), rule, readSnapshot(task.getAgentSnapshotJson()), preRuleFindings, recordId);
                if (!evaluation.supported()) {
                    result.setResultStatus("ERROR"); result.setScore(0); result.setRiskLevel("HIGH"); result.setDeduction(0);
                    result.setReason(evaluation.reason()); result.setFindingJson("[]"); result.setEvidenceJson("[]"); result.setSuggestionJson("[]");
                    result.setRuleBreakdownJson(writeJson(List.of(breakdownDetail(result, rule, "ERROR", 0, evaluation.reason()))));
                    return result;
                }
                Match match = new Match(evaluation.hit(), null, -1, -1);
                int deduction = match.hit() ? Math.max(0, Math.min(100, rule.path("deduction").asInt(10))) : 0;
                boolean veto = match.hit() && rule.path("veto").asBoolean(false);
                result.setResultStatus(match.hit() ? "HIT" : "NOT_HIT"); result.setScore(match.hit() ? (veto ? 0 : 100 - deduction) : 100);
                result.setRiskLevel(match.hit() ? rule.path("riskLevel").asText("MEDIUM") : "LOW"); result.setDeduction(deduction);
                result.setReason(evaluation.reason()); result.setEvidence(match.hit() && !conversationEvaluation ? message.getContent() : null);
                if (conversationFacts && evaluation.structuredJson() != null) {
                    JsonNode raw = readSnapshot(evaluation.structuredJson());
                    if (raw != null && raw.isObject() && !message.getId().equals(conversationFactOwners.get(rule.path("id").asText()))) {
                        ObjectNode empty = (ObjectNode) raw.deepCopy(); empty.putArray("facts");
                        result.setFindingJson(empty.toString());
                    } else result.setFindingJson(evaluation.structuredJson());
                } else result.setFindingJson(evaluation.structuredJson() == null ? writeJson(List.of(finding(match, rule))) : evaluation.structuredJson());
                result.setEvidenceJson(conversationFacts && message.getId().equals(conversationFactOwners.get(rule.path("id").asText()))
                        ? labelEvidence(result.getFindingJson(), rule.path("id").asText(), conversationMessages) : "[]");
                result.setSuggestionJson(writeJson(match.hit() ? List.of(suggestion(rule)) : List.of()));
                return result;
            }
            RuleMatcher.Match evaluated = RuleMatcher.evaluate(objectMapper, ruleType, expression, message);
            Match match = new Match(evaluated.hit(), evaluated.text(), evaluated.start(), evaluated.end());
            int deduction = match.hit() ? Math.max(0, Math.min(100, rule.path("deduction").asInt(10))) : 0;
            boolean veto = match.hit() && rule.path("veto").asBoolean(false);
            String riskLevel = match.hit() ? rule.path("riskLevel").asText("MEDIUM") : "LOW";
            result.setResultStatus(match.hit() ? "HIT" : "NOT_HIT"); result.setScore(match.hit() ? (veto ? 0 : 100 - deduction) : 100);
            result.setRiskLevel(riskLevel); result.setDeduction(deduction);
            result.setReason(match.hit() ? "命中规则" : "未命中规则"); result.setEvidence(match.hit() ? message.getContent() : null);
            result.setFindingJson(rule.path("labelFactTargets").isArray()
                    ? deterministicLabelFacts(rule, message, match) : writeJson(List.of(finding(match, rule))));
            result.setEvidenceJson(writeJson(match.hit() && (!rule.path("labelFactTargets").isArray() || hasLocatedMatch(message, match))
                    ? List.of(evidence(message, match, rule)) : List.of()));
            result.setSuggestionJson(writeJson(match.hit() ? List.of(suggestion(rule)) : List.of()));
            result.setRuleBreakdownJson(writeJson(List.of(breakdownDetail(result, rule, result.getResultStatus(), deduction, result.getReason()))));
        } catch (RuntimeException exception) {
            result.setResultStatus("ERROR"); result.setScore(0); result.setRiskLevel("HIGH"); result.setDeduction(0); result.setReason("规则执行失败: " + exception.getMessage());
        }
        return result;
    }

    private DlsEngine.Compiled compiledDls(String expression) {
        synchronized (dlsCompileCache) {
            DlsEngine.Compiled compiled = dlsCompileCache.get(expression);
            if (compiled == null) {
                compiled = DlsEngine.compile(objectMapper, expression);
                dlsCompileCache.put(expression, compiled);
            }
            return compiled;
        }
    }

    private InspectionResult notEvaluated(InspectionTask task, ConversationMessage message, JsonNode rule, String reason) {
        InspectionResult result = new InspectionResult();
        result.setTaskId(task.getId()); result.setConversationId(message.getConversationId()); result.setMessageId(message.getId());
        result.setRuleId(rule == null ? null : rule.path("id").asText(null)); result.setSpeakerRole(message.getSpeakerRole());
        result.setResultStatus("NOT_EVALUATED"); result.setScore(100); result.setRiskLevel("LOW"); result.setDeduction(0);
        result.setReason(reason); result.setFindingJson("[]"); result.setEvidenceJson("[]"); result.setSuggestionJson("[]");
        result.setRuleBreakdownJson(writeJson(List.of(breakdownDetail(result, rule, "NOT_EVALUATED", 0, reason))));
        return result;
    }

    private void markCandidate(InspectionResult result) {
        result.setResultStatus("CANDIDATE");
        try {
            JsonNode breakdown = objectMapper.readTree(result.getRuleBreakdownJson());
            if (breakdown.isArray()) breakdown.forEach(item -> { if (item.isObject()) ((ObjectNode) item).put("status", "CANDIDATE"); });
            result.setRuleBreakdownJson(breakdown.toString());
        } catch (Exception ignored) {
            // The original local evidence remains available even if its optional breakdown is malformed.
        }
    }

    /** New tasks own their strategy; absent snapshots retain the exact legacy Agent-mode interpretation. */
    private String taskQualityMode(InspectionTask task) {
        var mode = io.github.opensabre.iqc.task.TaskExecutionStrategy.fromSnapshot(readSnapshot(task.getRuleSnapshotJson()));
        return mode == null ? qualityMode(readSnapshot(task.getAgentSnapshotJson())) : mode.name();
    }

    private String qualityMode(JsonNode agentSnapshot) {
        if (agentSnapshot == null || agentSnapshot.isMissingNode()) return "LEGACY";
        JsonNode config = agentSnapshot.path("configJson");
        if (config.isTextual()) {
            try { config = objectMapper.readTree(config.asText()); }
            catch (Exception ignored) { return "LEGACY"; }
        }
        String mode = config.path("mode").asText("LEGACY").trim().toUpperCase();
        return Set.of("RULE_ONLY", "RULE_THEN_LLM", "LLM_THEN_RULE", "AGENT_LLM").contains(mode) ? mode : "LEGACY";
    }

    private List<String> conversationIds(InspectionTask task) {
        if (task.getConversationIdsJson() != null && !task.getConversationIdsJson().isBlank()) {
            try {
                JsonNode value = objectMapper.readTree(task.getConversationIdsJson());
                if (value.isArray()) {
                    List<String> ids = new ArrayList<>();
                    value.forEach(item -> { if (item.isTextual() && !item.asText().isBlank()) ids.add(item.asText()); });
                    if (!ids.isEmpty()) return ids;
                }
            } catch (Exception ignored) {
                // Legacy fallback below keeps pre-batch tasks executable.
            }
        }
        return task.getConversationId() == null || task.getConversationId().isBlank() ? List.of() : List.of(task.getConversationId());
    }

    private Map<String, Object> finding(Match match, JsonNode rule) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("type", "RULE_HIT"); finding.put("ruleId", rule.path("id").asText(null));
        finding.put("label", match.hit() ? "命中规则表达式" : "未命中规则表达式"); finding.put("severity", match.hit() ? rule.path("riskLevel").asText("MEDIUM") : "LOW");
        return finding;
    }

    /** Explicit expert mappings turn only located, same-speaker hits into label facts; misses stay UNKNOWN. */
    private String deterministicLabelFacts(JsonNode rule, ConversationMessage message, Match match) {
        var payload = objectMapper.createObjectNode().put("schemaVersion", "iqc-label-facts-v2");
        var facts = payload.putArray("facts");
        if (!match.hit()) return payload.toString();
        if (!hasLocatedMatch(message, match)) {
            payload.put("factError", "NO_LOCATED_EVIDENCE");
            return payload.toString();
        }
        for (JsonNode target : rule.path("labelFactTargets")) {
            if (!target.has("onRuleHitValue") || !target.path("subjectRole").asText().equals(message.getSpeakerRole())) continue;
            var fact = facts.addObject().put("labelId", target.path("labelId").asText())
                    .put("valueCode", target.path("valueCode").asText())
                    .put("ruleId", rule.path("id").asText())
                    .put("subjectKind", "CURRENT_PARTICIPANT")
                    .put("subjectRole", message.getSpeakerRole());
            fact.set("value", target.get("onRuleHitValue").deepCopy());
            fact.putArray("evidence").addObject().put("messageId", message.getId()).put("text", match.text());
        }
        return payload.toString();
    }

    private boolean hasLocatedMatch(ConversationMessage message, Match match) {
        return match.text() != null && !match.text().isBlank() && message.getContent() != null
                && match.start() >= 0 && match.end() >= match.start() && match.end() <= message.getContent().length()
                && message.getContent().substring(match.start(), match.end()).equals(match.text());
    }

    private Map<String, Object> evidence(ConversationMessage message, Match match, JsonNode rule) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("ruleId", rule.path("id").asText(null)); evidence.put("messageId", message.getId()); evidence.put("sequenceNo", message.getSequenceNo()); evidence.put("text", match.text());
        evidence.put("start", match.start()); evidence.put("end", match.end());
        return evidence;
    }

    /** Candidate quotes are persisted with their original message identity even when check hit is false. */
    private String labelEvidence(String findingJson, String ruleId, List<ConversationMessage> messages) {
        ArrayNode evidence = objectMapper.createArrayNode();
        JsonNode finding = readSnapshot(findingJson);
        if (finding == null || !"iqc-label-facts-v2".equals(finding.path("schemaVersion").asText())) return evidence.toString();
        Map<String, ConversationMessage> byId = new LinkedHashMap<>();
        messages.forEach(message -> byId.put(message.getId(), message));
        for (JsonNode fact : finding.path("facts")) {
            if (!ruleId.equals(fact.path("ruleId").asText())) continue;
            for (JsonNode quote : fact.path("evidence")) {
                var source = byId.get(quote.path("messageId").asText());
                String text = quote.path("text").asText();
                if (source == null || source.getContent() == null || text.isBlank()
                        || !fact.path("subjectRole").asText().equals(source.getSpeakerRole())) continue;
                int start = source.getContent().indexOf(text);
                if (start < 0) continue;
                evidence.addObject().put("ruleId", ruleId).put("messageId", source.getId())
                        .put("sequenceNo", source.getSequenceNo()).put("text", text)
                        .put("start", start).put("end", start + text.length());
            }
        }
        return evidence.toString();
    }

    private Map<String, Object> suggestion(JsonNode rule) {
        Map<String, Object> suggestion = new LinkedHashMap<>();
        suggestion.put("ruleId", rule.path("id").asText(null)); suggestion.put("title", "加强合规话术");
        suggestion.put("content", "建议结合业务规范补充标准解释和后续处理路径。");
        return suggestion;
    }

    /** Builds a stable, human-readable rule result snapshot for later review and export. */
    private Map<String, Object> breakdownDetail(InspectionResult result, JsonNode rule, String status, int deduction, String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ruleId", result.getRuleId());
        detail.put("ruleName", rule == null ? null : rule.path("name").asText(null));
        detail.put("ruleCode", rule == null ? null : rule.path("code").asText(null));
        detail.put("ruleVersion", rule == null ? null : rule.path("versionNo").asInt(0));
        String ruleType = rule == null ? null : rule.path("ruleType").asText(null);
        detail.put("ruleType", ruleType);
        detail.put("evaluationScope", "DLS".equalsIgnoreCase(ruleType) || rule != null && rule.path("labelFactTargets").isArray()
                ? "CONVERSATION" : "MESSAGE");
        detail.put("category", rule == null ? null : rule.path("category").asText(null));
        detail.put("veto", rule != null && rule.path("veto").asBoolean(false));
        detail.put("status", status); detail.put("deduction", deduction);
        detail.put("score", result.getScore()); detail.put("riskLevel", result.getRiskLevel()); detail.put("reason", reason);
        return detail;
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("结果解释结构生成失败", exception); }
    }

    private record Match(boolean hit, String text, int start, int end) { }

    private JsonNode readSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return null;
        try { return objectMapper.readTree(snapshot); }
        catch (Exception exception) { throw new IllegalStateException("任务规则快照损坏", exception); }
    }

}
