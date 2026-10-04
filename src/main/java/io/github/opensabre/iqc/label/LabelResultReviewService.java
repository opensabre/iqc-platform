package io.github.opensabre.iqc.label;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper;
import io.github.opensabre.iqc.label.model.InspectionLabelResult;
import io.github.opensabre.iqc.quality.dao.ResultReviewMapper;
import io.github.opensabre.iqc.quality.model.ResultReview;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.scheme.InspectionSchemeService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Human label-value rounds reuse the review ledger without changing generated values or scores. */
@Service @RequiredArgsConstructor
public class LabelResultReviewService {
    private final ResultReviewMapper reviews;
    private final InspectionLabelResultMapper labels;
    private final ConversationInspectionResultMapper conversations;
    private final InspectionTaskMapper tasks;
    private final ConversationMessageMapper messages;
    private final IqcDataScope scope;
    private final ObjectMapper mapper;

    /** Lists label review rounds with task ownership applied in SQL before pagination. */
    @Transactional(readOnly = true)
    public io.github.opensabre.iqc.shared.IqcPage<io.github.opensabre.iqc.label.model.LabelReviewQueueItem> queue(
            String status, String taskId, int current, int size) {
        String selected = status == null ? "PENDING" : status;
        if (!Set.of("PENDING", "COMPLETED", "REJECTED", "ALL").contains(selected))
            throw IqcException.invalidArgument("标签复核状态无效");
        if (current < 1 || current > 100_000 || size < 1 || size > 100)
            throw IqcException.invalidArgument("复核分页范围无效，每页最多 100 条");
        if (taskId != null && (taskId.isBlank() || taskId.length() > 64))
            throw IqcException.invalidArgument("任务筛选无效");
        boolean all = scope.canViewAll();
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<io.github.opensabre.iqc.label.model.LabelReviewQueueItem>(current, size);
        var result = reviews.selectLabelQueue(page,
                "ALL".equals(selected) ? null : selected, taskId, all, scope.owner(), all ? null : scope.groupId());
        addFrozenNames(result.getRecords());
        return io.github.opensabre.iqc.shared.IqcPage.from(result);
    }

    /** Page-scoped task reads preserve historical names without requiring mutable label-catalog access. */
    private void addFrozenNames(List<io.github.opensabre.iqc.label.model.LabelReviewQueueItem> rows) {
        if (rows.isEmpty()) return;
        List<String> taskIds = rows.stream().map(io.github.opensabre.iqc.label.model.LabelReviewQueueItem::getTaskId)
                .filter(Objects::nonNull).distinct().toList();
        if (taskIds.isEmpty()) return;
        var frozenByTask = new HashMap<String, JsonNode>();
        for (InspectionTask task : tasks.selectBatchIds(taskIds)) {
            if (task.getLabelScopeSnapshotJson() == null) continue;
            try {
                JsonNode frozen = mapper.readTree(task.getLabelScopeSnapshotJson());
                if (frozen != null && "2.0".equals(frozen.path("schemaVersion").asText())
                        && frozen.path("labels").isArray()) frozenByTask.put(task.getId(), frozen);
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // A damaged display snapshot never guesses a name from today's mutable catalog.
            }
        }
        for (var row : rows) {
            JsonNode frozen = frozenByTask.get(row.getTaskId());
            if (frozen == null || row.getLabelVersionNo() == null) continue;
            for (JsonNode label : frozen.path("labels")) {
                if (!label.path("id").isTextual() || !Objects.equals(row.getLabelId(), label.path("id").asText())
                        || !label.path("versionNo").isIntegralNumber()
                        || row.getLabelVersionNo() != label.path("versionNo").intValue()) continue;
                if (label.path("name").isTextual() && !label.path("name").asText().isBlank())
                    row.setLabelName(label.path("name").asText());
                for (JsonNode value : label.path("values"))
                    if (value.path("valueCode").isTextual()
                            && Objects.equals(row.getValueCode(), value.path("valueCode").asText())
                            && value.path("description").isTextual()
                            && !value.path("description").asText().isBlank()) {
                        row.setValueDescription(value.path("description").asText());
                        break;
                    }
                break;
            }
        }
    }

