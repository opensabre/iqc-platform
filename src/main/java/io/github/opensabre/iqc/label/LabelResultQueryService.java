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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final io.github.opensabre.iqc.result.dao.RuleInspectionResultMapper ruleResultMapper;
    private final LabelResultReviewService labelReviews;

    /** Machine and human columns are read from one database snapshot; the machine contract is never overwritten. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<LabelResultView> listByTask(String taskId) {
        InspectionTask task = taskMapper.selectById(taskId);
        if (task == null) throw IqcException.notFound("质检任务不存在: " + taskId);
        if (!dataScope.canView(task.getCreatedBy(), task.getOwnerGroupId())) {
            throw IqcException.accessDenied("无权查看该质检结果");
        }
        SnapshotCatalog snapshotLabels = snapshotLabels(task);
        List<ConversationInspectionResult> conversations = conversationResultMapper.selectLatestForTask(taskId);
        if (conversations.isEmpty()) return List.of();
        Map<String, ConversationInspectionResult> byId = conversations.stream()
                .collect(Collectors.toMap(ConversationInspectionResult::getId, Function.identity()));
        List<InspectionLabelResult> results = labelResultMapper.selectList(
                Wrappers.<InspectionLabelResult>lambdaQuery()
                        .in(InspectionLabelResult::getConversationResultId, byId.keySet())
                        .orderByAsc(InspectionLabelResult::getCreatedTime));
        if (results.isEmpty()) return List.of();
        Map<String, LabelResultReviewService.ReviewOverlay> reviewed = labelReviews.effectiveForTask(task, byId, results);
        // Joint results are self-contained: mutable directory availability must not gate frozen reads.
        Map<String, InsightLabel> labels = snapshotLabels.strict() ? Map.of() : labelMapper.selectBatchIds(results.stream()
                        .map(InspectionLabelResult::getLabelId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(InsightLabel::getId, Function.identity()));
        Map<String, io.github.opensabre.iqc.label.model.LabelGroup> groups = snapshotLabels.strict() ? Map.of() : groupMapper.selectBatchIds(labels.values().stream()
                        .map(InsightLabel::getGroupId).distinct().toList()).stream()
                .collect(Collectors.toMap(io.github.opensabre.iqc.label.model.LabelGroup::getId, Function.identity()));
        Map<String, io.github.opensabre.iqc.label.model.LabelCategory> categories = snapshotLabels.strict() ? Map.of() : categoryMapper.selectBatchIds(groups.values().stream()
                        .map(io.github.opensabre.iqc.label.model.LabelGroup::getCategoryId).distinct().toList()).stream()
                .collect(Collectors.toMap(io.github.opensabre.iqc.label.model.LabelCategory::getId, Function.identity()));
        Map<String, String> fileNames = conversationMapper.selectBatchIds(conversations.stream().map(ConversationInspectionResult::getConversationId).distinct().toList())
                .stream().collect(Collectors.toMap(io.github.opensabre.iqc.conversation.model.Conversation::getId,
                        io.github.opensabre.iqc.conversation.model.Conversation::getSourceFileName, (a, b) -> a));
        return new java.util.ArrayList<>(results.stream().map(result -> {
            ConversationInspectionResult conversation = byId.get(result.getConversationResultId());
            InsightLabel label = labels.get(result.getLabelId());
            var group = label == null ? null : groups.get(label.getGroupId());
            var category = group == null ? null : categories.get(group.getCategoryId());
            SnapshotLabel snapshot = snapshotLabels.values().get(result.getLabelId() + ":" + result.getLabelVersionNo());
            if (snapshotLabels.strict() && snapshot == null)
                throw IqcException.invalidState("标签结果不属于任务冻结标签快照");
            String path = String.join(" / ", java.util.stream.Stream.of(
                    snapshotLabels.strict() ? snapshot.categoryName() : snapshot != null && snapshot.categoryName() != null ? snapshot.categoryName() : category == null ? null : category.getName(),
                    snapshotLabels.strict() ? snapshot.groupName() : snapshot != null && snapshot.groupName() != null ? snapshot.groupName() : group == null ? null : group.getName(),
                    snapshot != null ? snapshot.name() : label == null ? null : label.getName()).filter(Objects::nonNull).toList());
            String status = resultStatus(result.getValueJson(), snapshotLabels.strict(), snapshot, result.getValueCode());
            String evidenceJson = evidenceJson(result);
            return new LabelResultView(result.getId(), conversation.getConversationId(), fileNames.get(conversation.getConversationId()), result.getLabelId(),
                    snapshot != null ? snapshot.name() : label == null ? result.getLabelId() : label.getName(),
                    snapshot != null ? snapshot.code() : label == null ? null : label.getCode(), path, result.getLabelVersionNo(), result.getValueCode(),
                    result.getValueJson(), result.getConfidence(), result.getGenerationSource(),
                    result.getSourceRuleResultId(), evidenceJson, status, reviewed.get(result.getId()));
        }).collect(Collectors.toMap(value -> value.conversationId() + ":" + value.labelId() + ":" + Objects.toString(value.valueCode(), ""),
                Function.identity(), (first, ignored) -> {
                    if (snapshotLabels.strict()) throw IqcException.invalidState("标签结果重复，不能确认联合试跑覆盖");
                    return first;
                }, java.util.LinkedHashMap::new)).values());
    }

    /** Legacy rows are hits; coverage rows must declare a recognized state and cannot silently count as hits. */
    private String resultStatus(String json) {
        return resultStatus(json, false, null, null);
    }

    private String resultStatus(String json, boolean strict, SnapshotLabel frozen, String valueCode) {
        if (json == null || json.isBlank()) {
            if (strict) throw IqcException.invalidState("联合任务标签结果协议无效");
            return "HIT";
        }
        try {
            var payload = objectMapper.readTree(json);
            if (payload == null || !"iqc-label-result-v2".equals(payload.path("schemaVersion").asText())) {
                if (strict) throw IqcException.invalidState("联合任务标签结果协议无效");
                return "HIT";
            }
            if (strict && (frozen == null || valueCode == null || !frozen.valueTypes().containsKey(valueCode)
                    || !valueCode.equals(payload.path("valueCode").asText())))
                throw IqcException.invalidState("联合任务标签结果与冻结值定义不一致");
            LabelResultPayloadValidator.validate(payload, strict ? frozen.valueTypes().get(valueCode) : null,
                    strict ? frozen.targetRole() : null, true);
            return payload.path("status").asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            if (strict) throw IqcException.invalidState("联合任务标签结果格式无效");
            throw new IllegalStateException("标签结果格式无效", exception);
        }
    }

    private boolean detected(String status) { return "HIT".equals(status) || "KNOWN".equals(status); }

    /** Batch-check all trial candidates against owned rule results and persisted quotes before publication. */
    public void requirePersistedCandidateEvidence(List<InspectionLabelResult> values) {
        Map<String, String> ownerBySource = new HashMap<>();
        Set<EvidenceQuote> expected = new HashSet<>();
        for (InspectionLabelResult value : values) {
            try {
                var payload = objectMapper.readTree(value.getValueJson());
                if (payload == null || !"iqc-label-result-v2".equals(payload.path("schemaVersion").asText()))
                    throw IqcException.invalidState("试跑标签结果协议无效");
                Set<EvidenceQuote> quotes;
                try { quotes = candidateQuotes(payload); }
                catch (IllegalStateException exception) { throw IqcException.invalidState(exception.getMessage()); }
                for (EvidenceQuote quote : quotes) {
                    String previous = ownerBySource.putIfAbsent(quote.sourceRuleResultId(), value.getConversationResultId());
                    if (previous != null && !previous.equals(value.getConversationResultId()))
                        throw IqcException.invalidState("标签候选来源跨越会话结果");
                    expected.add(quote);
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw IqcException.invalidState("试跑标签结果格式无效");
            }
        }
        if (expected.isEmpty()) return;
        var sourceIds = List.copyOf(ownerBySource.keySet());
        Set<EvidenceQuote> actual = new HashSet<>();
        for (int start = 0; start < sourceIds.size(); start += 500) {
            var batch = sourceIds.subList(start, Math.min(start + 500, sourceIds.size()));
            var owned = ruleResultMapper.selectList(Wrappers.<io.github.opensabre.iqc.result.model.RuleInspectionResult>lambdaQuery()
                    .in(io.github.opensabre.iqc.result.model.RuleInspectionResult::getId, batch));
            Set<String> found = new HashSet<>();
            for (var source : owned) {
                if (!found.add(source.getId()) || !Objects.equals(ownerBySource.get(source.getId()), source.getConversationResultId()))
                    throw IqcException.invalidState("标签候选来源不属于当前会话结果");
            }
            if (!found.equals(new HashSet<>(batch))) throw IqcException.invalidState("标签候选来源不属于当前会话结果");
            for (var row : evidenceMapper.selectList(Wrappers.<io.github.opensabre.iqc.result.model.InspectionEvidence>lambdaQuery()
                    .in(io.github.opensabre.iqc.result.model.InspectionEvidence::getRuleResultId, batch)))
                actual.add(new EvidenceQuote(row.getRuleResultId(), row.getMessageId(), row.getMatchedText()));
        }
        if (!actual.containsAll(expected)) throw IqcException.invalidState("标签候选引文缺少已持久化证据");
    }

    /** Resolve every V2 candidate source within the same canonical result; never trust payload IDs as query authority. */
    private String evidenceJson(InspectionLabelResult result) {
        try {
            var payload = result.getValueJson() == null || result.getValueJson().isBlank()
                    ? objectMapper.createObjectNode() : objectMapper.readTree(result.getValueJson());
            var sources = new java.util.LinkedHashSet<String>();
            boolean coverage = "iqc-label-result-v2".equals(payload.path("schemaVersion").asText());
            var expectedQuotes = new java.util.LinkedHashSet<EvidenceQuote>();
            if (coverage) {
                expectedQuotes.addAll(candidateQuotes(payload));
                expectedQuotes.forEach(quote -> sources.add(quote.sourceRuleResultId()));
                if (sources.isEmpty()) return "[]";
                var ownedSources = ruleResultMapper.selectList(Wrappers.<io.github.opensabre.iqc.result.model.RuleInspectionResult>lambdaQuery()
                        .eq(io.github.opensabre.iqc.result.model.RuleInspectionResult::getConversationResultId, result.getConversationResultId())
                        .in(io.github.opensabre.iqc.result.model.RuleInspectionResult::getId, sources));
                var ownedIds = ownedSources.stream()
                        .filter(source -> Objects.equals(result.getConversationResultId(), source.getConversationResultId()))
                        .map(io.github.opensabre.iqc.result.model.RuleInspectionResult::getId).collect(Collectors.toSet());
                if (!ownedIds.equals(sources)) throw new IllegalStateException("标签候选来源不属于当前会话结果");
            } else {
                sources.add(result.getSourceRuleResultId());
            }
            var evidence = evidenceMapper.selectList(Wrappers.<io.github.opensabre.iqc.result.model.InspectionEvidence>lambdaQuery()
                    .in(io.github.opensabre.iqc.result.model.InspectionEvidence::getRuleResultId, sources)
                    .orderByAsc(io.github.opensabre.iqc.result.model.InspectionEvidence::getSequenceNo)
                    .orderByAsc(io.github.opensabre.iqc.result.model.InspectionEvidence::getId));
            if (coverage) {
                evidence = evidence.stream().filter(row -> expectedQuotes.contains(new EvidenceQuote(
                        row.getRuleResultId(), row.getMessageId(), row.getMatchedText()))).toList();
                var actualQuotes = evidence.stream().map(row -> new EvidenceQuote(
                        row.getRuleResultId(), row.getMessageId(), row.getMatchedText())).collect(Collectors.toSet());
                if (!actualQuotes.containsAll(expectedQuotes)) throw new IllegalStateException("标签候选引文缺少已持久化证据");
            }
            return objectMapper.writeValueAsString(evidence);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("标签结果或证据格式无效", exception);
        }
    }

    private Set<EvidenceQuote> candidateQuotes(com.fasterxml.jackson.databind.JsonNode payload) {
        if (!payload.path("candidates").isArray()) throw new IllegalStateException("标签候选结构无效");
        Set<EvidenceQuote> quotes = new java.util.LinkedHashSet<>();
        for (var candidate : payload.path("candidates")) {
            if (!candidate.isObject() || !candidate.path("sourceRuleResultId").isTextual())
                throw new IllegalStateException("标签候选来源无效");
            String sourceId = candidate.path("sourceRuleResultId").asText("");
            if (sourceId.isBlank()) throw new IllegalStateException("标签候选来源无效");
            if (!candidate.path("evidence").isArray() || candidate.path("evidence").isEmpty())
                throw new IllegalStateException("标签候选引文无效");
            for (var quote : candidate.path("evidence")) {
                if (!quote.isObject() || !quote.path("messageId").isTextual() || !quote.path("text").isTextual())
                    throw new IllegalStateException("标签候选引文无效");
                String messageId = quote.path("messageId").asText("");
                String text = quote.path("text").asText("");
                if (messageId.isBlank() || text.isBlank()) throw new IllegalStateException("标签候选引文无效");
                quotes.add(new EvidenceQuote(sourceId, messageId, text));
            }
        }
        return quotes;
    }

    private record EvidenceQuote(String sourceRuleResultId, String messageId, String text) { }

    /** New joint results must use their frozen label identity, never mutable directory names or silent deduplication. */
    private SnapshotCatalog snapshotLabels(InspectionTask task) {
        String json = task.getLabelScopeSnapshotJson();
        boolean joint = hasJointLabels(task);
        if (json == null || json.isBlank()) {
            if (joint) throw IqcException.invalidState("联合任务缺少冻结标签快照");
            return new SnapshotCatalog(Map.of(), false);
        }
        try {
            var root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                if (joint) throw IqcException.invalidState("任务冻结标签快照无效");
                return new SnapshotCatalog(Map.of(), false);
            }
            boolean strict = "2.0".equals(root.path("schemaVersion").asText()) || joint;
            if (strict && (!"2.0".equals(root.path("schemaVersion").asText())
                    || !root.path("labels").isArray() || root.path("labels").isEmpty()))
                throw IqcException.invalidState("联合任务冻结标签快照无效");
            if (joint && !root.equals(objectMapper.readTree(task.getRuleSnapshotJson())
                    .path("schemeSnapshot").path("release").path("dependencies").path("labels")))
                throw IqcException.invalidState("联合任务标签范围与方案冻结依赖不一致");
            Map<String, SnapshotLabel> values = new java.util.HashMap<>();
            for (var node : root.path("labels")) {
                String id = node.path("id").asText();
                int version = node.path("versionNo").asInt(0);
                if (strict && (id.isBlank() || version < 1)) throw IqcException.invalidState("联合任务标签身份无效");
                Map<String, String> valueTypes = new java.util.HashMap<>();
                if (strict && (!List.of("user", "customer", "agent").contains(node.path("targetRole").asText())
                        || !node.path("values").isArray() || node.path("values").isEmpty()))
                    throw IqcException.invalidState("联合任务冻结标签值定义无效");
                for (var definition : node.path("values")) {
                    String code = definition.path("valueCode").asText();
                    String type = definition.path("valueType").asText();
                    if (strict && (code.isBlank()
                            || !List.of("FIXED", "BOOLEAN", "PERCENTAGE", "DURATION_MONTHS", "MONTH", "DATE").contains(type)
                            || valueTypes.putIfAbsent(code, type) != null))
                        throw IqcException.invalidState("联合任务冻结标签值定义无效");
                    if (!strict && !code.isBlank()) valueTypes.put(code, type);
                }
                var value = new SnapshotLabel(node.path("name").asText(node.path("id").asText()), node.path("code").asText(null),
                        node.path("categoryName").asText(null), node.path("groupName").asText(null),
                        node.path("targetRole").asText(null), Map.copyOf(valueTypes));
                String key = id + ":" + (strict ? version : node.path("versionNo").asInt(1));
                if (strict && values.putIfAbsent(key, value) != null)
                    throw IqcException.invalidState("联合任务冻结标签定义重复");
                if (!strict) values.put(key, value);
            }
            return new SnapshotCatalog(values, strict);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            if (joint) throw IqcException.invalidState("任务冻结标签快照损坏");
            return new SnapshotCatalog(Map.of(), false); // Historical label tasks retain their tolerant read path.
        }
    }

    private boolean hasJointLabels(InspectionTask task) {
        if (task.getRuleSnapshotJson() == null || task.getRuleSnapshotJson().isBlank()) return false;
        try {
            var labels = objectMapper.readTree(task.getRuleSnapshotJson())
                    .path("schemeSnapshot").path("release").path("definition").path("labels");
            return labels.isArray() && !labels.isEmpty();
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            if (task.getRuleSnapshotJson().contains("\"schemeSnapshot\""))
                throw IqcException.invalidState("任务规则快照损坏");
            return false; // Legacy tasks without a scheme marker keep their historical query behavior.
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LabelInsightSummary summary(String taskId) {
        List<LabelResultView> allValues = listByTask(taskId);
        CoverageSummary coverage = coverageSummary(allValues.stream().map(LabelResultView::status).toList());
        List<LabelResultView> values = allValues.stream().filter(value -> detected(value.status())).toList();
        long conversationCount = conversationResultMapper.selectLatestForTask(taskId).stream()
                .map(ConversationInspectionResult::getConversationId).filter(Objects::nonNull).distinct().count();
        ReviewSummary reviewed = reviewSummary(allValues, conversationCount);
        if (values.isEmpty()) return new LabelInsightSummary(conversationCount, 0, BigDecimal.ZERO, Map.of(), Map.of(),
                coverage.valueCount(), coverage.statusCounts(), reviewed);
        Map<String, Long> labelDistribution = values.stream().collect(Collectors.groupingBy(LabelResultView::labelName, Collectors.counting()));
        SnapshotCatalog snapshot = snapshotLabels(taskMapper.selectById(taskId));
        Map<String, Long> groupDistribution;
        if (snapshot.strict()) {
            groupDistribution = values.stream().collect(Collectors.groupingBy(value -> {
                SnapshotLabel frozen = snapshot.values().get(value.labelId() + ":" + value.labelVersionNo());
                return frozen == null || frozen.groupName() == null ? "未知标签组" : frozen.groupName();
            }, Collectors.counting()));
        } else {
            Map<String, String> groupNames = groupMapper.selectBatchIds(labelMapper.selectBatchIds(values.stream()
                            .map(LabelResultView::labelId).distinct().toList()).stream().map(InsightLabel::getGroupId).distinct().toList())
                    .stream().collect(Collectors.toMap(io.github.opensabre.iqc.label.model.LabelGroup::getId,
                            io.github.opensabre.iqc.label.model.LabelGroup::getName));
            groupDistribution = values.stream().collect(Collectors.groupingBy(value -> {
                InsightLabel label = labelMapper.selectById(value.labelId());
                return label == null ? "未知标签组" : groupNames.getOrDefault(label.getGroupId(), "未知标签组");
            }, Collectors.counting()));
        }
        long detectedConversations = values.stream().map(LabelResultView::conversationId).distinct().count();
        BigDecimal detectionRate = conversationCount == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(detectedConversations).divide(BigDecimal.valueOf(conversationCount), 4, java.math.RoundingMode.HALF_UP);
        return new LabelInsightSummary(conversationCount, detectedConversations, detectionRate, labelDistribution, groupDistribution,
                coverage.valueCount(), coverage.statusCounts(), reviewed);
    }

    /** Human-effective rates are additive; legacy machine rates and distributions keep their original meaning. */
    private ReviewSummary reviewSummary(List<LabelResultView> values, long conversationCount) {
        long corrected = values.stream().filter(value -> value.reviewOverlay() != null
                && value.reviewOverlay().effectiveReviewId() != null).count();
        long pending = values.stream().filter(value -> value.reviewOverlay() != null
                && "PENDING".equals(value.reviewOverlay().latestStatus())).count();
        List<String> statuses = values.stream().map(value -> value.reviewOverlay() != null
                && value.reviewOverlay().effectiveStatus() != null ? value.reviewOverlay().effectiveStatus() : value.status()).toList();
        CoverageSummary coverage = coverageSummary(statuses);
        long detected = java.util.stream.IntStream.range(0, values.size())
                .filter(index -> detected(statuses.get(index))).mapToObj(index -> values.get(index).conversationId()).distinct().count();
        BigDecimal rate = conversationCount == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(detected).divide(BigDecimal.valueOf(conversationCount), 4, java.math.RoundingMode.HALF_UP);
        return new ReviewSummary(corrected, pending, detected, rate, coverage.valueCount(), coverage.statusCounts());
    }

    public LabelInsightSummary summaryByTasks(List<String> taskIds, java.util.Date from, java.util.Date to) {
        if (taskIds == null || taskIds.isEmpty()) return new LabelInsightSummary(0, 0, BigDecimal.ZERO, Map.of(), Map.of());
        Map<String, SnapshotCatalog> snapshots = taskMapper.selectBatchIds(taskIds).stream()
                .collect(Collectors.toMap(InspectionTask::getId, task -> snapshotLabels(task)));
        if (snapshots.size() != taskIds.stream().distinct().count())
            throw IqcException.invalidState("标签汇总中的任务不存在，无法核对冻结快照");
        List<ConversationInspectionResult> conversations = conversationResultMapper.selectLatestInWindow(taskIds, from, to);
        if (conversations.isEmpty()) return new LabelInsightSummary(0, 0, BigDecimal.ZERO, Map.of(), Map.of());
        // A conversation inspected by two tasks is two evaluations, not one customer in this dashboard rate.
        long conversationCount = conversations.size();
        Map<String, ConversationInspectionResult> byId = conversations.stream()
                .collect(Collectors.toMap(ConversationInspectionResult::getId, Function.identity()));
        var seenJointResults = new java.util.HashSet<LabelResultKey>();
        List<InspectionLabelResult> results = new java.util.ArrayList<>();
        List<String> statuses = new java.util.ArrayList<>();
        for (InspectionLabelResult result : labelResultMapper.selectList(Wrappers.<InspectionLabelResult>lambdaQuery()
                .in(InspectionLabelResult::getConversationResultId, byId.keySet()))) {
            ConversationInspectionResult conversation = byId.get(result.getConversationResultId());
            SnapshotCatalog snapshot = snapshots.get(conversation.getTaskId());
            if (snapshot != null && snapshot.strict()) {
                requireSnapshotLabel(snapshot, result);
                var key = new LabelResultKey(conversation.getTaskId(), conversation.getConversationId(),
                        result.getLabelId(), result.getValueCode());
                if (!seenJointResults.add(key)) throw IqcException.invalidState("标签结果重复，不能确认联合试跑覆盖");
            }
            String status = resultStatus(result.getValueJson(), snapshot != null && snapshot.strict(),
                    snapshot == null ? null : snapshot.values().get(result.getLabelId() + ":" + result.getLabelVersionNo()),
                    result.getValueCode());
            statuses.add(status);
            if (detected(status)) results.add(result);
        }
        CoverageSummary coverage = coverageSummary(statuses);
        if (results.isEmpty()) return new LabelInsightSummary(conversationCount, 0, BigDecimal.ZERO, Map.of(), Map.of(),
                coverage.valueCount(), coverage.statusCounts());
        // Mutable names are needed only for legacy evaluations, including mixed dashboards.
        List<String> legacyLabelIds = results.stream().filter(result -> !snapshots.get(
                        byId.get(result.getConversationResultId()).getTaskId()).strict())
                .map(InspectionLabelResult::getLabelId).distinct().toList();
        Map<String, InsightLabel> labels = legacyLabelIds.isEmpty() ? Map.of() : labelMapper.selectBatchIds(legacyLabelIds)
                .stream().collect(Collectors.toMap(InsightLabel::getId, Function.identity()));
        Map<String, io.github.opensabre.iqc.label.model.LabelGroup> groups = labels.isEmpty() ? Map.of() : groupMapper.selectBatchIds(labels.values().stream().map(InsightLabel::getGroupId).distinct().toList())
                .stream().collect(Collectors.toMap(io.github.opensabre.iqc.label.model.LabelGroup::getId, Function.identity()));
        Map<String, Long> labelDistribution = results.stream().collect(Collectors.groupingBy(result -> {
            SnapshotLabel frozen = snapshotLabel(snapshots, byId, result);
            InsightLabel label = labels.get(result.getLabelId());
            return frozen != null ? frozen.name() : label == null ? result.getLabelId() : label.getName();
        }, Collectors.counting()));
        Map<String, Long> groupDistribution = results.stream().collect(Collectors.groupingBy(result -> {
            SnapshotLabel frozen = snapshotLabel(snapshots, byId, result);
            SnapshotCatalog catalog = snapshots.get(byId.get(result.getConversationResultId()).getTaskId());
            if (catalog != null && catalog.strict()) return frozen.groupName() == null ? "未知标签组" : frozen.groupName();
            InsightLabel label = labels.get(result.getLabelId()); var group = label == null ? null : groups.get(label.getGroupId());
            return frozen != null && frozen.groupName() != null ? frozen.groupName() : group == null ? "未知标签组" : group.getName();
        }, Collectors.counting()));
        long detected = results.stream().map(InspectionLabelResult::getConversationResultId).map(byId::get).filter(Objects::nonNull)
                .map(value -> new TaskConversationKey(value.getTaskId(), value.getConversationId())).distinct().count();
        BigDecimal rate = conversationCount == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(detected).divide(BigDecimal.valueOf(conversationCount), 4, java.math.RoundingMode.HALF_UP);
        return new LabelInsightSummary(conversationCount, detected, rate, labelDistribution, groupDistribution,
                coverage.valueCount(), coverage.statusCounts());
    }

    /** Coverage rates use generated V2 label-value rows, never legacy HIT rows or expected-but-missing rows. */
    private CoverageSummary coverageSummary(List<String> statuses) {
        Map<String, Long> counts = statuses.stream().filter(status -> !"HIT".equals(status))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        long count = counts.values().stream().mapToLong(Long::longValue).sum();
        return new CoverageSummary(count, Map.copyOf(counts));
    }

    private SnapshotLabel snapshotLabel(Map<String, SnapshotCatalog> snapshots,
                                        Map<String, ConversationInspectionResult> conversations, InspectionLabelResult result) {
        SnapshotCatalog snapshot = snapshots.get(conversations.get(result.getConversationResultId()).getTaskId());
        return snapshot == null ? null : snapshot.values().get(result.getLabelId() + ":" + result.getLabelVersionNo());
    }

    private void requireSnapshotLabel(SnapshotCatalog snapshot, InspectionLabelResult result) {
        if (!snapshot.values().containsKey(result.getLabelId() + ":" + result.getLabelVersionNo()))
            throw IqcException.invalidState("标签结果不属于任务冻结标签快照");
    }

    public record LabelResultView(String id, String conversationId, String sourceFileName, String labelId, String labelName,
                                  String labelCode, String labelPath, Integer labelVersionNo, String valueCode, String valueJson,
                                  BigDecimal confidence, String generationSource, String sourceRuleResultId, String evidenceJson, String status,
                                  LabelResultReviewService.ReviewOverlay reviewOverlay) {
        /** Older callers construct machine-only test/export rows without a human overlay. */
        public LabelResultView(String id, String conversationId, String sourceFileName, String labelId, String labelName,
                               String labelCode, String labelPath, Integer labelVersionNo, String valueCode, String valueJson,
                               BigDecimal confidence, String generationSource, String sourceRuleResultId, String evidenceJson, String status) {
            this(id, conversationId, sourceFileName, labelId, labelName, labelCode, labelPath, labelVersionNo, valueCode,
                    valueJson, confidence, generationSource, sourceRuleResultId, evidenceJson, status, null);
        }
    }
    public record LabelInsightSummary(long conversationCount, long detectedConversationCount, BigDecimal detectionRate,
                                      Map<String, Long> labelDistribution, Map<String, Long> groupDistribution,
                                      long coverageValueCount, Map<String, Long> coverageStatusCounts, ReviewSummary reviewed) {
        public LabelInsightSummary(long conversationCount, long detectedConversationCount, BigDecimal detectionRate,
                                   Map<String, Long> labelDistribution, Map<String, Long> groupDistribution) {
            this(conversationCount, detectedConversationCount, detectionRate, labelDistribution, groupDistribution, 0, Map.of(), null);
        }
        public LabelInsightSummary(long conversationCount, long detectedConversationCount, BigDecimal detectionRate,
                                   Map<String, Long> labelDistribution, Map<String, Long> groupDistribution,
                                   long coverageValueCount, Map<String, Long> coverageStatusCounts) {
            this(conversationCount, detectedConversationCount, detectionRate, labelDistribution, groupDistribution,
                    coverageValueCount, coverageStatusCounts, null);
        }
    }
    public record ReviewSummary(long correctedValueCount, long pendingReviewCount, long detectedConversationCount,
                                BigDecimal detectionRate, long coverageValueCount, Map<String, Long> coverageStatusCounts) { }
    private record CoverageSummary(long valueCount, Map<String, Long> statusCounts) { }
    private record SnapshotLabel(String name, String code, String categoryName, String groupName,
                                 String targetRole, Map<String, String> valueTypes) { }
    private record SnapshotCatalog(Map<String, SnapshotLabel> values, boolean strict) { }
    private record LabelResultKey(String taskId, String conversationId, String labelId, String valueCode) { }
    private record TaskConversationKey(String taskId, String conversationId) { }
}
