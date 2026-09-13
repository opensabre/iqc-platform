package io.github.opensabre.iqc.label;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.dao.InsightLabelMapper;
import io.github.opensabre.iqc.label.dao.LabelGroupMapper;
import io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper;
import io.github.opensabre.iqc.label.model.InsightLabel;
import io.github.opensabre.iqc.label.model.InspectionLabelResult;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.dao.InspectionEvidenceMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.label.dao.LabelCategoryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read model for business label hits generated from canonical inspection results. */
@Service
@RequiredArgsConstructor
public class LabelResultQueryService {
    private final InspectionTaskMapper taskMapper;
    private final ConversationInspectionResultMapper conversationResultMapper;
    private final InspectionLabelResultMapper labelResultMapper;
    private final InsightLabelMapper labelMapper;
    private final LabelGroupMapper groupMapper;
    private final LabelCategoryMapper categoryMapper;
    private final ConversationMapper conversationMapper;
    private final InspectionEvidenceMapper evidenceMapper;
    private final ObjectMapper objectMapper;
    private final IqcDataScope dataScope;

    public List<LabelResultView> listByTask(String taskId) {
        InspectionTask task = taskMapper.selectById(taskId);
        if (task == null) throw IqcException.notFound("质检任务不存在: " + taskId);
        if (!dataScope.canView(task.getCreatedBy(), task.getOwnerGroupId())) {
            throw IqcException.accessDenied("无权查看该质检结果");
        }
        List<ConversationInspectionResult> conversations = conversationResultMapper.selectList(
                Wrappers.<ConversationInspectionResult>lambdaQuery()
                        .eq(ConversationInspectionResult::getTaskId, taskId));
        if (conversations.isEmpty()) return List.of();
        Map<String, ConversationInspectionResult> latestByConversation = conversations.stream().collect(Collectors.toMap(
                ConversationInspectionResult::getConversationId, Function.identity(), this::newerConversationResult));
        Map<String, ConversationInspectionResult> byId = latestByConversation.values().stream()
                .collect(Collectors.toMap(ConversationInspectionResult::getId, Function.identity()));
        List<InspectionLabelResult> results = labelResultMapper.selectList(
                Wrappers.<InspectionLabelResult>lambdaQuery()
                        .in(InspectionLabelResult::getConversationResultId, byId.keySet())
                        .orderByAsc(InspectionLabelResult::getCreatedTime));
        if (results.isEmpty()) return List.of();
        Map<String, InsightLabel> labels = labelMapper.selectBatchIds(results.stream()
                        .map(InspectionLabelResult::getLabelId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(InsightLabel::getId, Function.identity()));
        Map<String, io.github.opensabre.iqc.label.model.LabelGroup> groups = groupMapper.selectBatchIds(labels.values().stream()
                        .map(InsightLabel::getGroupId).distinct().toList()).stream()
                .collect(Collectors.toMap(io.github.opensabre.iqc.label.model.LabelGroup::getId, Function.identity()));
        Map<String, io.github.opensabre.iqc.label.model.LabelCategory> categories = categoryMapper.selectBatchIds(groups.values().stream()
                        .map(io.github.opensabre.iqc.label.model.LabelGroup::getCategoryId).distinct().toList()).stream()
                .collect(Collectors.toMap(io.github.opensabre.iqc.label.model.LabelCategory::getId, Function.identity()));
        Map<String, String> fileNames = conversationMapper.selectBatchIds(conversations.stream().map(ConversationInspectionResult::getConversationId).distinct().toList())
                .stream().collect(Collectors.toMap(io.github.opensabre.iqc.conversation.model.Conversation::getId,
                        io.github.opensabre.iqc.conversation.model.Conversation::getSourceFileName, (a, b) -> a));
        Map<String, SnapshotLabel> snapshotLabels = snapshotLabels(task.getLabelScopeSnapshotJson());
        return new java.util.ArrayList<>(results.stream().map(result -> {
            ConversationInspectionResult conversation = byId.get(result.getConversationResultId());
            InsightLabel label = labels.get(result.getLabelId());
            var group = label == null ? null : groups.get(label.getGroupId());
            var category = group == null ? null : categories.get(group.getCategoryId());
            SnapshotLabel snapshot = snapshotLabels.get(result.getLabelId() + ":" + result.getLabelVersionNo());
            String path = String.join(" / ", java.util.stream.Stream.of(
                    snapshot != null && snapshot.categoryName() != null ? snapshot.categoryName() : category == null ? null : category.getName(),
                    snapshot != null && snapshot.groupName() != null ? snapshot.groupName() : group == null ? null : group.getName(),
                    snapshot != null ? snapshot.name() : label == null ? null : label.getName()).filter(Objects::nonNull).toList());
            String evidenceJson;
            try { evidenceJson = objectMapper.writeValueAsString(evidenceMapper.selectList(Wrappers.<io.github.opensabre.iqc.result.model.InspectionEvidence>lambdaQuery()
                    .eq(io.github.opensabre.iqc.result.model.InspectionEvidence::getRuleResultId, result.getSourceRuleResultId()))); }
            catch (Exception exception) { evidenceJson = "[]"; }
            return new LabelResultView(result.getId(), conversation.getConversationId(), fileNames.get(conversation.getConversationId()), result.getLabelId(),
                    snapshot != null ? snapshot.name() : label == null ? result.getLabelId() : label.getName(),
                    snapshot != null ? snapshot.code() : label == null ? null : label.getCode(), path, result.getLabelVersionNo(), result.getValueCode(),
                    result.getValueJson(), result.getConfidence(), result.getGenerationSource(),
                    result.getSourceRuleResultId(), evidenceJson);
        }).collect(Collectors.toMap(value -> value.conversationId() + ":" + value.labelId() + ":" + Objects.toString(value.valueCode(), ""),
                Function.identity(), (first, ignored) -> first, java.util.LinkedHashMap::new)).values());
    }

    private Map<String, SnapshotLabel> snapshotLabels(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, SnapshotLabel> values = new java.util.HashMap<>();
            for (var node : objectMapper.readTree(json).path("labels")) {
                var value = new SnapshotLabel(node.path("name").asText(node.path("id").asText()), node.path("code").asText(null),
                        node.path("categoryName").asText(null), node.path("groupName").asText(null));
                values.put(node.path("id").asText() + ":" + node.path("versionNo").asInt(1), value);
            }
            return values;
        } catch (Exception ignored) { return Map.of(); }
    }

