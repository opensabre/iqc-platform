package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.iqc.action.ActionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import io.github.opensabre.iqc.action.ActionDiscoveryRequestService;
import io.github.opensabre.iqc.action.ActionDiscoveryRequestStateService;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.governance.ratelimit.annotations.RateLimit;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/iqc/conversation-results/{id}/actions")
public class ActionExecutionController {
    private final ActionQueryService queries;
    private final ActionDiscoveryRequestService discovery;
    @GetMapping
    @ResourcePermission(code="iqc:action:history:view",name="查看结果动作记录",type="iqc",description="查看授权会话结果的动作状态，不返回冻结输入或策略配置")
    public List<ActionQueryService.View> history(@PathVariable String id){return queries.history(id);}
    @PostMapping("/enqueue")
    @ResourcePermission(code="iqc:action:enqueue",name="请求生产结果动作检查",type="iqc",description="安排负责人生产结果的后台动作检查，不直接发送或重试动作")
    @Audit(operationType=OperationType.UPDATE,description="请求生产结果动作检查",module="IQC_ACTION")
    @RateLimit(sceneCode="iqc-action-discovery-request",maxCount=20,period=60)
    public ActionDiscoveryRequestStateService.Response request(@PathVariable String id){return discovery.request(id);}
}
