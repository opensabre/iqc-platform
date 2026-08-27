package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.result.dao.InspectionResultMapper;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    public BatchResultQueryService(InspectionTaskMapper taskMapper, ConversationMapper conversationMapper,
                                   ConversationMessageMapper messageMapper, InspectionResultMapper resultMapper,
                                   IqcDataScope dataScope, ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.resultMapper = resultMapper;
        this.dataScope = dataScope;
        this.objectMapper = objectMapper;
    }

    /** Returns the batch-wide score and one summary row per selected conversation. */
    public BatchResultSummary summary(String taskId) {
        InspectionTask task = requireTask(taskId);
        List<InspectionResult> results = resultMapper.selectList(Wrappers.<InspectionResult>lambdaQuery()
                .eq(InspectionResult::getTaskId, taskId).orderByAsc(InspectionResult::getCreatedTime));
        Map<String, List<InspectionResult>> grouped = results.stream()
                .collect(Collectors.groupingBy(InspectionResult::getConversationId));
        List<String> conversationIds = selectedConversationIds(task, results);
        List<ConversationResultSummary> conversations = new ArrayList<>();
        for (String conversationId : conversationIds) {
            Conversation conversation = conversationMapper.selectById(conversationId);
            List<InspectionResult> conversationResults = grouped.getOrDefault(conversationId, List.of());
            conversations.add(conversationSummary(conversationId, conversation, conversationResults));
        }
        return new BatchResultSummary(taskId, task.getStatus(), conversations.size(), task.getTotalMessages(),
                task.getProcessedMessages(), task.getFailedMessages(), averageScore(results),
                count(results, "HIT"), countRisk(results, "HIGH"), conversations);
    }

    /** Returns all messages and their detailed findings for one conversation in the batch. */
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
        return new ConversationResultDetail(conversation, conversationSummary(conversationId, conversation, results), messages, results);
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

    private ConversationResultSummary conversationSummary(String id, Conversation conversation, List<InspectionResult> results) {
        return new ConversationResultSummary(id, conversation == null ? null : conversation.getSourceFileName(),
                conversation == null ? 0 : conversation.getMessageCount(), results.size(), averageScore(results),
                count(results, "HIT"), countRisk(results, "HIGH"),
                results.stream().filter(item -> item.getResultStatus() != null && item.getResultStatus().endsWith("ERROR")).count());
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
                                            long highRiskCount, long errorCount) { }
    public record ConversationResultDetail(Conversation conversation, ConversationResultSummary summary,
                                           List<ConversationMessage> messages, List<InspectionResult> results) { }
}
