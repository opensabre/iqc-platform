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
import io.github.opensabre.iqc.label.LabelResolutionService;

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
    @RateLimit(sceneCode = "iqc-task-query", maxCount = 60, period = 60)
    public IqcPage<InspectionTask> list(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String taskType) {
        return taskService.page(current, size, keyword, status, taskType);
    }

    @GetMapping("/{id}")
    @ResourcePermission(code = "iqc:task:view", name = "查看质检任务详情", type = "iqc", description = "查看质检任务详情")
    public InspectionTask get(@PathVariable String id) {
        return taskService.get(id);
    }

    @PostMapping
    @ResourcePermission(code = "iqc:task:create", name = "创建质检任务", type = "iqc", description = "创建质检任务")
    @Audit(operationType = OperationType.CREATE, description = "创建 IQC 质检任务", module = "IQC_TASK")
    @RateLimit(sceneCode = "iqc-task-create", maxCount = 20, period = 60)
    public InspectionTask create(@RequestBody CreateTaskRequest request) {
        if ("SAMPLE".equalsIgnoreCase(request.taskType())) {
            InspectionTask task = request.labelSelection() == null
                    ? taskService.createSampled(request.name(), request.selectionFilter(), request.sampleSize() == null ? 100 : request.sampleSize(), request.sampleSeed(), request.agentId(), request.ruleSetId(), request.ruleIds(), request.concurrencyLimit())
                    : taskService.createSampledWithLabels(request.name(), request.selectionFilter(), request.sampleSize() == null ? 100 : request.sampleSize(), request.sampleSeed(), request.agentId(), request.labelSelection(), request.concurrencyLimit(), request.labelOptions());
            usageCounterRecorder.record(new UsageRecord("inspection-task:create:" + task.getId(), null, "iqc-platform", "INSPECTION_TASK", task.getId(), "CREATE", UsageOutcome.SUCCESS));
            return task;
        }
        if ("SCHEDULED".equalsIgnoreCase(request.taskType())) {
            InspectionTask task = request.labelSelection() == null
                    ? taskService.createScheduled(request.name(), request.selectionFilter(), parseScheduledTime(request.scheduledTime()), request.agentId(), request.ruleSetId(), request.ruleIds(), request.concurrencyLimit())
                    : taskService.createScheduledWithLabels(request.name(), request.selectionFilter(), parseScheduledTime(request.scheduledTime()), request.agentId(), request.labelSelection(), request.concurrencyLimit(), request.labelOptions());
            usageCounterRecorder.record(new UsageRecord("inspection-task:create:" + task.getId(), null, "iqc-platform", "INSPECTION_TASK", task.getId(), "CREATE", UsageOutcome.SUCCESS));
            return task;
        }
        List<String> conversationIds = request.conversationIds() == null || request.conversationIds().isEmpty()
                ? (request.conversationId() == null ? List.of() : List.of(request.conversationId())) : request.conversationIds();
        InspectionTask task = request.labelSelection() == null
                ? taskService.createBatch(request.name(), conversationIds, request.agentId(), request.ruleSetId(), request.ruleIds(), request.concurrencyLimit())
                : taskService.createBatchWithLabels(request.name(), conversationIds, request.agentId(), request.labelSelection(), request.concurrencyLimit(), request.labelOptions());
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

    @PostMapping("/{id}/pause")
    @ResourcePermission(code = "iqc:task:execute", name = "暂停质检任务", type = "iqc", description = "在安全边界暂停执行中的质检任务")
    @Audit(operationType = OperationType.UPDATE, description = "暂停 IQC 质检任务", module = "IQC_TASK")
    public InspectionTask pause(@PathVariable String id) {
        return taskService.requestPause(id);
    }

    @PutMapping("/{id}/priority")
    @ResourcePermission(code = "iqc:task:execute", name = "调整任务优先级", type = "iqc", description = "调整未完成任务的队列优先级")
    @Audit(operationType = OperationType.UPDATE, description = "调整 IQC 任务优先级", module = "IQC_TASK")
    public InspectionTask priority(@PathVariable String id, @RequestBody Map<String, Long> request) {
        return taskService.changePriority(id, request.getOrDefault("priority", 0L));
    }

    @DeleteMapping("/{id}")
    @ResourcePermission(code = "iqc:task:delete", name = "删除质检任务", type = "iqc", description = "逻辑删除终态质检任务")
    @Audit(operationType = OperationType.DELETE, description = "删除 IQC 质检任务", module = "IQC_TASK")
    public void delete(@PathVariable String id) {
        taskService.deleteTerminal(id);
    }

    public record CreateTaskRequest(String name, String taskType, String conversationId, List<String> conversationIds,
                                    InspectionTaskService.ScheduledFilter selectionFilter, String scheduledTime, String agentId,
                                    String ruleSetId, List<String> ruleIds, Integer concurrencyLimit,
                                    Integer sampleSize, String sampleSeed,
                                    LabelResolutionService.LabelSelection labelSelection,
                                    InspectionTaskService.LabelExecutionOptions labelOptions) { }
}