    /** Opens the next round for a canonical V2 label value with an idempotent request token. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResultReview request(String labelResultId, int expectedRevision, String requestId, String reason) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}") || expectedRevision < 0)
            throw IqcException.invalidArgument("标签复核请求标识或预期修订无效");
        String comment = reason(reason);
        Context context = context(labelResultId, true);
        String id = "lr-" + hash(List.of(actor(), requestId)).substring(0, 60);
        String fingerprint = hash(List.of(labelResultId, expectedRevision, comment));
        ResultReview existing = reviews.selectById(id);
        if (existing != null) {
            if (!"LABEL".equals(existing.getTargetType()) || !fingerprint.equals(existing.getRequestFingerprint()))
                throw IqcException.invalidArgument("同一复核请求标识不能用于不同内容");
            return existing;
        }
        ResultReview last = latest(labelResultId);
        if (last != null && (last.getReviewRevision() == null || last.getReviewRevision() < 1))
            throw IqcException.invalidState("历史标签复核轮次损坏");
        int revision = last == null ? 0 : last.getReviewRevision();
        if (revision != expectedRevision) throw IqcException.invalidState("标签复核版本已变化，请刷新");
        if (last != null && "PENDING".equals(last.getStatus())) throw IqcException.invalidState("已有待处理标签复核");
        ResultReview review = new ResultReview();
        review.setId(id); review.setTargetType("LABEL"); review.setLabelResultId(labelResultId);
        review.setReviewRevision(revision + 1); review.setStatus("PENDING");
        review.setSourceHash(sourceHash(context)); review.setRequestFingerprint(fingerprint);
        review.setRequestComment(comment); review.setReviewComment(comment);
        review.setOriginalStatus(context.payload().path("status").asText());
        review.setOwnerGroupId(context.task().getOwnerGroupId()); review.setCreatedBy(actor());
        reviews.insert(review);
        return review;
    }

    /** Completes or rejects one pending round; a completed round stores only the human overlay. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResultReview decide(String reviewId, int expectedRevision, boolean rejected, Decision decision, String reason) {
        ResultReview initial = reviews.selectById(reviewId);
        if (initial == null || !"LABEL".equals(initial.getTargetType())) throw IqcException.notFound("标签复核不存在");
        Context context = context(initial.getLabelResultId(), true);
        ResultReview review = reviews.selectById(reviewId);
        if (review == null || !"LABEL".equals(review.getTargetType()) || !Objects.equals(initial.getLabelResultId(), review.getLabelResultId()))
            throw IqcException.invalidState("标签复核轮次已变化");
        String comment = reason(reason);
        if (expectedRevision < 1 || rejected == (decision != null))
            throw IqcException.invalidArgument("退回不得提交标签修订，完成必须提交标签修订");
        String fingerprint = hash(List.of(expectedRevision, rejected, decision == null ? "" : decision, comment, actor()));
        if (!Objects.equals(review.getReviewRevision(), expectedRevision)) throw IqcException.invalidState("标签复核版本已变化");
        if (!"PENDING".equals(review.getStatus())) {
            if (fingerprint.equals(review.getDecisionFingerprint())) return review;
            throw IqcException.invalidState("该轮标签复核已结束");
        }
        ResultReview latest = latest(review.getLabelResultId());
        if (!Objects.equals(review.getSourceHash(), sourceHash(context))
                || latest == null || !reviewId.equals(latest.getId()))
            throw IqcException.invalidState("标签原始结果或复核轮次已变化");
        if (!rejected) review.setReviewedResultJson(projection(context, decision));
        review.setStatus(rejected ? "REJECTED" : "COMPLETED");
        review.setDecisionFingerprint(fingerprint); review.setReviewerId(actor());
        review.setReviewedTime(LocalDateTime.now()); review.setReviewComment(comment);
        reviews.updateById(review);
        return review;
    }

    /** Returns all authorized rounds for one value, including obsolete executions for audit. */
    @Transactional(readOnly = true)
    public List<ResultReview> history(String labelResultId) {
        Context context = context(labelResultId, false);
        List<ResultReview> rows = reviews.selectList(Wrappers.<ResultReview>lambdaQuery().eq(ResultReview::getTargetType, "LABEL")
                .eq(ResultReview::getLabelResultId, labelResultId).orderByDesc(ResultReview::getReviewRevision));
        for (ResultReview row : rows) {
            if (!labelResultId.equals(row.getLabelResultId()) || !"LABEL".equals(row.getTargetType())
                    || row.getReviewRevision() == null || row.getReviewRevision() < 1
                    || !Objects.equals(row.getSourceHash(), sourceHash(context)))
                throw IqcException.invalidState("标签复核历史与原始结果不一致");
            if ("COMPLETED".equals(row.getStatus())) validateProjection(context, row);
            else if (!"PENDING".equals(row.getStatus()) && !"REJECTED".equals(row.getStatus()))
                throw IqcException.invalidState("标签复核历史状态无效");
        }
        return rows;
    }

