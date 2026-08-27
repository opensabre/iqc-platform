package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.governance.ratelimit.annotations.RateLimit;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import io.github.opensabre.governance.usage.UsageOutcome;
import io.github.opensabre.governance.usage.UsageRecord;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import io.github.opensabre.iqc.shared.IqcPage;
import java.util.List;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/iqc/tasks")
public class InspectionTaskController {
    private final InspectionTaskService taskService;
    private final UsageCounterRecorder usageCounterRecorder;

    public InspectionTaskController(InspectionTaskService taskService, UsageCounterRecorder usageCounterRecorder) {
        this.taskService = taskService;
        this.usageCounterRecorder = usageCounterRecorder;
    }

    @GetMapping
    @ResourcePermission(code = "iqc:task:view", name = "查看质检任务", type = "iqc", description = "查询质检任务")
    @Audit(operationType = OperationType.QUERY, description = "查询 IQC 质检任务", module = "IQC_TASK")
    @RateLimit(sceneCode = "iqc-task-query", maxCount = 60, period = 60)
    public IqcPage<InspectionTask> list(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size) {
        return taskService.page(current, size);
    }

    @GetMapping("/{id}")
    @ResourcePermission(code = "iqc:task:view", name = "查看质检任务详情", type = "iqc", description = "查看质检任务详情")
    @Audit(operationType = OperationType.QUERY, description = "查看 IQC 质检任务详情", module = "IQC_TASK")
    public InspectionTask get(@PathVariable String id) {
        return taskService.get(id);
    }

    @PostMapping
    @ResourcePermission(code = "iqc:task:create", name = "创建质检任务", type = "iqc", description = "创建质检任务")
    @Audit(operationType = OperationType.CREATE, description = "创建 IQC 质检任务", module = "IQC_TASK")
    @RateLimit(sceneCode = "iqc-task-create", maxCount = 20, period = 60)
    public InspectionTask create(@RequestBody CreateTaskRequest request) {
        if ("SAMPLE".equalsIgnoreCase(request.taskType())) {
            InspectionTask task = taskService.createSampled(request.name(), request.selectionFilter(),
                    request.sampleSize() == null ? 100 : request.sampleSize(), request.sampleSeed(), request.agentId(),
                    request.ruleSetId(), request.ruleIds(), request.concurrencyLimit());
            usageCounterRecorder.record(new UsageRecord("inspection-task:create:" + task.getId(), null, "iqc-platform", "INSPECTION_TASK", task.getId(), "CREATE", UsageOutcome.SUCCESS));
            return task;
        }
        if ("SCHEDULED".equalsIgnoreCase(request.taskType())) {
            InspectionTask task = taskService.createScheduled(request.name(), request.selectionFilter(),
                    parseScheduledTime(request.scheduledTime()), request.agentId(), request.ruleSetId(), request.ruleIds(), request.concurrencyLimit());
            usageCounterRecorder.record(new UsageRecord("inspection-task:create:" + task.getId(), null, "iqc-platform", "INSPECTION_TASK", task.getId(), "CREATE", UsageOutcome.SUCCESS));
            return task;
        }
        List<String> conversationIds = request.conversationIds() == null || request.conversationIds().isEmpty()
                ? (request.conversationId() == null ? List.of() : List.of(request.conversationId())) : request.conversationIds();
        InspectionTask task = taskService.createBatch(request.name(), conversationIds, request.agentId(), request.ruleSetId(), request.ruleIds(), request.concurrencyLimit());
        usageCounterRecorder.record(new UsageRecord(
                "inspection-task:create:" + task.getId(), null, "iqc-platform", "INSPECTION_TASK",
                task.getId(), "CREATE", UsageOutcome.SUCCESS));
        return task;
    }

    private LocalDateTime parseScheduledTime(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDateTime.parse(value); }
        catch (java.time.format.DateTimeParseException exception) { throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("计划执行时间格式无效"); }
    }

    @PostMapping("/{id}/cancel")
    @ResourcePermission(code = "iqc:task:cancel", name = "取消质检任务", type = "iqc", description = "取消质检任务")
    @Audit(operationType = OperationType.UPDATE, description = "取消 IQC 质检任务", module = "IQC_TASK")
    public InspectionTask cancel(@PathVariable String id) {
        return taskService.cancel(id);
    }

    public record CreateTaskRequest(String name, String taskType, String conversationId, List<String> conversationIds,
                                    InspectionTaskService.ScheduledFilter selectionFilter, String scheduledTime, String agentId,
                                    String ruleSetId, List<String> ruleIds, Integer concurrencyLimit,
                                    Integer sampleSize, String sampleSeed) { }
}
