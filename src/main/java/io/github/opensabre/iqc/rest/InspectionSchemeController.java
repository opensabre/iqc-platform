package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.scheme.InspectionSchemeService;
import io.github.opensabre.iqc.scheme.SchemePublicationService;
import io.github.opensabre.iqc.scheme.SchemeTaskService;
import io.github.opensabre.iqc.scheme.model.InspectionScheme;
import io.github.opensabre.iqc.scheme.model.InspectionSchemeVersion;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.task.model.InspectionTask;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Separates expert standard maintenance from ordinary use of published business templates. */
@RestController
@RequestMapping("/api/iqc/schemes")
@RequiredArgsConstructor
@Tag(name = "业务质检方案", description = "专家维护、草稿试跑、版本发布与模板任务")
public class InspectionSchemeController {
    private final InspectionSchemeService schemes;
    private final SchemePublicationService publication;
    private final SchemeTaskService tasks;

    @GetMapping
    @ResourcePermission(code="iqc:scheme:manage", name="维护业务方案", type="iqc", description="查询业务方案草稿")
    public List<InspectionScheme> drafts() { return schemes.list(); }

    @GetMapping("/published")
    @ResourcePermission(code="iqc:scheme:view", name="查看业务模板", type="iqc", description="查询已发布业务方案模板")
    public Object templates(@RequestParam(required=false) String schemeId,
                            @RequestParam(required=false) Integer beforeVersion) {
        if (schemeId == null) {
            if (beforeVersion != null) throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("查询历史版本时必须指定方案 ID");
            return schemes.templates();
        }
        return schemes.publishedHistory(schemeId, beforeVersion);
    }

    @GetMapping("/{id}/versions")
    @Operation(summary="查看专家发布历史", description="按版本倒序，每次最多 20 个；使用正版本号游标读取更早标准，停用历史仍可读，不改变可用版本")
    @ResourcePermission(code="iqc:scheme:manage", name="维护业务方案", type="iqc", description="查询业务方案草稿")
    public InspectionSchemeService.VersionHistory history(@PathVariable String id,
                                                          @RequestParam(required=false) Integer beforeVersion) {
        return schemes.history(id, beforeVersion);
    }

    @PostMapping
    @ResourcePermission(code="iqc:scheme:create", name="创建业务方案", type="iqc", description="创建业务方案草稿")
    @Audit(operationType=OperationType.CREATE, description="创建业务质检方案", module="IQC_SCHEME")
    public InspectionScheme create(@RequestBody InspectionSchemeService.DraftRequest request) { return schemes.create(request); }

    @PutMapping("/{id}")
    @ResourcePermission(code="iqc:scheme:update", name="修订业务方案", type="iqc", description="修订业务方案草稿")
    @Audit(operationType=OperationType.UPDATE, description="修订业务质检方案", module="IQC_SCHEME")
    public InspectionScheme revise(@PathVariable String id, @RequestBody @Valid RevisionRequest request) {
        return schemes.revise(id, request.expectedRevision(), request.draft());
    }

    @PostMapping("/{id}/preview")
    @Operation(summary="检查方案依赖", description="仅结构与依赖检查，不替代真实试跑")
    @ResourcePermission(code="iqc:scheme:preview", name="检查业务方案", type="iqc", description="检查草稿结构和依赖")
    public InspectionSchemeService.ReleaseSnapshot preview(@PathVariable String id, @RequestBody @Valid Revision request) {
        return schemes.preview(id, request.expectedRevision());
    }

    @PostMapping("/{id}/trials")
    @Operation(summary="创建草稿试跑任务", description="可选请求标识支持超时幂等重试；使用现有任务执行接口启动，最多 20 个可见会话")
    @ResourcePermission(code="iqc:scheme:trial:create", name="试跑业务方案", type="iqc", description="创建业务方案草稿试跑任务")
    @Audit(operationType=OperationType.CREATE, description="创建业务方案试跑", module="IQC_SCHEME")
    public InspectionTask trial(@PathVariable String id, @RequestBody @Valid TrialRequest request) {
        return publication.trial(id, request.expectedRevision(), request.conversationIds(), request.requestId(),
                request.variantCode(), request.runCount(), request.confidenceThreshold());
    }

