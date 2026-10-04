package io.github.opensabre.iqc.scheme;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper;
import io.github.opensabre.iqc.label.LabelResultQueryService;
import io.github.opensabre.iqc.label.model.InspectionLabelResult;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.scheme.model.InspectionSchemeVersion;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Publication gate over real persisted trial results; structural preview alone is never verification. */
@Service
@RequiredArgsConstructor
public class SchemePublicationService {
    private final InspectionSchemeService schemes;
    private final InspectionTaskService taskService;
    private final InspectionTaskMapper tasks;
    private final ConversationInspectionResultMapper results;
    private final InspectionLabelResultMapper labelResults;
    private final LabelResultQueryService labelQueries;
    private final IqcDataScope scope;
    private final ObjectMapper mapper;

    /** Recovers recent visible trials after a browser refresh without creating another trial store. */
    public List<InspectionTask> trials(String id) {
        schemes.get(id);
        var query = Wrappers.<InspectionTask>lambdaQuery()
                .apply("JSON_UNQUOTE(JSON_EXTRACT(rule_snapshot_json, '$.schemeSnapshot.kind')) = {0}", "DRAFT_TRIAL")
                .apply("JSON_UNQUOTE(JSON_EXTRACT(rule_snapshot_json, '$.schemeSnapshot.schemeId')) = {0}", id)
                .orderByDesc(InspectionTask::getCreatedTime).last("LIMIT 20");
        if (!scope.canViewAll()) {
            String owner = scope.owner(); String group = scope.groupId();
            query.and(q -> q.eq(InspectionTask::getCreatedBy, owner)
                    .or(group != null, g -> g.eq(InspectionTask::getOwnerGroupId, group)));
        }
        return tasks.selectList(query);
    }

    /** Creates a bounded draft trial; execution uses the existing task run/queue endpoint and its permission. */
    public InspectionTask trial(String id, int revision, List<String> conversationIds) {
        return trial(id, revision, conversationIds, null);
    }

    /** Reuses the same task on an uncertain network retry; a changed payload with the same key is rejected. */
    public InspectionTask trial(String id, int revision, List<String> conversationIds, String requestId) {
        return trial(id, revision, conversationIds, requestId, null);
    }

    /** Select an approved route alternative; retries bind to the existing task's immutable release, not today's draft. */
    public InspectionTask trial(String id, int revision, List<String> conversationIds, String requestId, String variantCode) {
        return trial(id, revision, conversationIds, requestId, variantCode, null, null);
    }

    /** Freeze optional multi-round adjudication settings in the idempotent expert trial request. */
    public InspectionTask trial(String id, int revision, List<String> conversationIds, String requestId, String variantCode,
                                Integer requestedRunCount, java.math.BigDecimal confidenceThreshold) {
        int runCount = requestedRunCount == null ? 1 : requestedRunCount;
        if (runCount < 1 || runCount > 5) throw IqcException.invalidArgument("裁决轮数必须在 1 到 5 之间");
        if (confidenceThreshold != null && (confidenceThreshold.compareTo(java.math.BigDecimal.ZERO) < 0
                || confidenceThreshold.compareTo(java.math.BigDecimal.ONE) > 0))
            throw IqcException.invalidArgument("一致率门槛必须在 0 到 1 之间");
        if (confidenceThreshold != null) confidenceThreshold = confidenceThreshold.stripTrailingZeros();
        if (requestId == null) {
            var release = previewTrial(id, revision, variantCode);
            return createTrial(id, revision, conversationIds, release, null, null, variantCode, runCount, confidenceThreshold);
        }
        if (!requestId.matches("[A-Za-z0-9_-]{16,64}")) throw IqcException.invalidArgument("试跑请求标识无效");
        String taskId = "tr-" + InspectionSchemeService.contentHash(scope.owner() + ":" + requestId).substring(0, 60);
        InspectionTask existing = tasks.selectById(taskId);
        if (existing != null) return repeatedTrial(existing, retryFingerprint(existing, id, revision, conversationIds,
                variantCode, runCount, confidenceThreshold));
        var release = previewTrial(id, revision, variantCode);
        String fingerprint = trialFingerprint(id, revision, conversationIds, release, variantCode, runCount, confidenceThreshold);
        try {
            return createTrial(id, revision, conversationIds, release, taskId, fingerprint, variantCode, runCount, confidenceThreshold);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            // The task creation transaction has rolled back before this read.
            existing = tasks.selectById(taskId);
            if (existing == null) throw exception;
            return repeatedTrial(existing, fingerprint);
        }
    }

