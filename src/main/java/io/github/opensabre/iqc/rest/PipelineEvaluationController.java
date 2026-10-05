package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.quality.evaluation.PipelineEvaluationService;
import io.github.opensabre.iqc.quality.model.EvaluationSampleResult;
import io.github.opensabre.iqc.shared.IqcPage;
import io.github.opensabre.iqc.task.model.InspectionTask;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/iqc/evaluation/tasks")
public class PipelineEvaluationController {
    private final PipelineEvaluationService service;
    @PostMapping
    @ResourcePermission(code="iqc:evaluation:task:create", name="创建冻结数据集评测任务", type="iqc", description="使用精确数据集与流水线版本创建离线评测")
    @Audit(operationType=OperationType.CREATE, description="创建 IQC 冻结数据集评测", module="IQC_EVALUATION")
    public InspectionTask create(@RequestBody Create request) {
        if (request == null || request.datasetVersion() == null) throw IqcException.invalidArgument("必须选择精确数据集版本");
        return service.create(request.datasetId(), request.datasetVersion(), request.pipelineId(), request.pipelineVersion(),
                request.draftRevision(), request.name(), request.concurrency(), request.requestId());
    }
    @GetMapping("/{taskId}/results")
    @ResourcePermission(code="iqc:evaluation:results:view", name="查看评测样本结果", type="iqc", description="分页查询精确执行轮次的评测结果摘要")
    public IqcPage<EvaluationSampleResult> page(@PathVariable String taskId, @RequestParam(required=false) String executionId,
            @RequestParam(defaultValue="1") long current, @RequestParam(defaultValue="20") long size) {
        return service.page(taskId, executionId, current, size);
    }
    @GetMapping("/{taskId}/results/{resultId}")
    @ResourcePermission(code="iqc:evaluation:result:view", name="查看评测样本轨迹", type="iqc", description="校验并查看不可变机器输出和样本来源")
    public EvaluationSampleResult result(@PathVariable String taskId, @PathVariable String resultId) { return service.result(taskId, resultId); }
    @GetMapping("/{taskId}/report")
    @ResourcePermission(code="iqc:evaluation:report:view", name="查看固定数据集评测报告", type="iqc", description="以完整金标分母计算并保留失败重试来源")
    public PipelineEvaluationService.Report report(@PathVariable String taskId, @RequestParam(required=false) String executionId) {
        return service.report(taskId, executionId);
    }
    public record Create(String datasetId, Integer datasetVersion, String pipelineId, Integer pipelineVersion,
                         Integer draftRevision, String name, Integer concurrency, String requestId) { }
}