    private ConversationInspectionResult newerConversationResult(ConversationInspectionResult left, ConversationInspectionResult right) {
        if (left.getCreatedTime() == null) return right;
        if (right.getCreatedTime() == null) return left;
        int compared = left.getCreatedTime().compareTo(right.getCreatedTime());
        if (compared != 0) return compared > 0 ? left : right;
        return Objects.toString(left.getId(), "").compareTo(Objects.toString(right.getId(), "")) >= 0 ? left : right;
    }

    public LabelInsightSummary summary(String taskId) {
        List<LabelResultView> values = listByTask(taskId);
        long conversationCount = conversationResultMapper.selectList(Wrappers.<ConversationInspectionResult>lambdaQuery()
                        .eq(ConversationInspectionResult::getTaskId, taskId)).stream()
                .map(ConversationInspectionResult::getConversationId).filter(Objects::nonNull).distinct().count();
        if (values.isEmpty()) return new LabelInsightSummary(conversationCount, 0, BigDecimal.ZERO, Map.of(), Map.of());
        Map<String, Long> labelDistribution = values.stream().collect(Collectors.groupingBy(LabelResultView::labelName, Collectors.counting()));
        Map<String, String> groupNames = groupMapper.selectBatchIds(labelMapper.selectBatchIds(values.stream()
                        .map(LabelResultView::labelId).distinct().toList()).stream().map(InsightLabel::getGroupId).distinct().toList())
                .stream().collect(Collectors.toMap(io.github.opensabre.iqc.label.model.LabelGroup::getId,
                        io.github.opensabre.iqc.label.model.LabelGroup::getName));
        Map<String, Long> groupDistribution = values.stream().collect(Collectors.groupingBy(value -> {
            InsightLabel label = labelMapper.selectById(value.labelId());
            return label == null ? "未知标签组" : groupNames.getOrDefault(label.getGroupId(), "未知标签组");
        }, Collectors.counting()));
        long detectedConversations = values.stream().map(LabelResultView::conversationId).distinct().count();
        BigDecimal detectionRate = conversationCount == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(detectedConversations).divide(BigDecimal.valueOf(conversationCount), 4, java.math.RoundingMode.HALF_UP);
        return new LabelInsightSummary(conversationCount, detectedConversations, detectionRate, labelDistribution, groupDistribution);
    }

