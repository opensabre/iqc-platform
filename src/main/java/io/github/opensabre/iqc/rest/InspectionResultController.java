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
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.shared.IqcPage;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/iqc")
public class InspectionResultController {
    private final InspectionExecutionService executionService;
    private final UsageCounterRecorder usageCounterRecorder;
    private final BatchResultQueryService batchResultQueryService;

    public InspectionResultController(InspectionExecutionService executionService, UsageCounterRecorder usageCounterRecorder,
                                      BatchResultQueryService batchResultQueryService) {
        this.executionService = executionService; this.usageCounterRecorder = usageCounterRecorder; this.batchResultQueryService = batchResultQueryService;
    }

    @PostMapping("/tasks/{id}/run")
    @ResourcePermission(code = "iqc:task:execute", name = "执行质检任务", type = "iqc", description = "执行质检任务")
    @Audit(operationType = OperationType.SCAN, description = "执行 IQC 质检任务", module = "IQC_TASK")
    @RateLimit(sceneCode = "iqc-task-run", maxCount = 10, period = 60)
    public InspectionTask run(@PathVariable String id) {
        InspectionTask task = executionService.queue(id);
        executionService.executeAsync(id, task.getCurrentExecutionId());
        usageCounterRecorder.record(new UsageRecord("inspection-task:run:" + id, null, "iqc-platform", "INSPECTION_TASK", id, "RUN", UsageOutcome.SUCCESS));
        return task;
    }

    @GetMapping("/results")
    @ResourcePermission(code = "iqc:result:view", name = "查看质检结果", type = "iqc", description = "查询质检结果")
    @Audit(operationType = OperationType.QUERY, description = "查询 IQC 质检结果", module = "IQC_RESULT")
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
        return executionService.page(current, size, taskId, agentId, ownerId, groupId, status, minScore, maxScore, speakerRole, riskLevel);
    }

    @GetMapping("/results/{id}")
    @ResourcePermission(code = "iqc:result:view", name = "查看质检结果详情", type = "iqc", description = "查看质检结果详情")
    @Audit(operationType = OperationType.QUERY, description = "查看 IQC 结果详情", module = "IQC_RESULT")
    public Map<String, Object> resultDetail(@PathVariable String id) { return executionService.detail(id); }

    @GetMapping("/tasks/{id}/result-summary")
    @ResourcePermission(code = "iqc:result:view", name = "查看批次质检结果", type = "iqc", description = "查看质检批次及会话汇总结果")
    @Audit(operationType = OperationType.QUERY, description = "查看 IQC 批次结果汇总", module = "IQC_RESULT")
    public BatchResultSummary batchSummary(@PathVariable String id) { return batchResultQueryService.summary(id); }

    @GetMapping("/tasks/{id}/conversations/{conversationId}/result-detail")
    @ResourcePermission(code = "iqc:result:view", name = "查看会话质检明细", type = "iqc", description = "查看批次内会话记录和质检标注")
    @Audit(operationType = OperationType.QUERY, description = "查看 IQC 会话质检明细", module = "IQC_RESULT")
    public ConversationResultDetail conversationResultDetail(@PathVariable String id, @PathVariable String conversationId) {
        return batchResultQueryService.conversationDetail(id, conversationId);
    }

    @GetMapping(value = "/results/export", produces = "text/csv")
    @ResourcePermission(code = "iqc:result:export", name = "导出质检结果", type = "iqc", description = "导出质检结果")
    @Audit(operationType = OperationType.EXPORT, description = "导出 IQC 质检结果", module = "IQC_RESULT")
    @RateLimit(sceneCode = "iqc-result-export", maxCount = 10, period = 60)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String taskId,
                                         @RequestParam(required = false) String agentId,
                                         @RequestParam(required = false) String ownerId,
                                         @RequestParam(required = false) String groupId,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) Integer minScore,
                                         @RequestParam(required = false) Integer maxScore,
                                         @RequestParam(required = false) String speakerRole,
                                         @RequestParam(required = false) String riskLevel) {
        byte[] body = executionService.exportCsv(taskId, agentId, ownerId, groupId, status, minScore, maxScore, speakerRole, riskLevel).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=iqc-results.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(body);
    }
}