    @GetMapping("/{id}/trials")
    @Operation(summary="查询最近试跑", description="最近 20 条当前用户可见的方案试跑任务，修订号以任务快照为准")
    @ResourcePermission(code="iqc:scheme:trial:view", name="查看方案试跑", type="iqc", description="恢复业务方案试跑记录")
    public List<InspectionTask> trials(@PathVariable String id) { return publication.trials(id); }

    @PostMapping("/{id}/publish")
    @Operation(summary="管理业务模板发布状态", description="PUBLISH 要求完整试跑与专家确认；DISABLE/ENABLE 切换方案可用性；ARCHIVE_VERSION/RESTORE_VERSION 仅作用于已被取代的发布版本")
    @ResourcePermission(code="iqc:scheme:publish", name="发布业务方案", type="iqc", description="确认发布、切换模板可用性及归档发布版本")
    @Audit(operationType=OperationType.UPDATE, description="管理业务质检模板发布与可用性", module="IQC_SCHEME")
    public Object publish(@PathVariable String id, @RequestBody @Valid PublishRequest request) {
        String action = request.action() == null ? "PUBLISH" : request.action();
        if ("DISABLE".equals(action) || "ENABLE".equals(action)) {
            if (request.trialTaskId() != null || request.resultsReviewed() || request.versionNo() != null)
                throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("停用或恢复不能混入发布试跑或版本参数");
            return schemes.changeAvailability(id, request.expectedRevision(), "ENABLE".equals(action));
        }
        if ("ARCHIVE_VERSION".equals(action) || "RESTORE_VERSION".equals(action)) {
            if (request.trialTaskId() != null || request.resultsReviewed() || request.versionNo() == null)
                throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("版本归档或恢复必须只指定目标版本");
            return schemes.changeVersionArchive(id, request.expectedRevision(), request.versionNo(), "ARCHIVE_VERSION".equals(action));
        }
        if (!"PUBLISH".equals(action) || request.trialTaskId() == null || request.trialTaskId().isBlank())
            throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("发布动作或试跑记录无效");
        if (request.versionNo() != null) throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("发布动作不能指定历史版本号");
        return publication.publish(id, request.expectedRevision(), request.trialTaskId(), request.resultsReviewed());
    }

    @PostMapping("/{id}/versions/{versionNo}/tasks")
    @Operation(summary="使用业务模板创建任务", description="按请求标识幂等创建，可指定模板允许的 variantCode，省略则推荐；正式能力门禁仍适用，拒绝未声明字段及规则、评分或 Agent 覆盖")
    @ResourcePermission(code="iqc:scheme:use", name="使用业务模板", type="iqc", description="按已发布业务模板创建质检任务")
    @Audit(operationType=OperationType.CREATE, description="使用业务模板创建任务", module="IQC_TASK")
    public InspectionTask task(@PathVariable String id, @PathVariable int versionNo, @RequestBody @Valid TaskRequest request) {
        if (!request.unsupportedFields().isEmpty())
            throw new IllegalArgumentException("模板任务不支持字段: " + String.join(", ", request.unsupportedFields()));
        String taskType = request.taskType() == null ? "BATCH" : request.taskType().trim().toUpperCase(java.util.Locale.ROOT);
        if ("BATCH".equals(taskType)) {
            if (request.scheduledTime() != null || request.selectionFilter() != null)
                throw new IllegalArgumentException("批量模板任务不接受 scheduledTime 或 selectionFilter 字段");
            if (request.conversationIds() == null || request.conversationIds().isEmpty())
                throw new IllegalArgumentException("批量模板任务必须选择会话");
            return request.variantCode() == null
                    ? tasks.create(id, versionNo, request.name(), request.conversationIds(), request.concurrency(), request.requestId())
                    : tasks.create(id, versionNo, request.name(), request.conversationIds(), request.concurrency(), request.requestId(), request.variantCode());
        }
        if ("SCHEDULED".equals(taskType)) {
            if (request.conversationIds() != null && !request.conversationIds().isEmpty())
                throw new IllegalArgumentException("定时模板任务使用 selectionFilter，不能提交 conversationIds");
            if (request.scheduledTime() == null || request.scheduledTime().isBlank())
                throw new IllegalArgumentException("定时模板任务必须指定 scheduledTime");
            return request.variantCode() == null
                    ? tasks.createScheduled(id, versionNo, request.name(), request.selectionFilter(),
                        parseScheduledTime(request.scheduledTime()), request.concurrency(), request.requestId())
                    : tasks.createScheduled(id, versionNo, request.name(), request.selectionFilter(),
                        parseScheduledTime(request.scheduledTime()), request.concurrency(), request.requestId(), request.variantCode());
        }
        throw new IllegalArgumentException("模板任务类型仅支持 BATCH 或 SCHEDULED");
    }

