package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.iqc.result.llm.gateway.GatewayCallService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/iqc/model-gateway")
public class ModelGatewayController {
    private final GatewayCallService calls;
    @GetMapping("/calls")
    @ResourcePermission(code="iqc:model-gateway:calls:view",name="查看模型调用事实",type="iqc",description="质检管理员查看模型并发预留和真实用量")
    public GatewayCallService.CallPage calls(@RequestParam(required=false) String modelKey,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="1") long current,@RequestParam(defaultValue="20") long size){return calls.pageWithSummary(modelKey,status,current,size);}
    @GetMapping("/capacity/{modelKey}")
    @ResourcePermission(code="iqc:model-gateway:capacity:view",name="查看模型容量",type="iqc",description="质检管理员查看模型并发占用和配置版本")
    public GatewayCallService.CapacityView capacity(@PathVariable String modelKey){return calls.getCapacity(modelKey);}
    public record ConfigureCapacity(com.fasterxml.jackson.databind.JsonNode concurrencyLimit,String expectedVersion,com.fasterxml.jackson.databind.JsonNode budget) {
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
    public record ReconcileBudget(String modelKey,com.fasterxml.jackson.databind.JsonNode tokens,com.fasterxml.jackson.databind.JsonNode cost,
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

}
