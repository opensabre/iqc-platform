package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.governance.ratelimit.annotations.RateLimit;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import io.github.opensabre.governance.usage.UsageOutcome;
import io.github.opensabre.governance.usage.UsageRecord;
import io.github.opensabre.iqc.result.InspectionExecutionService;
import io.github.opensabre.iqc.result.BatchResultQueryService;
import io.github.opensabre.iqc.result.BatchResultQueryService.BatchResultSummary;
import io.github.opensabre.iqc.result.BatchResultQueryService.ConversationResultDetail;
import io.github.opensabre.iqc.result.HierarchicalResultService;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.label.LabelResultQueryService;
import io.github.opensabre.iqc.label.LabelInsightExportService;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.shared.IqcPage;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@lombok.extern.slf4j.Slf4j
@RestController
@RequestMapping("/api/iqc")
public class InspectionResultController {
    @org.springframework.beans.factory.annotation.Value("${iqc.task.worker-enabled:true}")
    private boolean workerEnabled=true;
    private final InspectionExecutionService executionService;
    private final io.github.opensabre.iqc.task.BulkTaskAccessService bulk;
    private final UsageCounterRecorder usageCounterRecorder;
    private final BatchResultQueryService batchResultQueryService;
    private final HierarchicalResultService hierarchicalResultService;
    private final LabelResultQueryService labelResultQueryService;
    private final LabelInsightExportService labelInsightExportService;
    private final io.github.opensabre.iqc.quality.BusinessItemReviewService businessReviews;
    private final io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive;
    private final io.github.opensabre.iqc.privacy.ResultReadProjection resultProjection;

    public InspectionResultController(InspectionExecutionService executionService, UsageCounterRecorder usageCounterRecorder,
                                      BatchResultQueryService batchResultQueryService,
                                      HierarchicalResultService hierarchicalResultService,
                                      LabelResultQueryService labelResultQueryService,
                                      LabelInsightExportService labelInsightExportService,
                                      io.github.opensabre.iqc.quality.BusinessItemReviewService businessReviews) {
        this(executionService, usageCounterRecorder, batchResultQueryService, hierarchicalResultService,
                labelResultQueryService, labelInsightExportService, businessReviews, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InspectionResultController(InspectionExecutionService executionService, UsageCounterRecorder usageCounterRecorder,
                                      BatchResultQueryService batchResultQueryService,
                                      HierarchicalResultService hierarchicalResultService,
                                      LabelResultQueryService labelResultQueryService,
                                      LabelInsightExportService labelInsightExportService,
                                      io.github.opensabre.iqc.quality.BusinessItemReviewService businessReviews,
                                      io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive,
                                      io.github.opensabre.iqc.privacy.ResultReadProjection resultProjection,
                                      io.github.opensabre.iqc.task.BulkTaskAccessService bulk) {
        this.bulk=bulk;
        this.executionService = executionService; this.usageCounterRecorder = usageCounterRecorder;
        this.batchResultQueryService = batchResultQueryService; this.hierarchicalResultService = hierarchicalResultService;
        this.labelResultQueryService = labelResultQueryService;
        this.labelInsightExportService = labelInsightExportService;
        this.businessReviews = businessReviews;
        this.sensitive = sensitive; this.resultProjection = resultProjection;
    }

    public InspectionResultController(InspectionExecutionService executionService,UsageCounterRecorder usageCounterRecorder,
            BatchResultQueryService batchResultQueryService,HierarchicalResultService hierarchicalResultService,
            LabelResultQueryService labelResultQueryService,LabelInsightExportService labelInsightExportService,
            io.github.opensabre.iqc.quality.BusinessItemReviewService businessReviews,
            io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive,
            io.github.opensabre.iqc.privacy.ResultReadProjection resultProjection) {
        this(executionService,usageCounterRecorder,batchResultQueryService,hierarchicalResultService,labelResultQueryService,
                labelInsightExportService,businessReviews,sensitive,resultProjection,null);
    }

    private io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access resultReadAccess() {
        return resultReadAccess(java.util.Set.of("iqc:result:view"));
    }
    private io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access resultReadAccess(java.util.Set<String> required) {
        if (sensitive == null || resultProjection == null)
            throw io.github.opensabre.iqc.governance.IqcException.invalidState("结果敏感读取授权组件不可用");
        var access = sensitive.current(required);
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet && servlet.getResponse() != null) {
            servlet.getResponse().setHeader("Cache-Control", "private, no-store");
            servlet.getResponse().setHeader("X-Content-Type-Options", "nosniff");
        }
        return access;
    }

    private io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access resultExportAccess() {
        return resultExportAccess("iqc:result:export");
    }
    private io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access resultExportAccess(String resource) {
        if (sensitive == null)
            throw io.github.opensabre.iqc.governance.IqcException.invalidState("结果导出敏感读取授权组件不可用");
        var access = sensitive.current(java.util.Set.of(resource));
        access.requireFullConversation(); access.requirePii();
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet && servlet.getResponse() != null) {
            servlet.getResponse().setHeader("Cache-Control", "private, no-store");
            servlet.getResponse().setHeader("X-Content-Type-Options", "nosniff");
        }
        return access;
    }

