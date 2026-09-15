package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.result.dao.InspectionResultMapper;
import io.github.opensabre.iqc.result.model.InspectionResult;
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
            if (execution != null) { execution.setStatus("FAILED"); execution.setErrorMessage(exception.getMessage()); executionMapper.updateById(execution); }
            log.error("event=iqc_task_execution taskId={} executionId={} status=FAILED errorType={} elapsedMs={}",
                    taskId, executionId, exception.getClass().getSimpleName(), elapsedMillis(startedAt), exception);
        }
    }

    private long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    public InspectionTask run(String taskId, String executionId) {
        InspectionTask task = taskMapper.selectById(taskId);
        if (task == null) throw IqcException.notFound("质检任务不存在: " + taskId);
        if ("RUNNING".equals(task.getStatus()) || "SUCCEEDED".equals(task.getStatus())) return task;

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
        int processed = previouslyProcessed + (int) items.stream().filter(item -> List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(item.getStatus())).count();
        int failed = (int) items.stream().filter(item -> "FAILED".equals(item.getStatus())).count();
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
        List<InspectionResult> conversationResults = new ArrayList<>();
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
                InspectionResult result = evaluateWithRuns(task, message, ruleSnapshot, conversationMessages);
                result.setExecutionId(executionId); resultMapper.insert(result); item.setResultId(result.getId());
                maybeProposeCandidate(task, message, result);
                conversationResults.add(result);
                if (result.getResultStatus() != null && result.getResultStatus().endsWith("ERROR")) {
                    item.setStatus("FAILED"); item.setErrorMessage(result.getReason());
                    usageCounterRecorder.record(new UsageRecord(usageRecordId + ":failure", null, "iqc-platform", "INSPECTION_MESSAGE", item.getMessageId(), "QUALITY_CHECK", UsageOutcome.FAILURE));
                } else {
                    item.setStatus("SUCCEEDED");
                    usageCounterRecorder.record(new UsageRecord(usageRecordId + ":success", null, "iqc-platform", "INSPECTION_MESSAGE", item.getMessageId(), "QUALITY_CHECK", UsageOutcome.SUCCESS));
                }
            } catch (RuntimeException exception) {
                item.setStatus("FAILED"); item.setErrorMessage(exception.getMessage());
                usageCounterRecorder.record(new UsageRecord(usageRecordId + ":failure", null, "iqc-platform", "INSPECTION_MESSAGE", item.getMessageId(), "QUALITY_CHECK", UsageOutcome.FAILURE));
            }
            taskItemMapper.updateById(item);
        }
        if (!conversationMessages.isEmpty() && !conversationResults.isEmpty()) {
            String conversationId = conversationMessages.get(0).getConversationId();
            List<ConversationMessage> fullMessages = messageMapper.selectList(Wrappers.<ConversationMessage>lambdaQuery()
                    .eq(ConversationMessage::getConversationId, conversationId).orderByAsc(ConversationMessage::getSequenceNo));
            if (fullMessages == null || fullMessages.isEmpty()) fullMessages = conversationMessages;
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

    private String row(String value) { return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\""; }

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
        int runs = Math.min(5, Math.max(1, task.getRunCount() == null ? 1 : task.getRunCount()));
        List<InspectionResult> decisions = new ArrayList<>(runs);
        for (int index = 0; index < runs; index++) decisions.add(evaluate(task, message, ruleSnapshot, conversationMessages));
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
        if (!Boolean.TRUE.equals(task.getAutoExpandEnabled()) || !"LLM_THEN_RULE".equals(qualityMode(readSnapshot(task.getAgentSnapshotJson())))
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

        JsonNode agentSnapshot = readSnapshot(task.getAgentSnapshotJson());
        String mode = qualityMode(agentSnapshot);
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
                    evaluated.add(evaluateSingle(task, message, agentReviewRule, preRuleFindings, conversationMessages));
                } else {
                    llmRules.forEach(rule -> evaluated.add(evaluateSingle(task, message, rule, preRuleFindings, conversationMessages)));
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
                llmRules.forEach(rule -> evaluated.add(evaluateSingle(task, message, rule, null, conversationMessages)));
            }
        } else {
            // Legacy tasks retain the historical independent rule execution semantics.
            rules.forEach(rule -> evaluated.add(evaluateSingle(task, message, rule, null, conversationMessages)));
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
        ArrayNode evidences = objectMapper.createArrayNode();
        ArrayNode suggestions = objectMapper.createArrayNode();
        ArrayNode breakdown = objectMapper.createArrayNode();
        evaluated.forEach(item -> { mergeJsonArray(findings, item.getFindingJson()); mergeJsonArray(evidences, item.getEvidenceJson()); mergeJsonArray(suggestions, item.getSuggestionJson()); });
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
        aggregate.setEvidence(aggregateHit ? message.getContent() : null);
        aggregate.setFindingJson(findings.toString()); aggregate.setEvidenceJson(evidences.toString()); aggregate.setSuggestionJson(suggestions.toString());
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

    private InspectionResult evaluateSingle(InspectionTask task, ConversationMessage message, JsonNode rule) {
        return evaluateSingle(task, message, rule, null, List.of(message));
    }

    private InspectionResult evaluateSingle(InspectionTask task, ConversationMessage message, JsonNode rule,
                                            JsonNode preRuleFindings) {
        return evaluateSingle(task, message, rule, preRuleFindings, List.of(message));
    }

    private InspectionResult evaluateSingle(InspectionTask task, ConversationMessage message, JsonNode rule,
                                            JsonNode preRuleFindings, List<ConversationMessage> conversationMessages) {
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
                result.setFindingJson(writeJson(List.of(finding(new Match(false, null, -1, -1), rule))));
                result.setEvidenceJson("[]"); result.setSuggestionJson("[]");
                return result;
            }
            String ruleType = rule.path("ruleType").asText();
            String expression = rule.path("expression").asText();
            if ("DLS".equalsIgnoreCase(ruleType)) {
                DlsEngine.Evaluation evaluation = DlsEngine.evaluate(compiledDls(expression), conversationMessages);
                DlsEngine.Hit anchor = evaluation.evidence().isEmpty() ? null : evaluation.evidence().get(0);
                boolean hit = evaluation.hit() && anchor != null && java.util.Objects.equals(anchor.messageId(), message.getId());
                Match match = hit ? new Match(true, anchor.text(), anchor.start(), anchor.end()) : new Match(false, null, -1, -1);
                int deduction = hit ? Math.max(0, Math.min(100, rule.path("deduction").asInt(10))) : 0;
                boolean veto = hit && rule.path("veto").asBoolean(false);
                result.setResultStatus(hit ? "HIT" : "NOT_HIT"); result.setScore(hit ? (veto ? 0 : 100 - deduction) : 100);
                result.setRiskLevel(hit ? rule.path("riskLevel").asText("MEDIUM") : "LOW"); result.setDeduction(deduction);
                result.setReason(evaluation.reason());
                result.setEvidence(hit ? evaluation.evidence().stream().map(DlsEngine.Hit::text).distinct()
                        .reduce((left, right) -> left + "；" + right).orElse(message.getContent()) : null);
                result.setFindingJson(writeJson(List.of(finding(match, rule))));
                result.setEvidenceJson(writeJson(hit ? evaluation.evidence() : List.of()));
                result.setSuggestionJson(writeJson(hit ? List.of(suggestion(rule)) : List.of()));
                result.setRuleBreakdownJson(writeJson(List.of(breakdownDetail(result, rule, result.getResultStatus(), deduction, result.getReason()))));
                return result;
            }
            if ("LLM".equalsIgnoreCase(ruleType)) {
                String recordId = "inspection-message:" + task.getId() + ":" + message.getId() + ":rule:" + rule.path("id").asText("unknown");
                LlmQualityProvider.LlmEvaluation evaluation = preRuleFindings == null
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
                result.setReason(evaluation.reason()); result.setEvidence(match.hit() ? message.getContent() : null);
                result.setFindingJson(evaluation.structuredJson() == null ? writeJson(List.of(finding(match, rule))) : evaluation.structuredJson()); result.setEvidenceJson("[]");
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
            result.setFindingJson(writeJson(List.of(finding(match, rule))));
            result.setEvidenceJson(writeJson(match.hit() ? List.of(evidence(message, match, rule)) : List.of()));
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

    private Map<String, Object> evidence(ConversationMessage message, Match match, JsonNode rule) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("ruleId", rule.path("id").asText(null)); evidence.put("messageId", message.getId()); evidence.put("sequenceNo", message.getSequenceNo()); evidence.put("text", match.text());
        evidence.put("start", match.start()); evidence.put("end", match.end());
        return evidence;
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
        detail.put("evaluationScope", "DLS".equalsIgnoreCase(ruleType) ? "CONVERSATION" : "MESSAGE");
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