    /** Resolves effective human overlays for canonical rows with bounded review/evidence reads. Machine values remain untouched. */
    @Transactional(readOnly = true)
    public java.util.Map<String, ReviewOverlay> effectiveForTask(InspectionTask task,
            java.util.Map<String, ConversationInspectionResult> conversationsById, List<InspectionLabelResult> values) {
        if (values.isEmpty()) return java.util.Map.of();
        var byId = new java.util.LinkedHashMap<String, InspectionLabelResult>();
        for (InspectionLabelResult value : values)
            if (value.getId() == null || byId.putIfAbsent(value.getId(), value) != null)
                throw IqcException.invalidState("标签结果身份缺失或重复");
        var groups = new HashMap<String, List<ResultReview>>();
        var ids = List.copyOf(byId.keySet());
        for (int start = 0; start < ids.size(); start += 500) {
            var batch = ids.subList(start, Math.min(start + 500, ids.size()));
            for (ResultReview row : reviews.selectList(Wrappers.<ResultReview>lambdaQuery()
                    .eq(ResultReview::getTargetType, "LABEL").in(ResultReview::getLabelResultId, batch))) {
                if (!"LABEL".equals(row.getTargetType()) || !byId.containsKey(row.getLabelResultId()))
                    throw IqcException.invalidState("标签复核结果超出任务范围");
                groups.computeIfAbsent(row.getLabelResultId(), ignored -> new ArrayList<>()).add(row);
            }
        }
        var overlays = new HashMap<String, ReviewOverlay>();
        var expectedConversationByMessage = new HashMap<String, String>();
        for (var entry : groups.entrySet()) {
            InspectionLabelResult value = byId.get(entry.getKey());
            Context context = frozenContext(task, conversationsById.get(value.getConversationResultId()), value);
            List<ResultReview> rounds = entry.getValue().stream()
                    .sorted(Comparator.comparing(ResultReview::getReviewRevision,
                            Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
            ResultReview effective = null;
            Projection projection = null;
            int revision = 0;
            for (ResultReview row : rounds) {
                if (row.getReviewRevision() == null || row.getReviewRevision() != ++revision
                        || !Objects.equals(row.getSourceHash(), sourceHash(context)))
                    throw IqcException.invalidState("标签复核历史轮次或基准不一致");
                if ("COMPLETED".equals(row.getStatus())) {
                    if (row.getReviewerId() == null || row.getReviewedTime() == null)
                        throw IqcException.invalidState("标签人工修订缺少裁决信息");
                    Projection checked = parseProjection(context, row);
                    for (String id : checked.evidenceMessageIds()) {
                        String previous = expectedConversationByMessage.putIfAbsent(id, context.conversation().getConversationId());
                        if (previous != null && !previous.equals(context.conversation().getConversationId()))
                            throw IqcException.invalidState("标签人工修订证据跨会话");
                    }
                    effective = row; projection = checked;
                } else if (!"PENDING".equals(row.getStatus()) && !"REJECTED".equals(row.getStatus())
                        || row.getReviewedResultJson() != null) {
                    throw IqcException.invalidState("标签复核历史状态或投影无效");
                }
                if ("PENDING".equals(row.getStatus()) && row != rounds.getLast())
                    throw IqcException.invalidState("标签复核待处理轮次不在最新位置");
            }
            ResultReview last = rounds.getLast();
            overlays.put(entry.getKey(), new ReviewOverlay(last.getReviewRevision(), last.getStatus(),
                    effective == null ? null : effective.getId(), effective == null ? null : effective.getReviewRevision(),
                    projection == null ? null : projection.status(), projection == null ? null : projection.value(),
                    projection == null ? List.of() : projection.evidenceMessageIds()));
        }
        var messageIds = List.copyOf(expectedConversationByMessage.keySet());
        var found = new HashSet<String>();
        for (int start = 0; start < messageIds.size(); start += 500) {
            var batch = messageIds.subList(start, Math.min(start + 500, messageIds.size()));
            for (ConversationMessage message : messages.selectBatchIds(batch)) {
                if (!found.add(message.getId()) || !Objects.equals(expectedConversationByMessage.get(message.getId()), message.getConversationId()))
                    throw IqcException.invalidState("标签人工修订证据不属于当前会话");
            }
        }
        if (found.size() != expectedConversationByMessage.size())
            throw IqcException.invalidState("标签人工修订证据缺失");
        return java.util.Map.copyOf(overlays);
    }

    private void validateProjection(Context context, ResultReview review) {
        Projection projection = parseProjection(context, review);
        List<String> ids = projection.evidenceMessageIds();
        if (!ids.isEmpty()) {
            Set<String> found = messages.selectList(Wrappers.<ConversationMessage>lambdaQuery()
                    .eq(ConversationMessage::getConversationId, context.conversation().getConversationId())
                    .in(ConversationMessage::getId, ids)).stream().map(ConversationMessage::getId)
                    .collect(java.util.stream.Collectors.toSet());
            if (!found.equals(new HashSet<>(ids))) throw IqcException.invalidState("标签人工修订证据已变化");
        }
    }

    private Projection parseProjection(Context context, ResultReview review) {
        try {
            Projection projection = mapper.readValue(review.getReviewedResultJson(), Projection.class);
            if (projection == null || !context.label().getId().equals(projection.sourceLabelResultId())
                    || !context.label().getLabelId().equals(projection.labelId())
                    || !Objects.equals(context.label().getLabelVersionNo(), projection.labelVersionNo())
                    || !context.label().getValueCode().equals(projection.valueCode())
                    || !context.valueType().equals(projection.valueType())
                    || (!"KNOWN".equals(projection.status()) && !"UNKNOWN".equals(projection.status())))
                throw IqcException.invalidState("标签人工修订快照无效");
            List<String> ids = projection.evidenceMessageIds();
            if (ids == null || ids.size() > 50 || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 64)
                    || new HashSet<>(ids).size() != ids.size())
                throw IqcException.invalidState("标签人工修订证据无效");
            if ("KNOWN".equals(projection.status())) {
                if (!LabelFactEvaluator.validValue(context.valueType(), projection.value()) || ids.isEmpty())
                    throw IqcException.invalidState("标签人工修订值无效");
            } else if (projection.value() != null || !ids.isEmpty()) {
                throw IqcException.invalidState("未知标签不得携带确定值或证据");
            }
            return projection;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException exception) {
            throw IqcException.invalidState("标签人工修订快照损坏");
        }
    }

    private Context context(String labelResultId, boolean requireCurrent) {
        InspectionLabelResult label = labels.selectById(labelResultId);
        if (label == null) throw IqcException.notFound("标签结果不存在");
        ConversationInspectionResult conversation = conversations.selectById(label.getConversationResultId());
        if (conversation == null) throw IqcException.invalidState("标签来源会话结果不存在");
        InspectionTask task = tasks.selectById(conversation.getTaskId());
        if (task == null || !scope.canView(task.getCreatedBy(), task.getOwnerGroupId()))
            throw IqcException.accessDenied("无权访问该标签复核");
        if (requireCurrent) {
            task = tasks.selectOne(Wrappers.<InspectionTask>lambdaQuery().eq(InspectionTask::getId, task.getId()).last("FOR UPDATE"));
            if (task == null || !scope.canView(task.getCreatedBy(), task.getOwnerGroupId()))
                throw IqcException.accessDenied("无权访问该标签复核");
            if (task.getStatus() == null || !Set.of("SUCCEEDED", "PARTIAL_FAILED", "FAILED", "CANCELLED").contains(task.getStatus()))
                throw IqcException.invalidState("任务尚未结束，暂不能复核标签");
            var current = conversations.selectLatestForTaskConversation(task.getId(), conversation.getConversationId());
            if (current == null || !conversation.getId().equals(current.getId()))
                throw IqcException.invalidState("该会话已有更新的质检结果");
            label = labels.selectById(labelResultId);
            if (label == null || !conversation.getId().equals(label.getConversationResultId()))
                throw IqcException.invalidState("标签原始结果已变化");
        }
        return frozenContext(task, conversation, label);
    }

    /** Validates a supplied canonical row against its immutable task snapshot without additional identity reads. */
    private Context frozenContext(InspectionTask task, ConversationInspectionResult conversation, InspectionLabelResult label) {
        if (task == null || conversation == null || label == null || !Objects.equals(task.getId(), conversation.getTaskId())
                || !Objects.equals(conversation.getId(), label.getConversationResultId()))
            throw IqcException.invalidState("标签结果与任务会话归属不一致");
        try {
            JsonNode frozen = mapper.readTree(task.getLabelScopeSnapshotJson());
            if (frozen == null || !"2.0".equals(frozen.path("schemaVersion").asText()))
                throw IqcException.invalidArgument("旧标签结果暂不支持值复核");
            JsonNode definition = null;
            if (label.getLabelId() == null || label.getValueCode() == null || !frozen.path("labels").isArray())
                throw IqcException.invalidState("标签结果身份或冻结定义无效");
            JsonNode ruleSnapshot = mapper.readTree(task.getRuleSnapshotJson());
            JsonNode released = ruleSnapshot == null ? null : ruleSnapshot.path("schemeSnapshot").path("release")
                    .path("dependencies").path("labels");
            if (released != null && released.isObject() && !frozen.equals(released))
                throw IqcException.invalidState("任务标签范围与发布方案冻结依赖不一致");
            for (JsonNode entry : frozen.path("labels"))
                if (label.getLabelId().equals(entry.path("id").asText())
                        && Objects.equals(label.getLabelVersionNo(), entry.path("versionNo").asInt())) {
                    if (definition != null) throw IqcException.invalidState("冻结标签定义重复");
                    definition = entry;
                }
            if (definition == null) throw IqcException.invalidState("标签不属于冻结定义");
            String valueType = null;
            for (JsonNode entry : definition.path("values"))
                if (label.getValueCode().equals(entry.path("valueCode").asText())) {
                    if (valueType != null) throw IqcException.invalidState("冻结标签值定义重复");
                    valueType = entry.path("valueType").asText();
                }
            if (valueType == null) throw IqcException.invalidState("标签值不属于冻结定义");
            JsonNode payload = mapper.readTree(label.getValueJson());
            if (payload == null || !label.getValueCode().equals(payload.path("valueCode").asText()))
                throw IqcException.invalidState("标签原始结果无效");
            LabelResultPayloadValidator.validate(payload, valueType, definition.path("targetRole").asText(), true);
            return new Context(task, conversation, label, payload, valueType);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw IqcException.invalidState("标签冻结快照或原始结果损坏");
        }
    }

    private String projection(Context context, Decision decision) {
        if (decision == null || (!"KNOWN".equals(decision.status()) && !"UNKNOWN".equals(decision.status())))
            throw IqcException.invalidArgument("人工标签状态只支持确定或未知");
        List<String> ids = decision.evidenceMessageIds() == null ? List.of() : decision.evidenceMessageIds();
        if (ids.size() > 50 || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 64)
                || new HashSet<>(ids).size() != ids.size()) throw IqcException.invalidArgument("标签证据消息无效或重复");
        if ("KNOWN".equals(decision.status())) {
            if (!LabelFactEvaluator.validValue(context.valueType(), decision.value()) || ids.isEmpty())
                throw IqcException.invalidArgument("确定标签值必须符合冻结类型并提供证据消息");
        } else if (decision.value() != null && !decision.value().isNull() || !ids.isEmpty()) {
            throw IqcException.invalidArgument("未知标签不得携带确定值或证据");
        }
        if (!ids.isEmpty()) {
            Set<String> allowed = messages.selectList(Wrappers.<ConversationMessage>lambdaQuery()
                    .eq(ConversationMessage::getConversationId, context.conversation().getConversationId())
                    .in(ConversationMessage::getId, ids)).stream().map(ConversationMessage::getId).collect(java.util.stream.Collectors.toSet());
            if (!allowed.equals(new HashSet<>(ids))) throw IqcException.invalidArgument("标签证据不属于当前会话");
        }
        try {
            return mapper.writeValueAsString(new Projection(context.label().getId(), context.label().getLabelId(),
                    context.label().getLabelVersionNo(), context.label().getValueCode(), context.valueType(),
                    decision.status(), "KNOWN".equals(decision.status()) ? decision.value() : null, ids));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw IqcException.invalidArgument("标签修订无法序列化");
        }
    }