    @PostMapping("/tasks/{id}/run")
    @ResourcePermission(code = "iqc:task:execute", name = "执行质检任务", type = "iqc", description = "执行质检任务")
    @Audit(operationType = OperationType.SCAN, description = "执行 IQC 质检任务", module = "IQC_TASK", request = false, response = false, key = "#id")
    @RateLimit(sceneCode = "iqc-task-run", maxCount = 10, period = 60)
    public InspectionTask run(@PathVariable String id) {
        var access = resultReadAccess(java.util.Set.of("iqc:task:execute"));
        var coordinated=bulk==null?java.util.Optional.<InspectionTask>empty():bulk.queueIfBulk(id);
        if(coordinated.isPresent()) {
            usageCounterRecorder.record(new UsageRecord("inspection-task:run:"+id,null,"iqc-platform","INSPECTION_TASK",id,"RUN",UsageOutcome.SUCCESS));
            return resultProjection.taskReceipt(coordinated.get(),access);
        }
        InspectionTask task = executionService.queue(id);
        try{executionService.executeAsync(id,task.getCurrentExecutionId());}
        catch(org.springframework.core.task.TaskRejectedException busy){if(!workerEnabled)throw busy;log.info("event=iqc_api_backpressure taskId={} status=QUEUED",id);}
        usageCounterRecorder.record(new UsageRecord("inspection-task:run:" + id, null, "iqc-platform", "INSPECTION_TASK", id, "RUN", UsageOutcome.SUCCESS));
        return resultProjection.taskReceipt(task,access);
    }

    @PostMapping("/tasks/{id}/resume")
    @ResourcePermission(code = "iqc:task:resume", name = "恢复质检任务", type = "iqc", description = "恢复已暂停的质检任务")
    @Audit(operationType = OperationType.UPDATE, description = "恢复 IQC 质检任务", module = "IQC_TASK", request = false, response = false, key = "#id")
    public InspectionTask resume(@PathVariable String id) {
        var access = resultReadAccess(java.util.Set.of("iqc:task:resume"));
        var coordinated=bulk==null?java.util.Optional.<InspectionTask>empty():bulk.queueIfBulk(id);
        if(coordinated.isPresent())return resultProjection.taskReceipt(coordinated.get(),access);
        InspectionTask task = executionService.resume(id);
        try{executionService.executeAsync(id,task.getCurrentExecutionId());}
        catch(org.springframework.core.task.TaskRejectedException busy){if(!workerEnabled)throw busy;log.info("event=iqc_api_backpressure taskId={} status=QUEUED",id);}
        return resultProjection.taskReceipt(task,access);
    }

