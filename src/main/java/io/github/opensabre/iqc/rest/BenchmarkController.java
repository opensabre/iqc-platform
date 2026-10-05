package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.quality.evaluation.BenchmarkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/iqc/evaluation/benchmarks")
public class BenchmarkController {
    private final BenchmarkService service;
    @PostMapping
    @ResourcePermission(code="iqc:benchmark:create",name="创建单变量对照实验",type="iqc",description="同一冻结数据集创建双侧评测任务")
    @Audit(operationType=OperationType.CREATE,description="创建单变量对照实验",module="IQC_EVALUATION")
    public BenchmarkService.Pair create(@RequestBody BenchmarkService.Create request){return service.create(request);}
    @GetMapping("/{baselineTaskId}")
    @ResourcePermission(code="iqc:benchmark:view",name="查看对照实验",type="iqc",description="验证双向绑定和冻结单变量")
    public BenchmarkService.Pair pair(@PathVariable String baselineTaskId){return service.pair(baselineTaskId);}
    @GetMapping("/{baselineTaskId}/report")
    @ResourcePermission(code="iqc:benchmark:report:view",name="查看对照报告",type="iqc",description="固定双侧执行轮次和完整金标分母")
    public BenchmarkService.Report report(@PathVariable String baselineTaskId,@RequestParam String baselineExecutionId,
                                         @RequestParam String candidateExecutionId){return service.report(baselineTaskId,baselineExecutionId,candidateExecutionId);}
}
