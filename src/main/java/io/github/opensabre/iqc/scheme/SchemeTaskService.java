package io.github.opensabre.iqc.scheme;

import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.governance.IqcException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.time.LocalDateTime;

/** Bridges authorized published schemes to the existing task lifecycle without consulting mutable detector drafts. */
@Service
@RequiredArgsConstructor
public class SchemeTaskService {
    private final InspectionSchemeService schemes;
    private final InspectionTaskService tasks;
    private final InspectionTaskMapper taskMapper;
    private final SchemeDependencyResolver dependencies;
    private final IqcDataScope scope;
    private final ObjectMapper mapper;

    /** Creates a batch using exactly the requested release and the caller's visible conversations. */
    public InspectionTask create(String schemeId, int versionNo, String name, List<String> conversationIds, Integer concurrency, String requestId) {
        return create(schemeId, versionNo, name, conversationIds, concurrency, requestId, null);
    }

    /** Only an approved variant code is accepted; task-side detector or scoring overrides remain unsupported. */
    public InspectionTask create(String schemeId, int versionNo, String name, List<String> conversationIds,
                                 Integer concurrency, String requestId, String variantCode) {
        return createInternal(schemeId, versionNo, name, conversationIds, null, null, concurrency, requestId, false, variantCode);
    }

    /** Creates a schedule with a frozen published scheme and a data filter resolved only when due. */
    public InspectionTask createScheduled(String schemeId, int versionNo, String name,
                                          InspectionTaskService.ScheduledFilter selectionFilter, LocalDateTime scheduledTime,
                                          Integer concurrency, String requestId) {
        return createScheduled(schemeId, versionNo, name, selectionFilter, scheduledTime, concurrency, requestId, null);
    }

    /** A schedule selects its immutable variant before fingerprinting or creating the task. */
    public InspectionTask createScheduled(String schemeId, int versionNo, String name,
                                          InspectionTaskService.ScheduledFilter selectionFilter, LocalDateTime scheduledTime,
                                          Integer concurrency, String requestId, String variantCode) {
        return createInternal(schemeId, versionNo, name, List.of(), selectionFilter, scheduledTime, concurrency, requestId, true, variantCode);
    }