    @GetMapping("/results")
    @ResourcePermission(code = "iqc:result:view", name = "查看质检结果", type = "iqc", description = "查询质检结果")
    @io.github.opensabre.governance.audit.annotations.Audit(operationType = OperationType.QUERY, description = "读取质检结果列表", module = "IQC_PRIVACY", request = false, response = false, key = "#taskId")
    @RateLimit(sceneCode = "iqc-result-query", maxCount = 60, period = 60)
    public IqcPage<InspectionResult> results(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "20") long size,
                                          @RequestParam(required = false) String taskId,
                                          @RequestParam(required = false) String agentId,
                                          @RequestParam(required = false) String ownerId,
                                          @RequestParam(required = false) String groupId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) Integer minScore,
                                          @RequestParam(required = false) Integer maxScore,
                                          @RequestParam(required = false) String speakerRole,
                                          @RequestParam(required = false) String riskLevel) {
        var access = resultReadAccess();
        var page = executionService.page(current, size, taskId, agentId, ownerId, groupId, status, minScore, maxScore, speakerRole, riskLevel);
        return new IqcPage<>(page.records().stream().map(row -> resultProjection.result(row, access)).toList(),
                page.current(), page.size(), page.total());
    }

    @GetMapping("/results/{id}")
    @ResourcePermission(code = "iqc:result:detail:view", name = "查看质检结果详情", type = "iqc", description = "查看质检结果详情")
    @io.github.opensabre.governance.audit.annotations.Audit(operationType = OperationType.QUERY, description = "读取单条质检结果", module = "IQC_PRIVACY", request = false, response = false, key = "#id")
    public Map<String, Object> resultDetail(@PathVariable String id) {
        var access = resultReadAccess(java.util.Set.of("iqc:result:detail:view"));
        return resultProjection.detail(executionService.detail(id), access);
    }

    /** Current business conversation results; message filters and legacy scores have a separate endpoint. */
    @GetMapping("/results/business")
    @ResourcePermission(code = "iqc:result:business:view", name = "查看业务会话结果", type = "iqc", description = "按当前会话业务结果分页查询")
    @RateLimit(sceneCode = "iqc-result-query", maxCount = 60, period = 60)
    @Audit(operationType = OperationType.QUERY, description = "读取业务会话结果分页", module = "IQC_PRIVACY", request = false, response = false)
    public IqcPage<BatchResultQueryService.BusinessConversationRow> businessResults(
            @RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String taskId, @RequestParam(required = false) String scoreStatus,
            @RequestParam(required = false) String riskLevel, @RequestParam(required = false) java.math.BigDecimal minScore,
            @RequestParam(required = false) java.math.BigDecimal maxScore,
            @RequestParam(required = false) String parentTaskId) {
        var access = resultReadAccess(java.util.Set.of("iqc:result:business:view"));
        return resultProjection.businessPage(batchResultQueryService.businessPage(current,size,taskId,scoreStatus,riskLevel,minScore,maxScore,parentTaskId),access);
    }
    public IqcPage<BatchResultQueryService.BusinessConversationRow> businessResults(long current,long size,String taskId,
            String scoreStatus,String riskLevel,java.math.BigDecimal minScore,java.math.BigDecimal maxScore) {
        return businessResults(current,size,taskId,scoreStatus,riskLevel,minScore,maxScore,null);
    }

    @GetMapping("/tasks/{id}/result-summary")
    @ResourcePermission(code = "iqc:result:summary:view", name = "查看批次质检结果", type = "iqc", description = "查看质检批次及会话汇总结果")
    @Audit(operationType = OperationType.QUERY, description = "读取批次质检汇总", module = "IQC_PRIVACY", request = false, response = false, key = "#id")
    public BatchResultSummary batchSummary(@PathVariable String id) {
        var access = resultReadAccess(java.util.Set.of("iqc:result:summary:view"));
        return resultProjection.batchSummary(batchResultQueryService.summary(id), access);
    }

    @GetMapping("/tasks/{id}/label-results")
    @ResourcePermission(code = "iqc:result:labels:view", name = "查看标签洞察结果", type = "iqc", description = "查看任务生成的业务标签命中结果")
    @Audit(operationType = OperationType.QUERY, description = "读取标签质检结果", module = "IQC_PRIVACY", request = false, response = false, key = "#id")
    public List<Map<String, Object>> labelResults(@PathVariable String id) {
        var access = resultReadAccess(java.util.Set.of("iqc:result:labels:view"));
        return resultProjection.labelResults(labelResultQueryService.listByTask(id), access);
    }

    @GetMapping("/tasks/{id}/label-insights")
    @ResourcePermission(code = "iqc:result:label-insights:view", name = "查看标签洞察统计", type = "iqc", description = "查看标签及标签组分布和检出率")
    public LabelResultQueryService.LabelInsightSummary labelInsights(@PathVariable String id) {
        resultReadAccess(java.util.Set.of("iqc:result:label-insights:view"));
        return labelResultQueryService.summary(id);
    }

    @GetMapping(value = "/tasks/{id}/label-results/export", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @ResourcePermission(code = "iqc:result:labels:export", name = "导出标签洞察结果", type = "iqc", description = "导出任务标签结果 XLSX")
    @Audit(operationType = OperationType.EXPORT, description = "导出 IQC 标签洞察结果", module = "IQC_RESULT", request = false, response = false, key = "#id")
    public ResponseEntity<byte[]> exportLabelResults(@PathVariable String id) {
        resultExportAccess("iqc:result:labels:export");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=iqc-label-results.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(labelInsightExportService.exportTask(id));
    }

    @GetMapping("/tasks/{id}/conversations/{conversationId}/result-detail")
    @ResourcePermission(code = "iqc:result:conversation:view", name = "查看会话质检明细", type = "iqc", description = "查看批次内会话记录和质检标注")
    @io.github.opensabre.governance.audit.annotations.Audit(operationType = OperationType.QUERY, description = "读取会话质检明细", module = "IQC_PRIVACY", request = false, response = false, key = "#conversationId")
    public Map<String, Object> conversationResultDetail(@PathVariable String id, @PathVariable String conversationId) {
        var access = resultReadAccess(java.util.Set.of("iqc:result:conversation:view"));
        return resultProjection.conversationDetail(batchResultQueryService.conversationDetail(id, conversationId), access);
    }

    @GetMapping("/tasks/{id}/conversations/{conversationId}/result-hierarchy")
    @ResourcePermission(code = "iqc:result:hierarchy:view", name = "查看分层质检结果", type = "iqc", description = "按会话、规则和消息证据查询质检结果")
    @io.github.opensabre.governance.audit.annotations.Audit(operationType = OperationType.QUERY, description = "读取分层质检结果", module = "IQC_PRIVACY", request = false, response = false, key = "#conversationId")
    public Optional<Map<String, Object>> resultHierarchy(@PathVariable String id, @PathVariable String conversationId) {
        var access = resultReadAccess(java.util.Set.of("iqc:result:hierarchy:view"));
        batchResultQueryService.conversationDetail(id, conversationId);
        return Optional.ofNullable(hierarchicalResultService.hierarchy(id, conversationId))
                .map(hierarchy -> resultProjection.hierarchy(hierarchy, access));
    }

    @GetMapping("/conversations/{conversationId}/result-detail")
    @ResourcePermission(code = "iqc:result:conversation-history:view", name = "查看会话质检明细", type = "iqc", description = "查看会话全部质检结果和标注")
    @io.github.opensabre.governance.audit.annotations.Audit(operationType = OperationType.QUERY, description = "读取会话质检明细", module = "IQC_PRIVACY", request = false, response = false, key = "#conversationId")
    public Map<String, Object> conversationResultDetail(@PathVariable String conversationId) {
        var access = resultReadAccess(java.util.Set.of("iqc:result:conversation-history:view"));
        return resultProjection.conversationDetail(batchResultQueryService.conversationDetail(conversationId), access);
    }

    @GetMapping(value = "/results/export", produces = {"text/csv", "application/zip"})
    @ResourcePermission(code = "iqc:result:export", name = "导出质检结果", type = "iqc", description = "导出质检结果")
    @Audit(operationType = OperationType.EXPORT, description = "导出 IQC 质检结果", module = "IQC_RESULT", request = false, response = false)
    @RateLimit(sceneCode = "iqc-result-export", maxCount = 10, period = 60)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String taskId,
                                         @RequestParam(required = false) String agentId,
                                         @RequestParam(required = false) String ownerId,
                                         @RequestParam(required = false) String groupId,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) Integer minScore,
                                         @RequestParam(required = false) Integer maxScore,
                                         @RequestParam(required = false) String speakerRole,
                                         @RequestParam(required = false) String riskLevel,
                                         @RequestParam(defaultValue = "MESSAGE") String view,
                                         @RequestParam(required = false) String reviewId) {
        resultExportAccess();
        if ("BUSINESS_REVIEW_TASK".equals(view)) {
            if (reviewId != null || agentId != null || ownerId != null || groupId != null || status != null
                    || minScore != null || maxScore != null || speakerRole != null || riskLevel != null)
                throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("批量复核导出只能指定任务，不支持轮次或消息筛选");
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=iqc-task-reviews.zip")
                    .contentType(MediaType.parseMediaType("application/zip")).body(businessReviews.exportTaskZip(taskId));
        }
        if ("BUSINESS_REVIEW".equals(view)) {
            if (taskId != null || agentId != null || ownerId != null || groupId != null || status != null
                    || minScore != null || maxScore != null || speakerRole != null || riskLevel != null)
                throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("复核导出只能指定复核轮次，不支持任务或消息筛选");
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=iqc-business-review.csv")
                    .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                    .body(businessReviews.exportCsv(reviewId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (reviewId != null) throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("非复核导出不得指定复核轮次");
        if (!"MESSAGE".equals(view) && !"SCHEME".equals(view))
            throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("不支持的结果导出视图");
        if ("SCHEME".equals(view) && (agentId != null || ownerId != null || groupId != null || status != null
                || minScore != null || maxScore != null || speakerRole != null || riskLevel != null))
            throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("业务导出按完整任务导出，不支持消息筛选条件");
        String csv = "SCHEME".equals(view) ? batchResultQueryService.exportSchemeCsv(taskId)
                : executionService.exportCsv(taskId, agentId, ownerId, groupId, status, minScore, maxScore, speakerRole, riskLevel);
        byte[] body = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String filename = "SCHEME".equals(view) ? "iqc-business-results.csv" : "iqc-results.csv";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(body);
    }
}
