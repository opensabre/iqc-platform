package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.quality.evaluation.BenchmarkService;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/iqc/evaluation/benchmarks")
public class BenchmarkController {
    private final BenchmarkService service;
    private final io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive;
    private final io.github.opensabre.iqc.privacy.ResultReadProjection projection;
    @org.springframework.beans.factory.annotation.Autowired
    public BenchmarkController(BenchmarkService service,
            io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive,
            io.github.opensabre.iqc.privacy.ResultReadProjection projection){
        this.service=service;this.sensitive=sensitive;this.projection=projection;
    }
    public BenchmarkController(BenchmarkService service){this(service,null,null);}
    private io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access access(String resource){
        if(sensitive==null || projection==null)throw io.github.opensabre.iqc.governance.IqcException.invalidState("对照实验敏感读取授权组件不可用");
        var access=sensitive.current(java.util.Set.of(resource));
        if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes servlet && servlet.getResponse()!=null){
            servlet.getResponse().setHeader("Cache-Control","private, no-store");
            servlet.getResponse().setHeader("X-Content-Type-Options","nosniff");
        }
        return access;
    }
    @PostMapping
    @ResourcePermission(code="iqc:benchmark:create",name="创建单变量对照实验",type="iqc",description="同一冻结数据集创建双侧评测任务")
    @Audit(operationType=OperationType.CREATE,description="创建单变量对照实验",module="IQC_EVALUATION",request=false,response=false)
    public BenchmarkService.Pair create(@RequestBody BenchmarkService.Create request){
        var access=access("iqc:benchmark:create");
        return projection.benchmarkPair(service.create(request),access);
    }
    @GetMapping("/{baselineTaskId}")
    @ResourcePermission(code="iqc:benchmark:view",name="查看对照实验",type="iqc",description="验证双向绑定和冻结单变量")
    @Audit(operationType=OperationType.QUERY,description="读取对照实验摘要",module="IQC_PRIVACY",request=false,response=false,key="#baselineTaskId")
    public BenchmarkService.Pair pair(@PathVariable String baselineTaskId){
        var access=access("iqc:benchmark:view");
        return projection.benchmarkPair(service.pair(baselineTaskId),access);
    }
    @GetMapping("/{baselineTaskId}/report")
    @ResourcePermission(code="iqc:benchmark:report:view",name="查看对照报告",type="iqc",description="固定双侧执行轮次和完整金标分母")
    @Audit(operationType=OperationType.QUERY,description="读取对照实验报告",module="IQC_PRIVACY",request=false,response=false,key="#baselineTaskId")
    public BenchmarkService.Report report(@PathVariable String baselineTaskId,@RequestParam String baselineExecutionId,
                                         @RequestParam String candidateExecutionId){
        var access=access("iqc:benchmark:report:view");
        return projection.benchmarkReport(service.report(baselineTaskId,baselineExecutionId,candidateExecutionId),access);
    }
}
