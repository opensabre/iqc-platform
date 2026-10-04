package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.opensabre.iqc.shared.IqcPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.scheme.SchemeResultEvaluator;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.result.dao.InspectionResultMapper;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.model.TaskExecution;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/** Builds batch and conversation result views from authoritative task, conversation and result rows. */
@Service
public class BatchResultQueryService {
    private final InspectionTaskMapper taskMapper;
    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final InspectionResultMapper resultMapper;
    private final IqcDataScope dataScope;
    private final ObjectMapper objectMapper;
    private final ConversationInspectionResultMapper canonicalResults;
    private final TaskExecutionMapper executionMapper;

    public BatchResultQueryService(InspectionTaskMapper taskMapper, ConversationMapper conversationMapper,
                                   ConversationMessageMapper messageMapper, InspectionResultMapper resultMapper,
                                   IqcDataScope dataScope, ObjectMapper objectMapper, ConversationInspectionResultMapper canonicalResults,
                                   TaskExecutionMapper executionMapper) {
        this.taskMapper = taskMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.resultMapper = resultMapper;
        this.dataScope = dataScope;
        this.objectMapper = objectMapper;
        this.canonicalResults = canonicalResults;
        this.executionMapper = executionMapper;
    }

    /** Whole-task business export; message filters and legacy message scores are deliberately excluded. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String exportSchemeCsv(String taskId) {
        if (taskId == null || taskId.isBlank()) throw IqcException.invalidArgument("业务导出必须选择一个质检任务");
        InspectionTask task = requireTask(taskId);
        JsonNode snapshot = exportJson(task.getRuleSnapshotJson());
        var definition = SchemeResultEvaluator.definition(snapshot, objectMapper);
        if (definition == null) throw IqcException.invalidArgument("该任务不是业务方案任务，请使用消息观察导出");
        // This export has one row per check. A label-only scheme would otherwise return a misleading header-only file.
        if (definition.items().isEmpty())
            throw IqcException.invalidArgument("仅识别标签的任务没有业务质检项，请使用任务标签结果 XLSX 导出");
        List<String> ids = exportConversationIds(task, objectMapper);
        if ((long) ids.size() * definition.items().size() > 50_000)
            throw IqcException.invalidArgument("业务导出最多 50000 行，请缩小任务范围");
        JsonNode marker = snapshot.path("schemeSnapshot");
        StringBuilder csv = new StringBuilder("\uFEFF任务ID,方案ID,发布版本,草稿修订,快照哈希,任务类型,会话ID,执行实例,评分状态,会话最终分（勿按行求和）,评分政策版本,评分方式,质检项编码,质检项名称,判定,规则ID,规则版本,计分贡献（扣分制为扣分、得分制为得分）,一票否决,证据消息ID,证据原文\n");
        for (String id : ids) {
            ConversationInspectionResult canonical = latestCanonical(task, id);
            Map<String, JsonNode> items = new java.util.HashMap<>();
            Map<String, JsonNode> lines = new java.util.HashMap<>();
            if (canonical != null) {
                try { InspectionScoring.ScoreStatus.valueOf(canonical.getScoreStatus()); }
                catch (RuntimeException exception) { throw IqcException.invalidState("会话评分状态无效"); }
                JsonNode storedItems = exportJson(canonical.getBusinessItemResultsJson());
                if (!storedItems.isArray()) throw IqcException.invalidState("业务质检项结果损坏");
                for (JsonNode item : storedItems) {
                    String code = item.path("itemCode").asText();
                    if (items.putIfAbsent(code, item) != null) throw IqcException.invalidState("业务质检项重复");
                }
                if (items.size() != definition.items().size()) throw IqcException.invalidState("业务质检项结果不完整");
                JsonNode scoring = exportJson(canonical.getScoringResultJson());
                if (!scoring.path("lines").isArray()) throw IqcException.invalidState("评分明细损坏");
                for (JsonNode line : scoring.path("lines")) {
                    if (lines.putIfAbsent(line.path("itemCode").asText(), line) != null)
                        throw IqcException.invalidState("评分明细重复");
                }
                if (lines.size() != definition.scoring().items().size()) throw IqcException.invalidState("评分明细不完整");
                if (!scoring.path("scoreStatus").asText().equals(canonical.getScoreStatus())
                        || "FINAL".equals(canonical.getScoreStatus()) && (canonical.getFinalScore() == null
                        || !scoring.path("finalScore").isNumber()
                        || scoring.path("finalScore").decimalValue().compareTo(canonical.getFinalScore()) != 0))
                    throw IqcException.invalidState("会话评分与评分明细不一致");
            }
            Map<String, String> messages = new java.util.HashMap<>();
            if (canonical != null) messageMapper.selectList(Wrappers.<ConversationMessage>lambdaQuery()
                    .eq(ConversationMessage::getConversationId, id)).forEach(message -> messages.put(message.getId(), message.getContent()));
            for (var expected : definition.items()) {
                JsonNode item = items.get(expected.itemCode());
                if (canonical != null && (item == null || !expected.rule().id().equals(item.path("ruleId").asText())
                        || expected.rule().versionNo() != item.path("ruleVersionNo").asInt()))
                    throw IqcException.invalidState("业务质检项与任务快照不一致");
                String status = item == null ? "NOT_EVALUATED" : item.path("status").asText();
                try { InspectionScoring.ItemStatus.valueOf(status); }
                catch (IllegalArgumentException exception) { throw IqcException.invalidState("业务质检项状态无效"); }
                JsonNode line = lines.get(expected.itemCode());
                boolean scored = definition.scoring().items().stream().anyMatch(value -> value.itemCode().equals(expected.itemCode()));
                if (canonical != null && (scored != (line != null) || line != null && !status.equals(line.path("status").asText())))
                    throw IqcException.invalidState("业务质检项与评分明细不一致");
                List<String> evidenceIds = new ArrayList<>(), evidenceTexts = new ArrayList<>();
                if (item != null) {
                    if (!item.path("matchedMessageIds").isArray()) throw IqcException.invalidState("证据消息引用损坏");
                    for (JsonNode messageId : item.path("matchedMessageIds")) {
                        if (!messageId.isTextual() || !messages.containsKey(messageId.asText()))
                            throw IqcException.invalidState("证据消息不属于当前会话或已不存在");
                        evidenceIds.add(messageId.asText());
                        evidenceTexts.add(messageId.asText() + ": " + messages.get(messageId.asText()));
                    }
                }
                String[] cells = {taskId, marker.path("schemeId").asText(), marker.path("versionNo").asText(),
                        marker.path("draftRevision").asText(), marker.path("contentHash").asText(), marker.path("kind").asText(),
                        id, canonical == null ? "" : canonical.getExecutionId(), canonical == null ? "PENDING" : canonical.getScoreStatus(),
                        canonical != null && "FINAL".equals(canonical.getScoreStatus()) ? canonical.getFinalScore().toPlainString() : "",
                        definition.scoring().version(), definition.scoring().mode().name(), expected.itemCode(), expected.name(), status, expected.rule().id(),
                        Integer.toString(expected.rule().versionNo()), line == null ? "" : line.path("contribution").asText(""),
                        line == null ? "" : line.path("vetoTriggered").asText(), String.join(";", evidenceIds), String.join("\n", evidenceTexts)};
                csv.append(java.util.Arrays.stream(cells).map(InspectionExecutionService::row).collect(Collectors.joining(","))).append('\n');
            }
        }
        return csv.toString();
    }

    /** Strict, shared task scope for machine and review exports; corrupt JSON never falls back to another scope. */
    public static List<String> exportConversationIds(InspectionTask task, ObjectMapper mapper) {
        List<String> ids = new ArrayList<>();
        if (task.getConversationIdsJson() != null && !task.getConversationIdsJson().isBlank()) {
            try {
                JsonNode selected = mapper.readTree(task.getConversationIdsJson());
                if (selected == null || !selected.isArray()) throw IqcException.invalidState("任务会话范围损坏");
                for (JsonNode value : selected) {
                    if (!value.isTextual() || value.asText().isBlank()) throw IqcException.invalidState("任务会话范围损坏");
                    ids.add(value.asText());
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw IqcException.invalidState("任务会话范围损坏");
            }
        } else if (task.getConversationId() != null && !task.getConversationId().isBlank()) ids.add(task.getConversationId());
        if (ids.isEmpty()) throw IqcException.invalidState("任务缺少会话范围");
        return ids.stream().distinct().toList();
    }

    private JsonNode exportJson(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node == null) throw new IllegalArgumentException();
            return node;
        } catch (Exception exception) { throw IqcException.invalidState("业务导出数据损坏"); }
    }

    private ConversationInspectionResult latestCanonical(InspectionTask task, String conversationId) {
        return task.getCurrentExecutionId() == null ? null
                : canonicalResults.selectLatestForTaskConversation(task.getId(), conversationId);
    }

    /** Business rows paginate canonical task/conversation results, never a browser-grouped message page. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IqcPage<BusinessConversationRow> businessPage(long current, long size, String taskId, String scoreStatus,
                                                       String riskLevel, BigDecimal minScore, BigDecimal maxScore) {
        long pageNo = Math.max(1, current), pageSize = Math.min(Math.max(1, size), 100);
        if (scoreStatus != null && !List.of("FINAL", "PENDING", "NOT_APPLICABLE").contains(scoreStatus)
                || riskLevel != null && !List.of("HIGH", "MEDIUM", "LOW").contains(riskLevel)
                || minScore != null && minScore.signum() < 0 || maxScore != null && maxScore.signum() < 0
                || minScore != null && maxScore != null && minScore.compareTo(maxScore) > 0)
            throw IqcException.invalidArgument("业务结果筛选条件无效");
        var query = Wrappers.<InspectionTask>lambdaQuery().select(InspectionTask::getId, InspectionTask::getName);
        if (!dataScope.canViewAll()) {
            String group = dataScope.groupId();
            query.and(value -> value.eq(InspectionTask::getCreatedBy, dataScope.owner())
                    .or(group != null, nested -> nested.eq(InspectionTask::getOwnerGroupId, group)));
        }
        if (taskId != null && !taskId.isBlank()) query.eq(InspectionTask::getId, taskId);
        List<InspectionTask> visibleTasks = taskMapper.selectList(query);
        List<String> taskIds = visibleTasks.stream().map(InspectionTask::getId).toList();
        if (taskIds.isEmpty()) return new IqcPage<>(List.of(), pageNo, pageSize, 0);
        var page = canonicalResults.selectBusinessPage(new Page<>(pageNo, pageSize), taskIds,
                scoreStatus, riskLevel, minScore, maxScore);
        Map<String, String> names = new java.util.HashMap<>();
        Map<String, String> taskNames = new java.util.HashMap<>();
        visibleTasks.forEach(value -> taskNames.put(value.getId(), value.getName()));
        List<String> conversationIds = page.getRecords().stream().map(ConversationInspectionResult::getConversationId).distinct().toList();
        if (!conversationIds.isEmpty()) conversationMapper.selectBatchIds(conversationIds)
                .forEach(value -> names.put(value.getId(), value.getSourceFileName()));
        List<BusinessConversationRow> rows = page.getRecords().stream().map(value -> {
            int items = 0, failures = 0, errors = 0;
            try {
                InspectionScoring.ScoreStatus status = InspectionScoring.ScoreStatus.valueOf(value.getScoreStatus());
                if (status == InspectionScoring.ScoreStatus.FINAL && value.getFinalScore() == null)
                    throw new IllegalArgumentException();
                JsonNode outcomes = objectMapper.readTree(value.getBusinessItemResultsJson());
                if (outcomes == null || !outcomes.isArray()) throw new IllegalArgumentException();
                for (JsonNode item : outcomes) {
                    var state = InspectionScoring.ItemStatus.valueOf(item.path("status").asText());
                    items++;
                    if (state == InspectionScoring.ItemStatus.FAIL) failures++;
                    if (state == InspectionScoring.ItemStatus.ERROR) errors++;
                }
                return new BusinessConversationRow(value.getId(), value.getTaskId(), taskNames.get(value.getTaskId()), value.getConversationId(),
                        names.get(value.getConversationId()), value.getExecutionId(), value.getScoreStatus(),
                        status == InspectionScoring.ScoreStatus.FINAL ? value.getFinalScore() : null,
                        items, failures, errors, value.getRiskLevel());
            } catch (Exception exception) { throw IqcException.invalidState("业务会话结果损坏，不能推断评分或覆盖"); }
        }).toList();
        return new IqcPage<>(rows, page.getCurrent(), page.getSize(), page.getTotal());
    }

    public record BusinessConversationRow(String id, String taskId, String taskName, String conversationId, String sourceFileName,
                                           String executionId, String scoreStatus, BigDecimal finalScore,
                                           int itemCount, int failureCount, int errorCount, String riskLevel) { }

    /** Returns the batch-wide score and one summary row per selected conversation. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BatchResultSummary summary(String taskId) {
        InspectionTask task = requireTask(taskId);
        boolean scheme = isScheme(task);
        Map<String, Integer> attempts = scheme ? executionAttempts(taskId) : Map.of();
        List<InspectionResult> results = resultMapper.selectList(Wrappers.<InspectionResult>lambdaQuery()
                .eq(InspectionResult::getTaskId, taskId).orderByAsc(InspectionResult::getCreatedTime));
        Map<String, List<InspectionResult>> grouped = results.stream()
                .collect(Collectors.groupingBy(InspectionResult::getConversationId));
        List<String> conversationIds = selectedConversationIds(task, results);
        List<ConversationResultSummary> conversations = new ArrayList<>();
        for (String conversationId : conversationIds) {
            Conversation conversation = conversationMapper.selectById(conversationId);
            List<InspectionResult> conversationResults = grouped.getOrDefault(conversationId, List.of());
            conversations.add(conversationSummary(task, conversationId, conversation, conversationResults, attempts));
        }
        return new BatchResultSummary(taskId, task.getStatus(), conversations.size(), task.getTotalMessages(),
                task.getProcessedMessages(), task.getFailedMessages(), scheme ? averageFinalScores(conversations) : averageScore(results),
                scheme ? conversations.stream().mapToLong(ConversationResultSummary::hitCount).sum() : count(results, "HIT"),
                scheme ? conversations.stream().mapToLong(ConversationResultSummary::highRiskCount).sum() : countRisk(results, "HIGH"), conversations);
    }

    /** Returns all messages and their detailed findings for one conversation in the batch. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ConversationResultDetail conversationDetail(String taskId, String conversationId) {
        InspectionTask task = requireTask(taskId);
        List<String> selected = selectedConversationIds(task, List.of());
        if (!selected.contains(conversationId)) throw IqcException.invalidArgument("该会话不属于当前质检批次");
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) throw IqcException.notFound("会话不存在: " + conversationId);
        List<ConversationMessage> messages = messageMapper.selectList(Wrappers.<ConversationMessage>lambdaQuery()
                .eq(ConversationMessage::getConversationId, conversationId).orderByAsc(ConversationMessage::getSequenceNo));
        List<InspectionResult> results = resultMapper.selectList(Wrappers.<InspectionResult>lambdaQuery()
                .eq(InspectionResult::getTaskId, taskId).eq(InspectionResult::getConversationId, conversationId)
                .orderByAsc(InspectionResult::getCreatedTime));
        Map<String, Integer> attempts = isScheme(task) ? executionAttempts(taskId) : Map.of();
        List<InspectionResult> visibleResults = isScheme(task)
                ? observationsFor(latestCanonical(task, conversationId), results, attempts) : results;
        return new ConversationResultDetail(conversation, task, conversationSummary(task, conversationId, conversation, visibleResults, attempts), messages, visibleResults);
    }

    /** Returns all visible inspection results for one conversation across tasks. */
    public ConversationResultDetail conversationDetail(String conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) throw IqcException.notFound("会话不存在: " + conversationId);
        List<InspectionResult> allResults = resultMapper.selectList(Wrappers.<InspectionResult>lambdaQuery()
                .eq(InspectionResult::getConversationId, conversationId).orderByAsc(InspectionResult::getCreatedTime));
        List<InspectionResult> visibleResults = allResults.stream().filter(result -> {
            InspectionTask task = taskMapper.selectById(result.getTaskId());
            return task != null && dataScope.canView(task.getCreatedBy(), task.getOwnerGroupId());
        }).toList();
        if (visibleResults.isEmpty()) throw IqcException.notFound("会话质检结果不存在: " + conversationId);
        InspectionTask task = taskMapper.selectById(visibleResults.get(0).getTaskId());
        List<ConversationMessage> messages = messageMapper.selectList(Wrappers.<ConversationMessage>lambdaQuery()
                .eq(ConversationMessage::getConversationId, conversationId).orderByAsc(ConversationMessage::getSequenceNo));
        return new ConversationResultDetail(conversation, task, conversationSummary(task, conversationId, conversation,
                isScheme(task) ? visibleResults.stream().filter(result -> task.getId().equals(result.getTaskId())).toList() : visibleResults,
                isScheme(task) ? executionAttempts(task.getId()) : Map.of()), messages, visibleResults);
    }

    private InspectionTask requireTask(String taskId) {
        InspectionTask task = taskMapper.selectById(taskId);
        if (task == null) throw IqcException.notFound("质检任务不存在: " + taskId);
        if (!dataScope.canView(task.getCreatedBy(), task.getOwnerGroupId())) throw IqcException.accessDenied("无权查看该质检结果");
        return task;
    }

    private List<String> selectedConversationIds(InspectionTask task, List<InspectionResult> results) {
        if (task.getConversationIdsJson() != null && !task.getConversationIdsJson().isBlank()) {
            try {
                var node = objectMapper.readTree(task.getConversationIdsJson());
                if (node.isArray()) {
                    List<String> ids = new ArrayList<>();
                    node.forEach(item -> { if (item.isTextual() && !item.asText().isBlank()) ids.add(item.asText()); });
                    if (!ids.isEmpty()) return ids;
                }
            } catch (Exception ignored) { /* Legacy task fallback below. */ }
        }
        if (task.getConversationId() != null && !task.getConversationId().isBlank()) return List.of(task.getConversationId());
        return results.stream().map(InspectionResult::getConversationId).filter(java.util.Objects::nonNull).distinct().toList();
    }

    private ConversationResultSummary conversationSummary(InspectionTask task, String id, Conversation conversation,
                                                           List<InspectionResult> results, Map<String, Integer> attempts) {
        if (isScheme(task)) {
            // Completed conversations are not re-run when another conversation is retried; keep their latest canonical decision.
            ConversationInspectionResult canonical = latestCanonical(task, id);
            List<InspectionResult> currentResults = observationsFor(canonical, results, attempts);
            long failures = 0;
            if (canonical != null && canonical.getBusinessItemResultsJson() != null) {
                try {
                    for (var item : objectMapper.readTree(canonical.getBusinessItemResultsJson()))
                        if ("FAIL".equals(item.path("status").asText())) failures++;
                } catch (Exception exception) { throw IqcException.invalidState("业务质检项结果损坏"); }
            }
            return new ConversationResultSummary(id, conversation == null ? null : conversation.getSourceFileName(),
                    conversation == null ? 0 : conversation.getMessageCount(), currentResults.size(),
                    canonical == null || !"FINAL".equals(canonical.getScoreStatus()) ? null : canonical.getFinalScore(), failures,
                    canonical != null && "HIGH".equals(canonical.getRiskLevel()) ? 1 : 0,
                    currentResults.stream().filter(item -> item.getResultStatus() != null && item.getResultStatus().endsWith("ERROR")).count(),
                    canonical == null ? "PENDING" : canonical.getScoreStatus());
        }
        return new ConversationResultSummary(id, conversation == null ? null : conversation.getSourceFileName(),
                conversation == null ? 0 : conversation.getMessageCount(), results.size(), averageScore(results),
                count(results, "HIT"), countRisk(results, "HIGH"),
                results.stream().filter(item -> item.getResultStatus() != null && item.getResultStatus().endsWith("ERROR")).count(), null);
    }

    private Map<String, Integer> executionAttempts(String taskId) {
        return executionMapper.selectList(Wrappers.<TaskExecution>lambdaQuery()
                .eq(TaskExecution::getTaskId, taskId)).stream()
                .collect(Collectors.toMap(TaskExecution::getId,
                        value -> value.getAttemptNo() == null ? 0 : value.getAttemptNo()));
    }

    private List<InspectionResult> observationsFor(ConversationInspectionResult canonical, List<InspectionResult> results,
                                                    Map<String, Integer> attempts) {
        if (canonical == null) return List.of();
        // Older scheme results without an execution reference retain their historical readout.
        Integer currentAttempt = attempts.get(canonical.getExecutionId());
        if (currentAttempt == null) return results;
        Comparator<InspectionResult> recency = Comparator.comparingInt((InspectionResult value) ->
                attempts.getOrDefault(value.getExecutionId(), 0))
                .thenComparing(InspectionResult::getCreatedTime, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(InspectionResult::getId, Comparator.nullsFirst(Comparator.naturalOrder()));
        Map<String, InspectionResult> latestByMessage = new LinkedHashMap<>();
        for (InspectionResult result : results) {
            if (result.getMessageId() == null || result.getExecutionId() != null && !attempts.containsKey(result.getExecutionId())
                    || attempts.getOrDefault(result.getExecutionId(), 0) > currentAttempt) continue;
            latestByMessage.merge(result.getMessageId(), result,
                    (previous, candidate) -> recency.compare(previous, candidate) < 0 ? candidate : previous);
        }
        return List.copyOf(latestByMessage.values());
    }

    private boolean isScheme(InspectionTask task) {
        if (task.getRuleSnapshotJson() == null) return false;
        try { return objectMapper.readTree(task.getRuleSnapshotJson()).has("schemeSnapshot"); }
        catch (Exception exception) { throw IqcException.invalidState("任务规则快照损坏"); }
    }

    private BigDecimal averageFinalScores(List<ConversationResultSummary> conversations) {
        var scores = conversations.stream().filter(value -> "FINAL".equals(value.scoreStatus()))
                .map(ConversationResultSummary::averageScore).filter(java.util.Objects::nonNull).toList();
        return scores.isEmpty() ? null : scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(scores.size()), 2, RoundingMode.HALF_UP);
    }

    private static long count(List<InspectionResult> values, String status) {
        return values.stream().filter(item -> status.equals(item.getResultStatus())).count();
    }

    private static long countRisk(List<InspectionResult> values, String risk) {
        return values.stream().filter(item -> risk.equalsIgnoreCase(item.getRiskLevel())).count();
    }

    private static BigDecimal averageScore(List<InspectionResult> values) {
        return BigDecimal.valueOf(values.stream().map(InspectionResult::getScore).filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue).average().orElse(0)).setScale(2, RoundingMode.HALF_UP);
    }

    public record BatchResultSummary(String taskId, String status, int conversationCount, int totalMessages,
                                     int processedMessages, int failedMessages, BigDecimal averageScore,
                                     long hitCount, long highRiskCount, List<ConversationResultSummary> conversations) { }
    public record ConversationResultSummary(String conversationId, String sourceFileName, int messageCount,
                                            int resultCount, BigDecimal averageScore, long hitCount,
                                            long highRiskCount, long errorCount, String scoreStatus) { }
    public record ConversationResultDetail(Conversation conversation, InspectionTask task, ConversationResultSummary summary,
                                           List<ConversationMessage> messages, List<InspectionResult> results) { }
}
