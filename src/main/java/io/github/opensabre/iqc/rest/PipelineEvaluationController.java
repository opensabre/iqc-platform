package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.quality.evaluation.PipelineEvaluationService;
import io.github.opensabre.iqc.quality.model.EvaluationSampleResult;
import io.github.opensabre.iqc.shared.IqcPage;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/iqc/evaluation/tasks")
public class PipelineEvaluationController {
    private final PipelineEvaluationService service;
    private final io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive;
    private final io.github.opensabre.iqc.privacy.ResultReadProjection projection;
    @org.springframework.beans.factory.annotation.Autowired
    public PipelineEvaluationController(PipelineEvaluationService service,
            io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive,
            io.github.opensabre.iqc.privacy.ResultReadProjection projection) {
        this.service=service;this.sensitive=sensitive;this.projection=projection;
    }
    public PipelineEvaluationController(PipelineEvaluationService service){this(service,null,null);}
    private io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access access(String resource){
        if(sensitive==null || projection==null)throw IqcException.invalidState("评测敏感读取授权组件不可用");
        var access=sensitive.current(java.util.Set.of(resource));
        if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes servlet && servlet.getResponse()!=null){
            servlet.getResponse().setHeader("Cache-Control","private, no-store");
            servlet.getResponse().setHeader("X-Content-Type-Options","nosniff");
        }
        return access;
    }
    @PostMapping
    @ResourcePermission(code="iqc:evaluation:task:create", name="创建冻结数据集评测任务", type="iqc", description="使用精确数据集与流水线版本创建离线评测")
    @Audit(operationType=OperationType.CREATE, description="创建 IQC 冻结数据集评测", module="IQC_EVALUATION", request=false, response=false)
    public InspectionTask create(@RequestBody Create request) {
        var access=access("iqc:evaluation:task:create");
        if (request == null || request.datasetVersion() == null) throw IqcException.invalidArgument("必须选择精确数据集版本");
        return projection.taskReceipt(service.create(request.datasetId(), request.datasetVersion(), request.pipelineId(), request.pipelineVersion(),
                request.draftRevision(), request.name(), request.concurrency(), request.requestId()),access);
    }
    @GetMapping("/{taskId}/results")
    @ResourcePermission(code="iqc:evaluation:results:view", name="查看评测样本结果", type="iqc", description="分页查询精确执行轮次的评测结果摘要")
    @Audit(operationType=OperationType.QUERY, description="读取评测结果分页", module="IQC_PRIVACY", request=false, response=false, key="#taskId")
    public IqcPage<EvaluationSampleResult> page(@PathVariable String taskId, @RequestParam(required=false) String executionId,
            @RequestParam(defaultValue="1") long current, @RequestParam(defaultValue="20") long size) {
        var access=access("iqc:evaluation:results:view");
        var page=service.page(taskId,executionId,current,size);
        return new IqcPage<>(page.records().stream().map(row -> projection.evaluationResult(row,access,false)).toList(),page.current(),page.size(),page.total());
    }
    @GetMapping("/{taskId}/results/{resultId}")
    @ResourcePermission(code="iqc:evaluation:result:view", name="查看评测样本轨迹", type="iqc", description="校验并查看不可变机器输出和样本来源")
    @Audit(operationType=OperationType.QUERY, description="读取评测样本产物", module="IQC_PRIVACY", request=false, response=false, key="#resultId")
    public EvaluationSampleResult result(@PathVariable String taskId, @PathVariable String resultId) {
        var access=access("iqc:evaluation:result:view");
        return projection.evaluationResult(service.result(taskId,resultId),access,true);
    }
    @GetMapping("/{taskId}/report")
    @ResourcePermission(code="iqc:evaluation:report:view", name="查看固定数据集评测报告", type="iqc", description="以完整金标分母计算并保留失败重试来源")
    @Audit(operationType=OperationType.QUERY, description="读取评测及独立重复运行报告", module="IQC_PRIVACY", request=false, response=false, key="#taskId")
    public Object report(@PathVariable String taskId,@RequestParam(required=false) String executionId,
            @RequestParam(required=false) String referenceTaskId,@RequestParam(required=false) String referenceExecutionId){
        if(referenceTaskId==null&&referenceExecutionId==null)return report(taskId,executionId);
        return consistency(taskId,executionId,referenceTaskId,referenceExecutionId);
    }
    /** Preserve the embedded single-execution report contract and immutable publication evidence. */
    public PipelineEvaluationService.Report report(String taskId,String executionId){
        access("iqc:evaluation:report:view");return service.report(taskId,executionId);
    }
    /** Same registered report resource; explicit reference parameters select the independent comparison. */
    public io.github.opensabre.iqc.quality.evaluation.ModelConsistencyMetrics.Report consistency(String taskId,String executionId,
            String referenceTaskId,String referenceExecutionId){
        access("iqc:evaluation:report:view");
        if(executionId==null||executionId.isBlank()||referenceTaskId==null||referenceTaskId.isBlank()
                ||referenceExecutionId==null||referenceExecutionId.isBlank())throw IqcException.invalidArgument("一致性报告必须指定双方Task和执行轮次");
        return service.consistency(taskId,executionId,referenceTaskId,referenceExecutionId);
    }
    public record Create(String datasetId, Integer datasetVersion, String pipelineId, Integer pipelineVersion,
                         Integer draftRevision, String name, Integer concurrency, String requestId) { }
}