    private InspectionTask createInternal(String schemeId, int versionNo, String name, List<String> conversationIds,
                                          InspectionTaskService.ScheduledFilter selectionFilter, LocalDateTime scheduledTime,
                                          Integer concurrency, String requestId, boolean scheduled, String variantCode) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}")) throw IqcException.invalidArgument("任务请求标识无效");
        if (name != null && name.length() > 128) throw IqcException.invalidArgument("任务名称不能超过 128 字符");
        String taskId = "st-" + InspectionSchemeService.contentHash(scope.owner() + ":" + requestId).substring(0, 60);
        Object request = scheduled
                    ? new ScheduledRequest(schemeId, versionNo, name, selectionFilter,
                            scheduledTime == null ? null : scheduledTime.toString(), concurrency)
                    : new Request(schemeId, versionNo, name, conversationIds, concurrency);
        InspectionTask existing = taskMapper.selectById(taskId);
        if (existing != null) {
            if (!scope.canView(existing.getCreatedBy(), existing.getOwnerGroupId())) throw IqcException.accessDenied("无权访问已有任务");
            return repeated(existing, fingerprint(request, frozenRelease(existing), variantCode));
        }
        var version = schemes.published(schemeId, versionNo);
        InspectionSchemeService.ReleaseSnapshot selected;
        try {
            if (!InspectionSchemeService.contentHash(version.getSnapshotJson()).equals(version.getContentHash()))
                throw IqcException.invalidState("方案发布快照校验失败");
            var release = mapper.readValue(version.getSnapshotJson(), InspectionSchemeService.ReleaseSnapshot.class);
            selected = release.selectVariant(variantCode);
            // Availability checks use exact versions; they never replace the immutable release configuration.
            dependencies.validateReleasedDependencies(selected);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidState("方案发布快照无效"); }
        String fingerprint = fingerprint(request, selected, variantCode);
        try {
            if (selected.definition().executionVariants() != null)
                return scheduled
                        ? tasks.createScheduledFromScheme(name, selectionFilter, scheduledTime, concurrency, version, taskId, fingerprint, selected.selectedVariantCode())
                        : tasks.createFromScheme(name, conversationIds, concurrency, version, taskId, fingerprint, selected.selectedVariantCode());
            return scheduled
                    ? tasks.createScheduledFromScheme(name, selectionFilter, scheduledTime, concurrency, version, taskId, fingerprint)
                    : tasks.createFromScheme(name, conversationIds, concurrency, version, taskId, fingerprint);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            // The task service's transaction has rolled back before this read, so racing requests converge safely.
            existing = taskMapper.selectById(taskId);
            if (existing == null) throw exception;
            return repeated(existing, fingerprint);
        }
    }

    private String fingerprint(Object request, InspectionSchemeService.ReleaseSnapshot release, String variantCode) {
        try {
            if (release != null && release.definition() == null) throw IqcException.invalidState("已有任务快照缺少方案定义");
            if (release != null && release.definition().executionVariants() != null) {
                var selected = release.selectVariant(variantCode).forTaskSnapshot();
                var json = mapper.readTree(mapper.writeValueAsString(selected));
                request = new VariantRequest(request, selected.selectedVariantCode(), InspectionSchemeService.contentHash(json.toString()));
            } else if (variantCode != null) throw IqcException.invalidArgument("模板未配置允许策略变体");
            return InspectionSchemeService.contentHash(mapper.writeValueAsString(request));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw IqcException.invalidState("任务请求或冻结变体无法解析");
        }
    }

    /** Retries use the existing release even after template disablement or draft revision. */
    private InspectionSchemeService.ReleaseSnapshot frozenRelease(InspectionTask task) {
        try {
            var root = mapper.readTree(task.getRuleSnapshotJson());
            var scheme = root.path("schemeSnapshot");
            var release = scheme.path("release");
            if (release.isMissingNode()) return null; // Historical non-variant retry fingerprints are unchanged.
            if (release.path("definition").hasNonNull("executionVariants")) {
                if (!InspectionSchemeService.contentHash(release.toString()).equals(scheme.path("contentHash").asText()))
                    throw IqcException.invalidState("已有任务冻结变体摘要校验失败");
                var agent = release.path("dependencies").path("agent");
                SchemeDependencyResolver.validateRouteTaskProjection(root, agent.isMissingNode() || agent.isNull() ? null : agent.toString(), mapper);
            }
            var frozen = mapper.treeToValue(release, InspectionSchemeService.ReleaseSnapshot.class);
            if (frozen == null || frozen.definition() == null) throw IqcException.invalidState("已有任务快照缺少方案定义");
            return frozen;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw IqcException.invalidState("已有任务快照损坏");
        }
    }

    private InspectionTask repeated(InspectionTask task, String fingerprint) {
        if (!scope.canView(task.getCreatedBy(), task.getOwnerGroupId())) throw IqcException.accessDenied("无权访问已有任务");
        try {
            if (!fingerprint.equals(mapper.readTree(task.getRuleSnapshotJson()).path("schemeSnapshot").path("requestFingerprint").asText()))
                throw IqcException.invalidArgument("同一请求标识不能用于不同任务配置");
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidState("已有任务快照损坏"); }
        return task;
    }

    private record Request(String schemeId, int versionNo, String name, List<String> conversationIds, Integer concurrency) { }
    private record VariantRequest(Object request, String variantCode, String selectedReleaseHash) { }
    private record ScheduledRequest(String schemeId, int versionNo, String name,
                                    InspectionTaskService.ScheduledFilter selectionFilter,
                                    String scheduledTime, Integer concurrency) { }
}
