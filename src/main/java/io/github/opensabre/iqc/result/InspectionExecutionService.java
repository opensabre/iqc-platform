package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.result.dao.InspectionResultMapper;
import io.github.opensabre.iqc.result.model.InspectionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import io.github.opensabre.iqc.rule.RuleMatcher;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class InspectionExecutionService {
    private final InspectionTaskMapper taskMapper;
    private final ConversationMessageMapper messageMapper;
    private final InspectionResultMapper resultMapper;
    private final ObjectMapper objectMapper;
    private final TaskExecutionMapper executionMapper;
    private final TaskItemMapper taskItemMapper;
    private final IqcDataScope dataScope;
    private final LlmQualityProvider llmQualityProvider;
    private final UsageCounterRecorder usageCounterRecorder;

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
                        if ("SUCCEEDED".equals(previousItem.getStatus())) successfulMessages++;
                        else retryMessageIds.add(previousItem.getMessageId());
                    }
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
        if ("CANCELLED".equals(task.getStatus())) { execution.setStatus("CANCELLED"); executionMapper.updateById(execution); return task; }
        task.setStatus("RUNNING"); taskMapper.updateById(task);
        execution.setStatus("RUNNING"); executionMapper.updateById(execution);
        JsonNode ruleSnapshot = readSnapshot(task.getRuleSnapshotJson());
        List<TaskItem> items = taskItemMapper.selectList(Wrappers.<TaskItem>lambdaQuery()
                .eq(TaskItem::getExecutionId, executionId).orderByAsc(TaskItem::getSequenceNo));
        int previouslyProcessed = task.getProcessedMessages() == null ? 0 : task.getProcessedMessages();
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
        if (task == null || "CANCELLED".equals(task.getStatus())) {
            execution.setStatus("CANCELLED"); executionMapper.updateById(execution); return task;
        }
        int processed = previouslyProcessed + (int) items.stream().filter(item -> List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(item.getStatus())).count();
        int failed = (int) items.stream().filter(item -> "FAILED".equals(item.getStatus())).count();
        task.setProcessedMessages(processed); task.setFailedMessages(failed);
        task.setStatus(failed > 0 ? "PARTIAL_FAILED" : "SUCCEEDED"); taskMapper.updateById(task);
        execution.setProcessedMessages(processed); execution.setFailedMessages(failed); execution.setStatus(failed > 0 ? "PARTIAL_FAILED" : "SUCCEEDED"); executionMapper.updateById(execution);
        return task;
    }

    private void processConversation(InspectionTask task, String executionId, JsonNode ruleSnapshot, List<TaskItem> items) {
        for (TaskItem item : items) {
            InspectionTask current = taskMapper.selectById(task.getId());
            if (current == null || "CANCELLED".equals(current.getStatus())) {
                item.setStatus("CANCELLED"); taskItemMapper.updateById(item); continue;
            }
            if ("SUCCEEDED".equals(item.getStatus()) || "CANCELLED".equals(item.getStatus())) continue;
            item.setStatus("RUNNING"); item.setAttemptCount((item.getAttemptCount() == null ? 0 : item.getAttemptCount()) + 1); taskItemMapper.updateById(item);
            String usageRecordId = "inspection-message:" + task.getId() + ":" + item.getMessageId();
            usageCounterRecorder.record(new UsageRecord(usageRecordId + ":attempt", null, "iqc-platform", "INSPECTION_MESSAGE", item.getMessageId(), "QUALITY_CHECK", UsageOutcome.ATTEMPT));
            try {
                ConversationMessage message = messageMapper.selectById(item.getMessageId());
                if (message == null) throw IqcException.notFound("会话消息不存在: " + item.getMessageId());
                InspectionResult result = evaluate(task, message, ruleSnapshot);
                result.setExecutionId(executionId); resultMapper.insert(result); item.setResultId(result.getId());
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
        return IqcPage.from(resultMapper.selectPage(new Page<>(Math.max(1, current), Math.min(Math.max(1, size), 100)), resultQuery.orderByAsc(InspectionResult::getCreatedTime)));
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
        if (rules.isEmpty()) return evaluateSingle(task, message, null);

        List<InspectionResult> evaluated = rules.stream().map(rule -> evaluateSingle(task, message, rule)).toList();
        InspectionResult aggregate = evaluated.get(0);
        boolean anyHit = evaluated.stream().anyMatch(item -> "HIT".equals(item.getResultStatus()));
        boolean allHit = evaluated.stream().allMatch(item -> "HIT".equals(item.getResultStatus()));
        boolean aggregateHit = "ALL".equals(aggregationMode) ? allHit : anyHit;
        boolean anyError = evaluated.stream().anyMatch(item -> item.getResultStatus() != null && item.getResultStatus().endsWith("ERROR"));
        int deduction = evaluated.stream().mapToInt(item -> item.getDeduction() == null ? 0 : item.getDeduction()).sum();
        boolean veto = rules.stream().anyMatch(rule -> rule.path("veto").asBoolean(false)
                && evaluated.stream().anyMatch(item -> rule.path("id").asText().equals(item.getRuleId()) && "HIT".equals(item.getResultStatus())));
        List<String> ruleIds = rules.stream().map(rule -> rule.path("id").asText(null)).filter(id -> id != null && !id.isBlank()).toList();
        ArrayNode findings = objectMapper.createArrayNode();
        ArrayNode evidences = objectMapper.createArrayNode();
        ArrayNode suggestions = objectMapper.createArrayNode();
        ArrayNode breakdown = objectMapper.createArrayNode();
        evaluated.forEach(item -> { mergeJsonArray(findings, item.getFindingJson()); mergeJsonArray(evidences, item.getEvidenceJson()); mergeJsonArray(suggestions, item.getSuggestionJson()); });
        for (int index = 0; index < evaluated.size(); index++) {
            InspectionResult item = evaluated.get(index);
            breakdown.add(objectMapper.valueToTree(breakdownDetail(item, rules.get(index), item.getResultStatus(),
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
            if ("LLM".equalsIgnoreCase(ruleType)) {
                LlmQualityProvider.LlmEvaluation evaluation = llmQualityProvider.evaluate(message.getContent(), rule,
                        readSnapshot(task.getAgentSnapshotJson()),
                        "inspection-message:" + task.getId() + ":" + message.getId() + ":rule:" + rule.path("id").asText("unknown"));
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
                result.setFindingJson(writeJson(List.of(finding(match, rule)))); result.setEvidenceJson("[]");
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