    public LabelInsightSummary summaryByTasks(List<String> taskIds, java.util.Date from, java.util.Date to) {
        if (taskIds == null || taskIds.isEmpty()) return new LabelInsightSummary(0, 0, BigDecimal.ZERO, Map.of(), Map.of());
        var conversationQuery = Wrappers.<ConversationInspectionResult>lambdaQuery().in(ConversationInspectionResult::getTaskId, taskIds);
        if (from != null) conversationQuery.ge(ConversationInspectionResult::getCreatedTime, from);
        if (to != null) conversationQuery.lt(ConversationInspectionResult::getCreatedTime, to);
        List<ConversationInspectionResult> conversations = conversationResultMapper.selectList(conversationQuery);
        long conversationCount = conversations.stream().map(ConversationInspectionResult::getConversationId).filter(Objects::nonNull).distinct().count();
        if (conversations.isEmpty()) return new LabelInsightSummary(0, 0, BigDecimal.ZERO, Map.of(), Map.of());
        Map<String, ConversationInspectionResult> latestByTaskConversation = conversations.stream().collect(Collectors.toMap(
                value -> value.getTaskId() + ":" + value.getConversationId(), Function.identity(), this::newerConversationResult));
        Map<String, ConversationInspectionResult> byId = latestByTaskConversation.values().stream()
                .collect(Collectors.toMap(ConversationInspectionResult::getId, Function.identity()));
        List<InspectionLabelResult> results = labelResultMapper.selectList(Wrappers.<InspectionLabelResult>lambdaQuery().in(InspectionLabelResult::getConversationResultId, byId.keySet()));
        if (results.isEmpty()) return new LabelInsightSummary(conversationCount, 0, BigDecimal.ZERO, Map.of(), Map.of());
        Map<String, InsightLabel> labels = labelMapper.selectBatchIds(results.stream().map(InspectionLabelResult::getLabelId).distinct().toList())
                .stream().collect(Collectors.toMap(InsightLabel::getId, Function.identity()));
        Map<String, io.github.opensabre.iqc.label.model.LabelGroup> groups = groupMapper.selectBatchIds(labels.values().stream().map(InsightLabel::getGroupId).distinct().toList())
                .stream().collect(Collectors.toMap(io.github.opensabre.iqc.label.model.LabelGroup::getId, Function.identity()));
        Map<String, Long> labelDistribution = results.stream().collect(Collectors.groupingBy(result -> {
            InsightLabel label = labels.get(result.getLabelId()); return label == null ? result.getLabelId() : label.getName();
        }, Collectors.counting()));
        Map<String, Long> groupDistribution = results.stream().collect(Collectors.groupingBy(result -> {
            InsightLabel label = labels.get(result.getLabelId()); var group = label == null ? null : groups.get(label.getGroupId()); return group == null ? "未知标签组" : group.getName();
        }, Collectors.counting()));
        long detected = results.stream().map(InspectionLabelResult::getConversationResultId).map(byId::get).filter(Objects::nonNull)
                .map(ConversationInspectionResult::getConversationId).filter(Objects::nonNull).distinct().count();
        BigDecimal rate = conversationCount == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(detected).divide(BigDecimal.valueOf(conversationCount), 4, java.math.RoundingMode.HALF_UP);
        return new LabelInsightSummary(conversationCount, detected, rate, labelDistribution, groupDistribution);
    }

    public record LabelResultView(String id, String conversationId, String sourceFileName, String labelId, String labelName,
                                  String labelCode, String labelPath, Integer labelVersionNo, String valueCode, String valueJson,
                                  BigDecimal confidence, String generationSource, String sourceRuleResultId, String evidenceJson) { }
    public record LabelInsightSummary(long conversationCount, long detectedConversationCount, BigDecimal detectionRate,
                                      Map<String, Long> labelDistribution, Map<String, Long> groupDistribution) { }
    private record SnapshotLabel(String name, String code, String categoryName, String groupName) { }
}
