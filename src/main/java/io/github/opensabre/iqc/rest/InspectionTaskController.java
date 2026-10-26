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

    private final io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive;
    private final io.github.opensabre.iqc.privacy.ResultReadProjection projection;
    @org.springframework.beans.factory.annotation.Autowired
    public InspectionTaskController(InspectionTaskService taskService, UsageCounterRecorder usageCounterRecorder,
            io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive,
            io.github.opensabre.iqc.privacy.ResultReadProjection projection) {
        this.taskService=taskService;this.usageCounterRecorder=usageCounterRecorder;
        this.sensitive=sensitive;this.projection=projection;
    }
    public InspectionTaskController(InspectionTaskService taskService, UsageCounterRecorder usageCounterRecorder) {
        this(taskService,usageCounterRecorder,null,null);
    }
    private io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access access(String resource) {
        if(sensitive==null || projection==null)throw io.github.opensabre.iqc.governance.IqcException.invalidState("任务敏感响应授权组件不可用");
        return access(java.util.Set.of(resource));
    }
    private io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access access(java.util.Set<String> required) {
        if(sensitive==null || projection==null)throw io.github.opensabre.iqc.governance.IqcException.invalidState("任务敏感响应授权组件不可用");
        var access=sensitive.current(required);
        if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes servlet && servlet.getResponse()!=null) {
            servlet.getResponse().setHeader("Cache-Control","private, no-store");
            servlet.getResponse().setHeader("X-Content-Type-Options","nosniff");
        }
        return access;
    }

    @GetMapping
    @ResourcePermission(code = "iqc:task:view", name = "查看质检任务", type = "iqc", description = "查询质检任务")
    @RateLimit(sceneCode = "iqc-task-query", maxCount = 60, period = 60)
    @Audit(operationType=OperationType.QUERY, description="读取任务目录", module="IQC_PRIVACY", request=false, response=false)
    public IqcPage<InspectionTask> list(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String taskType,
                                       @RequestParam(required = false) String parentTaskId) {
        var access=access("iqc:task:view");
        var page=taskService.page(current,size,keyword,status,taskType,parentTaskId);
        return new IqcPage<>(page.records().stream().map(row -> projection.taskRead(row,access,false)).toList(),page.current(),page.size(),page.total());
    }

    public IqcPage<InspectionTask> list(long current,long size,String keyword,String status,String taskType) {
        return list(current,size,keyword,status,taskType,null);
    }

    @GetMapping("/{id}")
    @ResourcePermission(code = "iqc:task:view", name = "查看质检任务详情", type = "iqc", description = "查看质检任务详情")
    @Audit(operationType=OperationType.QUERY, description="读取任务详情", module="IQC_PRIVACY", request=false, response=false, key="#id")
    public InspectionTask get(@PathVariable String id) {
        var access=access("iqc:task:view");
        return projection.taskRead(taskService.get(id),access,true);
    }

    @GetMapping("/{id}/configuration")
    @ResourcePermission(code="iqc:sensitive:prompt:read",name="读取任务冻结配置与Prompt",type="iqc",description="在原任务范围内独立授权读取冻结配置")
    @Audit(operationType=OperationType.QUERY,description="读取任务冻结配置",module="IQC_PRIVACY",request=false,response=false,key="#id")
    @RateLimit(sceneCode="iqc-task-configuration-query",maxCount=30,period=60)
    public InspectionTask configuration(@PathVariable String id){
        var access=access(java.util.Set.of("iqc:task:view",io.github.opensabre.iqc.privacy.SensitiveReadAccessService.PROMPT));
        access.requireFullConversation();access.requirePii();access.requirePrompt();
        return projection.taskRead(taskService.get(id),access,true);
    }

    @GetMapping("/{id}/executions")
    @ResourcePermission(code = "iqc:task:view", name = "查看任务执行记录", type = "iqc", description = "按任务可见范围查看执行轮次")
    @RateLimit(sceneCode = "iqc-task-execution-query", maxCount = 60, period = 60)
    @Audit(operationType=OperationType.QUERY, description="读取任务执行轮次", module="IQC_PRIVACY", request=false, response=false, key="#id")
    public List<InspectionTaskService.TaskRunSummary> executions(@PathVariable String id) {
        access("iqc:task:view");
        return taskService.executions(id);
    }

    @PostMapping
    @ResourcePermission(code = "iqc:task:create", name = "创建质检任务", type = "iqc", description = "创建质检任务")
    @Audit(operationType = OperationType.CREATE, description = "创建 IQC 质检任务", module = "IQC_TASK", request = false, response = false)
    @RateLimit(sceneCode = "iqc-task-create", maxCount = 20, period = 60)
    public InspectionTask create(@RequestBody CreateTaskRequest request) {
        var access=access("iqc:task:create");
        List<String> conversationIds = request.conversationIds() == null || request.conversationIds().isEmpty()
                ? (request.conversationId() == null ? List.of() : List.of(request.conversationId())) : request.conversationIds();
        InspectionTask task = taskService.createConfigured(request.name(), request.taskType(), conversationIds,
                request.selectionFilter(), "SCHEDULED".equalsIgnoreCase(request.taskType()) ? parseScheduledTime(request.scheduledTime()) : null,
                request.sampleSize(), request.sampleSeed(), request.agentId(), request.ruleSetId(), request.ruleIds(),
                request.concurrencyLimit(), request.labelSelection(), request.labelOptions(), request.executionMode());
        usageCounterRecorder.record(new UsageRecord(
                "inspection-task:create:" + task.getId(), null, "iqc-platform", "INSPECTION_TASK",
                task.getId(), "CREATE", UsageOutcome.SUCCESS));
        return projection.taskReceipt(task,access);
    }

    private LocalDateTime parseScheduledTime(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDateTime.parse(value); }
        catch (java.time.format.DateTimeParseException exception) { throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("计划执行时间格式无效"); }
    }

    @PostMapping("/{id}/cancel")
    @ResourcePermission(code = "iqc:task:cancel", name = "取消质检任务", type = "iqc", description = "取消质检任务")
    @Audit(operationType = OperationType.UPDATE, description = "取消 IQC 质检任务", module = "IQC_TASK", request = false, response = false, key = "#id")
    public InspectionTask cancel(@PathVariable String id) {
        var access=access("iqc:task:cancel");
        return projection.taskReceipt(taskService.cancel(id),access);
    }

    @PostMapping("/{id}/pause")
    @ResourcePermission(code = "iqc:task:execute", name = "暂停质检任务", type = "iqc", description = "在安全边界暂停执行中的质检任务")
    @Audit(operationType = OperationType.UPDATE, description = "暂停 IQC 质检任务", module = "IQC_TASK", request = false, response = false, key = "#id")
    public InspectionTask pause(@PathVariable String id) {
        var access=access("iqc:task:execute");
        return projection.taskReceipt(taskService.requestPause(id),access);
    }

    @PutMapping("/{id}/priority")
    @ResourcePermission(code = "iqc:task:execute", name = "调整任务优先级", type = "iqc", description = "调整未完成任务的队列优先级")
    @Audit(operationType = OperationType.UPDATE, description = "调整 IQC 任务优先级", module = "IQC_TASK", request = false, response = false, key = "#id")
    public InspectionTask priority(@PathVariable String id, @RequestBody Map<String, Long> request) {
        var access=access("iqc:task:execute");
        return projection.taskReceipt(taskService.changePriority(id, request.getOrDefault("priority", 0L)),access);
    }

    @DeleteMapping("/{id}")
    @ResourcePermission(code = "iqc:task:delete", name = "删除质检任务", type = "iqc", description = "逻辑删除终态质检任务")
    @Audit(operationType = OperationType.DELETE, description = "删除 IQC 质检任务", module = "IQC_TASK", request = false, response = false, key = "#id")
    public void delete(@PathVariable String id) {
        access("iqc:task:delete");
        taskService.deleteTerminal(id);
    }

    public record CreateTaskRequest(String name, String taskType, String conversationId, List<String> conversationIds,
                                    InspectionTaskService.ScheduledFilter selectionFilter, String scheduledTime, String agentId,
                                    String ruleSetId, List<String> ruleIds, Integer concurrencyLimit,
                                    Integer sampleSize, String sampleSeed,
                                    LabelResolutionService.LabelSelection labelSelection,
                                    InspectionTaskService.LabelExecutionOptions labelOptions,
                                    @io.swagger.v3.oas.annotations.media.Schema(description = "任务执行策略；省略保留旧 Agent 模式。RULE_ONLY 无需 Agent。", allowableValues = {"RULE_ONLY", "RULE_THEN_LLM", "LLM_THEN_RULE", "AGENT_LLM", "INDEPENDENT"})
                                    String executionMode) { }
}