    private ResultReview latest(String labelResultId) {
        return reviews.selectOne(Wrappers.<ResultReview>lambdaQuery().eq(ResultReview::getTargetType, "LABEL")
                .eq(ResultReview::getLabelResultId, labelResultId).orderByDesc(ResultReview::getReviewRevision).last("LIMIT 1"));
    }

    private String sourceHash(Context context) {
        return hash(java.util.Arrays.asList(context.task().getRuleSnapshotJson(), context.task().getLabelScopeSnapshotJson(),
                context.conversation().getId(), context.label().getId(), context.label().getLabelId(),
                context.label().getLabelVersionNo(), context.label().getValueCode(), context.label().getValueJson()));
    }
    private String hash(Object value) {
        try { return InspectionSchemeService.contentHash(mapper.writeValueAsString(value)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidState("标签复核数据无法序列化"); }
    }
    private String actor() {
        String owner = scope.owner();
        if (owner == null || owner.isBlank() || "system".equals(owner)) throw IqcException.accessDenied("标签复核必须由已登录用户操作");
        return owner;
    }
    private String reason(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 1000)
            throw IqcException.invalidArgument("标签复核原因不能为空且不得超过 1000 字");
        return value.trim();
    }
    private record Context(InspectionTask task, ConversationInspectionResult conversation, InspectionLabelResult label,
                           JsonNode payload, String valueType) { }
    public record Decision(String status, JsonNode value, List<String> evidenceMessageIds) { }
    public record Projection(String sourceLabelResultId, String labelId, Integer labelVersionNo, String valueCode,
                             String valueType, String status, JsonNode value, List<String> evidenceMessageIds) { }
    public record ReviewOverlay(int latestRevision, String latestStatus, String effectiveReviewId, Integer effectiveRevision,
                                String effectiveStatus, JsonNode effectiveValue, List<String> evidenceMessageIds) { }
}