    private java.time.LocalDateTime parseScheduledTime(String value) {
        try { return java.time.LocalDateTime.parse(value); }
        catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("scheduledTime 必须使用 ISO 本地日期时间格式", exception);
        }
    }

    public record Revision(@Min(1) int expectedRevision) { }
    public record RevisionRequest(@Min(1) int expectedRevision, @NotNull InspectionSchemeService.DraftRequest draft) { }
    public record TrialRequest(@Min(1) int expectedRevision, @NotEmpty @Size(max=20) List<@NotBlank String> conversationIds,
                               @Pattern(regexp="[A-Za-z0-9_-]{16,64}") String requestId,
                               @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String variantCode,
                               @Min(1) @Max(5) Integer runCount,
                               @DecimalMin("0.0") @DecimalMax("1.0") java.math.BigDecimal confidenceThreshold) {
        public TrialRequest(int expectedRevision, List<String> conversationIds) { this(expectedRevision, conversationIds, null, null, null, null); }
        public TrialRequest(int expectedRevision, List<String> conversationIds, String requestId) {
            this(expectedRevision, conversationIds, requestId, null, null, null);
        }
        public TrialRequest(int expectedRevision, List<String> conversationIds, String requestId, String variantCode) {
            this(expectedRevision, conversationIds, requestId, variantCode, null, null);
        }
    }
    public record PublishRequest(@Min(1) int expectedRevision, String trialTaskId, boolean resultsReviewed, String action,
                                 @Min(1) Integer versionNo) {
        public PublishRequest(int expectedRevision, String trialTaskId, boolean resultsReviewed, String action) {
            this(expectedRevision, trialTaskId, resultsReviewed, action, null);
        }
    }
    public static final class TaskRequest {
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{16,64}") private String requestId;
        @Size(max=128) private String name;
        @Size(max=1000) private List<@NotBlank String> conversationIds;
        @Min(1) @Max(32) private Integer concurrency;
        @Pattern(regexp="[A-Za-z0-9_-]{1,64}") private String variantCode;
        private String taskType;
        private InspectionTaskService.ScheduledFilter selectionFilter;
        private String scheduledTime;
        private final Set<String> unsupportedFields = new TreeSet<>();

        public TaskRequest() { }

        public TaskRequest(String requestId, String name, List<String> conversationIds, Integer concurrency) {
            this.requestId = requestId;
            this.name = name;
            this.conversationIds = conversationIds;
            this.concurrency = concurrency;
        }

        public String requestId() { return requestId; }
        public String name() { return name; }
        public List<String> conversationIds() { return conversationIds; }
        public Integer concurrency() { return concurrency; }
        public String variantCode() { return variantCode; }
        public String taskType() { return taskType; }
        public InspectionTaskService.ScheduledFilter selectionFilter() { return selectionFilter; }
        public String scheduledTime() { return scheduledTime; }

        public void setRequestId(String requestId) { this.requestId = requestId; }
        public void setName(String name) { this.name = name; }
        public void setConversationIds(List<String> conversationIds) { this.conversationIds = conversationIds; }
        public void setConcurrency(Integer concurrency) { this.concurrency = concurrency; }
        public void setVariantCode(String variantCode) { this.variantCode = variantCode; }
        public void setTaskType(String taskType) { this.taskType = taskType; }
        public void setSelectionFilter(InspectionTaskService.ScheduledFilter selectionFilter) { this.selectionFilter = selectionFilter; }
        public void setScheduledTime(String scheduledTime) { this.scheduledTime = scheduledTime; }

        @JsonAnySetter
        public void captureUnsupportedField(String field, Object ignored) { unsupportedFields.add(field); }

        public Set<String> unsupportedFields() { return java.util.Collections.unmodifiableSet(new TreeSet<>(unsupportedFields)); }
    }
}
