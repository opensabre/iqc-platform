package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.iqc.result.llm.gateway.GatewayCallService;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/iqc/model-gateway")
public class ModelGatewayController {
    private final GatewayCallService calls;
    private final io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive;
    private final io.github.opensabre.iqc.privacy.ResultReadProjection projection;
    @org.springframework.beans.factory.annotation.Autowired
    public ModelGatewayController(GatewayCallService calls,io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive,
            io.github.opensabre.iqc.privacy.ResultReadProjection projection){this.calls=calls;this.sensitive=sensitive;this.projection=projection;}
    public ModelGatewayController(GatewayCallService calls){this(calls,null,null);}
    private io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access callAccess(){
        if(sensitive==null || projection==null)throw io.github.opensabre.iqc.governance.IqcException.invalidState("模型调用读取授权组件不可用");
        var access=sensitive.current(java.util.Set.of("iqc:model-gateway:calls:view"));
        if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes servlet && servlet.getResponse()!=null){
            servlet.getResponse().setHeader("Cache-Control","private, no-store");
            servlet.getResponse().setHeader("X-Content-Type-Options","nosniff");
        }
        return access;
    }

    @GetMapping("/calls")
    @ResourcePermission(code="iqc:model-gateway:calls:view",name="查看模型调用事实",type="iqc",description="质检管理员查看模型并发预留和真实用量")
    @io.github.opensabre.governance.audit.annotations.Audit(operationType=io.github.opensabre.governance.audit.annotations.OperationType.QUERY,description="读取模型调用账本",module="IQC_PRIVACY",request=false,response=false)
    public GatewayCallService.CallPage calls(@RequestParam(required=false) String modelKey,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="1") long current,@RequestParam(defaultValue="20") long size){
        var access=callAccess();
        return projection.gatewayCalls(calls.pageWithSummary(modelKey,status,current,size),access);
    }
    @GetMapping("/capacity/{modelKey}")
    @ResourcePermission(code="iqc:model-gateway:capacity:view",name="查看模型容量",type="iqc",description="质检管理员查看模型并发占用和配置版本")
    public GatewayCallService.CapacityView capacity(@PathVariable String modelKey){return calls.getCapacity(modelKey);}
    public record ConfigureCapacity(@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.ExactValueDeserializer.class) @tools.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.HttpExactValueDeserializer.class) com.fasterxml.jackson.databind.JsonNode concurrencyLimit,String expectedVersion,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.ExactValueDeserializer.class) @tools.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.HttpExactValueDeserializer.class) com.fasterxml.jackson.databind.JsonNode budget) {
        public ConfigureCapacity(com.fasterxml.jackson.databind.JsonNode concurrencyLimit,String expectedVersion){this(concurrencyLimit,expectedVersion,null);}
    }
    @PutMapping("/capacity/{modelKey}")
    @ResourcePermission(code="iqc:model-gateway:capacity:configure",name="配置模型容量",type="iqc",description="质检管理员按版本调整全局模型并发及累计预算")
    @io.github.opensabre.governance.audit.annotations.Audit(operationType=io.github.opensabre.governance.audit.annotations.OperationType.UPDATE,
            description="调整模型容量及预算",module="IQC_MODEL_GATEWAY",request=false,response=true)
    public GatewayCallService.CapacityChange configure(@PathVariable String modelKey,@RequestBody ConfigureCapacity request){
        if(request==null || request.concurrencyLimit()==null || !request.concurrencyLimit().isIntegralNumber() || !request.concurrencyLimit().canConvertToInt()
                || request.expectedVersion()==null || !request.expectedVersion().matches("0|[1-9][0-9]{0,18}"))
            throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("并发上限和版本必须为精确整数");
        try{return calls.configureCapacity(modelKey,request.concurrencyLimit().intValue(),Long.parseLong(request.expectedVersion()),request.budget());}
        catch(NumberFormatException invalid){throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("容量版本无效");}
    }
    public record ReleaseCapacity(String modelKey,GatewayCallService.ReleaseReason reason,String evidence,Boolean remoteStopped) { }
    @PostMapping("/calls/{callId}/capacity-release")
    @ResourcePermission(code="iqc:model-gateway:calls:release",name="恢复模型并发占用",type="iqc",description="质检管理员核对远端停止凭据后释放未知调用占用")
    @io.github.opensabre.governance.audit.annotations.Audit(operationType=io.github.opensabre.governance.audit.annotations.OperationType.UPDATE,
            description="核对恢复模型并发占用",module="IQC_MODEL_GATEWAY",request=false,response=true)
    public GatewayCallService.CapacityView release(@PathVariable String callId,@RequestBody ReleaseCapacity request){
        if(request==null)throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("缺少恢复凭据");
        return calls.releaseCapacity(callId,request.modelKey(),request.reason(),request.evidence(),Boolean.TRUE.equals(request.remoteStopped()));
    }
    public record ReconcileBudget(String modelKey,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.ExactValueDeserializer.class) @tools.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.HttpExactValueDeserializer.class) com.fasterxml.jackson.databind.JsonNode tokens,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.ExactValueDeserializer.class) @tools.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.HttpExactValueDeserializer.class) com.fasterxml.jackson.databind.JsonNode cost,
            String currency,String evidence,Boolean verified) { }
    @PostMapping("/calls/{callId}/budget-reconciliation")
    @ResourcePermission(code="iqc:model-gateway:calls:reconcile",name="核对模型预算",type="iqc",description="质检管理员根据供应商最终凭据核对预算承诺")
    @io.github.opensabre.governance.audit.annotations.Audit(operationType=io.github.opensabre.governance.audit.annotations.OperationType.UPDATE,
            description="核对模型预算",module="IQC_MODEL_GATEWAY",request=false,response=true)
    public GatewayCallService.ReconciliationChange reconcile(@PathVariable String callId,@RequestBody ReconcileBudget request){
        if(request==null || request.tokens()==null || !request.tokens().isTextual() || !request.tokens().textValue().matches("0|[1-9][0-9]{0,18}")
                || (request.cost()!=null && !request.cost().isNull() && (!request.cost().isTextual()
                    || !request.cost().textValue().matches("(?:0|[1-9][0-9]{0,17})(?:\\.[0-9]{1,18})?"))))
            throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("对账Token和金额必须为精确非负数字字符串");
        try{return calls.reconcileBudget(callId,request.modelKey(),Long.parseLong(request.tokens().textValue()),
            request.cost()==null || request.cost().isNull()?null:new java.math.BigDecimal(request.cost().textValue()),request.currency(),request.evidence(),Boolean.TRUE.equals(request.verified()));}
        catch(NumberFormatException invalid){throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("对账Token超出范围");}
    }

    public record RecoverBudget(String expectedVersion,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.ExactValueDeserializer.class) @tools.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.HttpExactValueDeserializer.class) com.fasterxml.jackson.databind.JsonNode budget) { }
    @PostMapping("/capacity/{modelKey}/budget-recovery")
    @ResourcePermission(code="iqc:model-gateway:budget:recover",name="恢复模型预算隔离",type="iqc",description="管理员完成最终对账并重新核定供应商契约后恢复模型预算")
    @io.github.opensabre.governance.audit.annotations.Audit(operationType=io.github.opensabre.governance.audit.annotations.OperationType.UPDATE,
            description="重新核定并恢复模型预算隔离",module="IQC_MODEL_GATEWAY",request=false,response=true)
    public GatewayCallService.CapacityChange recoverBudget(@PathVariable String modelKey,@RequestBody RecoverBudget request){
        if(request==null || request.expectedVersion()==null || !request.expectedVersion().matches("0|[1-9][0-9]{0,18}"))
            throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("恢复隔离需要有效政策版本");
        try{return calls.recoverBudget(modelKey,Long.parseLong(request.expectedVersion()),request.budget());}
        catch(NumberFormatException invalid){throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("政策版本超出范围");}
    }

}