    private InspectionTask repeatedTrial(InspectionTask task, String fingerprint) {
        if (!scope.canView(task.getCreatedBy(), task.getOwnerGroupId())) throw IqcException.accessDenied("无权访问已有试跑");
        JsonNode snapshot = read(task.getRuleSnapshotJson()).path("schemeSnapshot");
        if (!"DRAFT_TRIAL".equals(snapshot.path("kind").asText())
                || !fingerprint.equals(snapshot.path("requestFingerprint").asText()))
            throw IqcException.invalidArgument("同一试跑请求标识不能用于不同配置");
        return task;
    }

    private InspectionSchemeService.ReleaseSnapshot previewTrial(String id, int revision, String code) {
        return code == null ? schemes.preview(id, revision) : schemes.preview(id, revision, code);
    }

    private InspectionTask createTrial(String id, int revision, List<String> conversations,
                                       InspectionSchemeService.ReleaseSnapshot release, String taskId,
                                       String fingerprint, String code, int runCount,
                                       java.math.BigDecimal confidenceThreshold) {
        if (release.definition().executionVariants() == null) {
            if (code != null) throw IqcException.invalidArgument("模板未配置允许策略变体");
            return taskService.createSchemeTrial(id, revision, release.name(), conversations, release, taskId,
                    fingerprint, null, runCount, confidenceThreshold);
        }
        String selected = release.definition().executionVariants().select(code).code();
        return taskService.createSchemeTrial(id, revision, release.name(), conversations, release, taskId,
                fingerprint, selected, runCount, confidenceThreshold);
    }

    private String trialFingerprint(String id, int revision, List<String> conversations,
                                    InspectionSchemeService.ReleaseSnapshot release, String code,
                                    int runCount, java.math.BigDecimal confidenceThreshold) {
        String selected = null;
        String releaseHash = null;
        if (release != null && release.definition().executionVariants() != null) {
            selected = release.definition().executionVariants().select(code).code();
            var frozen = new InspectionSchemeService.ReleaseSnapshot(release.name(), release.code(), release.description(),
                    release.businessScene(), release.definition().forTaskSnapshot(), release.dependencies(), release.selectedVariantCode(), release.variantDependencies());
            releaseHash = hash(read(write(frozen)));
        } else if (code != null) throw IqcException.invalidArgument("模板未配置允许策略变体");
        return hash(new TrialFingerprint(id, revision, conversations, selected, releaseHash,
                runCount == 1 ? null : runCount, confidenceThreshold));
    }

