package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.iqc.result.llm.gateway.GatewayCallService;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/iqc/tasks/{id}/model-calls")
public class TaskModelCallController {
    private final GatewayCallService calls;
    private final io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive;
    private final io.github.opensabre.iqc.privacy.ResultReadProjection projection;
    private final io.github.opensabre.iqc.result.llm.gateway.GatewayCallDataService data;
    @org.springframework.beans.factory.annotation.Autowired
    public TaskModelCallController(GatewayCallService calls,io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive,
            io.github.opensabre.iqc.privacy.ResultReadProjection projection,io.github.opensabre.iqc.result.llm.gateway.GatewayCallDataService data){this.calls=calls;this.sensitive=sensitive;this.projection=projection;this.data=data;}
    public TaskModelCallController(GatewayCallService calls,io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive,
            io.github.opensabre.iqc.privacy.ResultReadProjection projection){this(calls,sensitive,projection,null);}
    public TaskModelCallController(GatewayCallService calls){this(calls,null,null,null);}
    private io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access callAccess(){
        if(sensitive==null || projection==null)throw io.github.opensabre.iqc.governance.IqcException.invalidState("模型调用读取授权组件不可用");
        return callAccess(java.util.Set.of("iqc:task:model-calls:view"));
    }
    private io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access callAccess(java.util.Set<String> required){
        if(sensitive==null || projection==null)throw io.github.opensabre.iqc.governance.IqcException.invalidState("模型调用读取授权组件不可用");
        var access=sensitive.current(required);
        if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes servlet && servlet.getResponse()!=null){
            servlet.getResponse().setHeader("Cache-Control","private, no-store");
            servlet.getResponse().setHeader("X-Content-Type-Options","nosniff");
        }
        return access;
    }

    @GetMapping
    @ResourcePermission(code="iqc:task:model-calls:view",name="查看任务模型调用",type="iqc",description="按任务数据范围查询真实执行轮次的模型调用")
    @io.github.opensabre.governance.audit.annotations.Audit(operationType=io.github.opensabre.governance.audit.annotations.OperationType.QUERY,description="读取任务模型调用账本",module="IQC_PRIVACY",request=false,response=false,key="#id")
    public GatewayCallService.CallPage page(@PathVariable String id,@RequestParam(required=false) String executionId,
            @RequestParam(required=false) String status,@RequestParam(defaultValue="1") long current,@RequestParam(defaultValue="20") long size,
            @RequestParam(required=false) String conversationId){
        if(conversationId==null)return page(id,executionId,status,current,size);
        var access=callAccess();
        return projection.gatewayCalls(calls.taskPageWithSummary(id,executionId,status,current,size,conversationId),access);
    }
    public GatewayCallService.CallPage page(String id,String executionId,String status,long current,long size){
        var access=callAccess();
        return projection.gatewayCalls(calls.taskPageWithSummary(id,executionId,status,current,size),access);
    }
    @GetMapping("/{callId}/diagnostics")
    @ResourcePermission(code="iqc:task:model-call-data:view",name="读取模型诊断正文",type="iqc",description="独立敏感授权读取本任务未过期加密诊断")
    @io.github.opensabre.governance.audit.annotations.Audit(operationType=io.github.opensabre.governance.audit.annotations.OperationType.QUERY,description="读取模型诊断正文",module="IQC_PRIVACY",request=false,response=false,key="#id + ':' + #callId")
    @io.github.opensabre.governance.ratelimit.annotations.RateLimit(sceneCode="iqc-model-call-data-read",maxCount=30,period=60)
    public io.github.opensabre.iqc.result.llm.gateway.GatewayCallDataService.View diagnostics(@PathVariable String id,@PathVariable String callId){
        var access=callAccess(java.util.Set.of("iqc:task:model-calls:view","iqc:task:model-call-data:view",io.github.opensabre.iqc.privacy.SensitiveReadAccessService.PROMPT));
        access.requireFullConversation();access.requirePii();access.requirePrompt();
        if(data==null)throw io.github.opensabre.iqc.governance.IqcException.invalidState("模型诊断读取组件不可用");
        return data.read(id,callId);
    }

}
