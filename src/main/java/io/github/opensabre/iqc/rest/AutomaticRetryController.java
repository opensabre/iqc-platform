package io.github.opensabre.iqc.rest;
import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.*;
import io.github.opensabre.iqc.task.retry.*;
import io.github.opensabre.iqc.governance.IqcException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/iqc/tasks/{id}/retry-policy")
public class AutomaticRetryController {
    private final AutomaticRetryService retries;
    public record Configure(AutomaticRetryPolicy policy) { }
    public record Availability(Boolean enabled) { }
    @GetMapping
    @ResourcePermission(code="iqc:task:retry-policy:view",name="查看自动重试策略",type="iqc",description="查看冻结重试策略和等待状态")
    public AutomaticRetryService.View get(@PathVariable String id){return retries.get(id);}
    @PutMapping
    @ResourcePermission(code="iqc:task:retry-policy:configure",name="配置自动重试策略",type="iqc",description="首次排队前冻结生产任务重试策略")
    @Audit(operationType=OperationType.UPDATE,description="配置自动重试",module="IQC_TASK",request=false,response=true)
    public AutomaticRetryService.View configure(@PathVariable String id,@RequestBody Configure request){
        if(request==null || request.policy()==null)throw IqcException.invalidArgument("缺少重试策略");return retries.configure(id,request.policy());}
    @PostMapping("/availability")
    @ResourcePermission(code="iqc:task:retry-policy:availability",name="启停自动重试",type="iqc",description="启停冻结策略而不重置轮次")
    @Audit(operationType=OperationType.UPDATE,description="启停自动重试",module="IQC_TASK",request=false,response=true)
    public AutomaticRetryService.View availability(@PathVariable String id,@RequestBody Availability request){
        if(request==null || request.enabled()==null)throw IqcException.invalidArgument("缺少启停标记");return retries.availability(id,request.enabled());}
}