    private String retryFingerprint(InspectionTask task, String id, int revision, List<String> conversations,
                                    String code, int runCount, java.math.BigDecimal confidenceThreshold) {
        if (!scope.canView(task.getCreatedBy(), task.getOwnerGroupId())) throw IqcException.accessDenied("无权访问已有试跑");
        var snapshot = read(task.getRuleSnapshotJson()).path("schemeSnapshot");
        if (!snapshot.path("release").path("definition").hasNonNull("executionVariants"))
            return trialFingerprint(id, revision, conversations, null, code, runCount, confidenceThreshold);
        if (!hash(snapshot.path("release")).equals(snapshot.path("contentHash").asText()))
            throw IqcException.invalidState("已有试跑快照校验失败");
        try {
            var release = mapper.treeToValue(snapshot.path("release"), InspectionSchemeService.ReleaseSnapshot.class);
            SchemeDependencyResolver.validateRouteTaskProjection(read(task.getRuleSnapshotJson()), task.getAgentSnapshotJson(), mapper);
            return trialFingerprint(id, revision, conversations, release, code, runCount, confidenceThreshold);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalidSnapshot) {
            throw IqcException.invalidState("已有试跑快照损坏");
        }
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw IqcException.invalidState("试跑快照无法序列化"); }
    }

    private record TrialFingerprint(String schemeId, int revision, List<String> conversationIds,
                                    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                    String variantCode,
                                    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                    String releaseHash,
                                    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                    Integer runCount,
                                    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                    java.math.BigDecimal confidenceThreshold) { }

    /** Requires an expert's acknowledgement and a completed, content-bound trial before publishing. */
    @Transactional
    public InspectionSchemeVersion publish(String id, int revision, String trialTaskId, boolean resultsReviewed) {
        if (!resultsReviewed) throw IqcException.invalidArgument("请先查看并确认试跑结果");
        var release = schemes.preview(id, revision);
        InspectionTask task = trialTaskId == null ? null : tasks.selectById(trialTaskId);
        if (task == null) throw IqcException.invalidArgument("请选择当前草稿的试跑任务");
        if (!scope.canView(task.getCreatedBy(), task.getOwnerGroupId())) throw IqcException.accessDenied("无权使用该试跑结果");
        if (!"SUCCEEDED".equals(task.getStatus()) || task.getTotalMessages() == null || task.getTotalMessages() < 1
                || task.getProcessedMessages() == null || task.getProcessedMessages() < task.getTotalMessages()
                || task.getFailedMessages() == null || task.getFailedMessages() != 0)
            throw IqcException.invalidState("试跑尚未完整成功执行，不能发布");
        JsonNode snapshot = read(task.getRuleSnapshotJson()).path("schemeSnapshot");
        if (!"DRAFT_TRIAL".equals(snapshot.path("kind").asText()) || !id.equals(snapshot.path("schemeId").asText())
                || revision != snapshot.path("draftRevision").asInt()
                || !hash(snapshot.path("release")).equals(snapshot.path("contentHash").asText())
                || !matchesTrialRelease(snapshot.path("release"), release))
            throw IqcException.invalidState("试跑不属于当前草稿或配置已变化，请重新试跑");
        Set<String> selected = new HashSet<>();
        JsonNode ids = read(task.getConversationIdsJson());
        if (!ids.isArray()) throw IqcException.invalidState("试跑数据范围损坏");
        ids.forEach(value -> { if (value.isTextual() && !value.asText().isBlank()) selected.add(value.asText()); });
        if (selected.isEmpty() || selected.size() > 20 || selected.size() != ids.size()) throw IqcException.invalidState("试跑数据范围无效");
        Map<String, ConversationInspectionResult> latest = new HashMap<>();
        for (String conversationId : selected) {
            var value = results.selectLatestForTaskConversation(trialTaskId, conversationId);
            if (value != null) latest.put(conversationId, value);
        }
        Set<String> expected = release.definition().items().stream().map(SchemeDefinition.Item::itemCode).collect(java.util.stream.Collectors.toSet());
        int applicable = 0;
        for (String conversationId : selected) {
            var result = latest.get(conversationId);
            if (result == null || !("FINAL".equals(result.getScoreStatus()) || "NOT_APPLICABLE".equals(result.getScoreStatus())))
                throw IqcException.invalidState("试跑缺少完整会话评分结果");
            JsonNode items = read(result.getBusinessItemResultsJson());
            if (!items.isArray() || items.size() != expected.size()) throw IqcException.invalidState("试跑质检项覆盖不完整");
            Set<String> covered = new HashSet<>();
            for (JsonNode item : items) {
                if (!expected.contains(item.path("itemCode").asText()) || !covered.add(item.path("itemCode").asText()))
                    throw IqcException.invalidState("试跑质检项与方案不一致");
                String status = item.path("status").asText();
                if (!Set.of("PASS", "FAIL", "NOT_APPLICABLE").contains(status))
                    throw IqcException.invalidState("试跑存在未评估、错误或待复核项目");
                if (!"NOT_APPLICABLE".equals(status)) applicable++;
            }
        }
        if (!expected.isEmpty() && applicable == 0)
            throw IqcException.invalidState("试跑样本没有适用质检项，请更换样本");
        validateLabelCoverage(release, task, selected, latest);
        return schemes.publishValidated(id, revision, trialTaskId, hash(release.forTaskSnapshot()));
    }

    /** A reviewed joint trial must contain one non-error result for every frozen label value in every conversation. */
    private void validateLabelCoverage(InspectionSchemeService.ReleaseSnapshot release, InspectionTask task,
                                       Set<String> selected, Map<String, ConversationInspectionResult> latest) {
        if (release.definition().labels() == null || release.definition().labels().isEmpty()) return;
        var frozen = release.dependencies() == null ? null : release.dependencies().labels();
        JsonNode frozenJson;
        try {
            // Serialize the typed decimal before reading; valueToTree can strip its scale to an integer node.
            frozenJson = frozen == null ? null : mapper.readTree(mapper.writeValueAsString(frozen));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalidLabels) {
            throw IqcException.invalidState("方案冻结标签无法序列化");
        }
        if (frozen == null || !"2.0".equals(frozen.schemaVersion()) || frozen.labels() == null || frozen.labels().isEmpty()
                || task.getLabelScopeSnapshotJson() == null
                || !frozenJson.equals(read(task.getLabelScopeSnapshotJson())))
            throw IqcException.invalidState("试跑标签快照与当前方案不一致，请重新试跑");
        Map<String, Integer> expectedVersions = new HashMap<>();
        Map<String, String> expectedTypes = new HashMap<>();
        Map<String, String> expectedRoles = new HashMap<>();
        for (var label : frozen.labels()) {
            if (label == null || label.id() == null || label.id().isBlank() || label.versionNo() == null
                    || label.versionNo() < 1 || label.targetRole() == null || label.targetRole().isBlank()
                    || label.values() == null || label.values().isEmpty())
                throw IqcException.invalidState("方案标签定义不完整");
            for (var value : label.values()) {
                if (value == null || value.getValueCode() == null || value.getValueCode().isBlank()
                        || expectedVersions.putIfAbsent(label.id() + "\u0000" + value.getValueCode(), label.versionNo()) != null)
                    throw IqcException.invalidState("方案标签值无效或重复");
                expectedTypes.put(label.id() + "\u0000" + value.getValueCode(), value.getValueType());
                expectedRoles.put(label.id() + "\u0000" + value.getValueCode(), label.targetRole());
            }
        }
        var resultIds = selected.stream().map(id -> latest.get(id).getId()).toList();
        if (resultIds.stream().anyMatch(id -> id == null || id.isBlank()))
            throw IqcException.invalidState("试跑标签结果缺少会话结果标识");
        Map<String, Set<String>> covered = new HashMap<>();
        var values = labelResults.selectList(Wrappers.<InspectionLabelResult>lambdaQuery()
                .in(InspectionLabelResult::getConversationResultId, resultIds));
        for (InspectionLabelResult value : values) {
            if (!resultIds.contains(value.getConversationResultId()))
                throw IqcException.invalidState("试跑标签结果归属无效");
            String key = value.getLabelId() + "\u0000" + value.getValueCode();
            if (!java.util.Objects.equals(expectedVersions.get(key), value.getLabelVersionNo())
                    || !covered.computeIfAbsent(value.getConversationResultId(), ignored -> new HashSet<>()).add(key))
                throw IqcException.invalidState("试跑标签结果与方案不一致");
            var payload = read(value.getValueJson());
            if (!"iqc-label-result-v2".equals(payload.path("schemaVersion").asText())
                    || !value.getValueCode().equals(payload.path("valueCode").asText())
                    || !Set.of("KNOWN", "UNKNOWN", "CONFLICT").contains(payload.path("status").asText()))
                throw IqcException.invalidState("试跑标签结果存在缺失或提取错误");
            validateLabelPayload(payload, expectedTypes.get(key), expectedRoles.get(key));
        }
        for (String id : resultIds) if (!expectedVersions.keySet().equals(covered.get(id)))
            throw IqcException.invalidState("试跑标签结果覆盖不完整");
        labelQueries.requirePersistedCandidateEvidence(values);
    }

    /** A status string alone cannot certify a usable value or explainable conflict at publication time. */
    private void validateLabelPayload(JsonNode payload, String expectedType, String expectedRole) {
        if (expectedType == null || expectedRole == null)
            throw IqcException.invalidState("试跑标签定义不完整");
        io.github.opensabre.iqc.label.LabelResultPayloadValidator.validate(payload, expectedType, expectedRole, false);
    }

    /** Normalize only supported task protocol markers; all frozen business and dependency fields still must match. */
    private boolean matchesTrialRelease(JsonNode actual, InspectionSchemeService.ReleaseSnapshot expected) {
        try {
            var frozen = mapper.treeToValue(actual, InspectionSchemeService.ReleaseSnapshot.class);
            if (frozen == null || frozen.definition() == null) return false;
            // Preserve every original field so unknown metadata cannot be silently discarded during comparison.
            JsonNode normalized = actual.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) normalized.path("definition"))
                    .put("schemaVersion", frozen.definition().forTaskSnapshot().schemaVersion());
            var target = new InspectionSchemeService.ReleaseSnapshot(expected.name(), expected.code(), expected.description(),
                    expected.businessScene(), expected.definition().forTaskSnapshot(), expected.dependencies(), expected.selectedVariantCode(), expected.variantDependencies());
            // Use the same JSON read path as the persisted task, including database decimal weights.
            return read(normalized.toString()).equals(read(mapper.writeValueAsString(target)));
        } catch (Exception invalidSnapshot) {
            return false;
        }
    }

    private String hash(Object value) {
        try { return InspectionSchemeService.contentHash(mapper.writeValueAsString(value)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidState("试跑快照无法校验"); }
    }

    private JsonNode read(String value) {
        try {
            JsonNode node = value == null ? null : mapper.readTree(value);
            if (node == null) throw IqcException.invalidState("试跑结果或快照缺失");
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidState("试跑结果或快照损坏"); }
    }
}
