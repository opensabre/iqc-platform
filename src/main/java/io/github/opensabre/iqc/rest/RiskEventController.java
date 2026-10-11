package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.iqc.risk.RiskEventQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/iqc/conversation-results/{id}/risk-events")
public class RiskEventController {
    private final RiskEventQueryService queries;
    private final io.github.opensabre.iqc.risk.RiskHandlingService handling;
    public record HandlingRequest(String version,String status,String reason) { }
    public record HandlingResponse(String id,String state) { }
    @GetMapping
    @ResourcePermission(code="iqc:risk:view",name="查看结果风险事件",type="iqc",description="查看已授权生产结果的实际风险事件")
    public List<RiskEventQueryService.View> list(@PathVariable String id){return queries.list(id);}
    @PostMapping("/{riskEventId}/handle")
    @ResourcePermission(code="iqc:risk:handle",name="处置风险事件",type="iqc",description="按观察版本处置实际风险事件并保存不可变原因")
    @io.github.opensabre.governance.audit.annotations.Audit(operationType=io.github.opensabre.governance.audit.annotations.OperationType.UPDATE,description="处置风险事件",module="IQC_RISK")
    @io.github.opensabre.governance.ratelimit.annotations.RateLimit(sceneCode="iqc-risk-handle",maxCount=20,period=60)
    public HandlingResponse handle(@PathVariable String id,@PathVariable String riskEventId,@RequestBody HandlingRequest request) {
        if(request==null)throw io.github.opensabre.iqc.governance.IqcException.invalidArgument("风险处置请求缺失");
        boolean changed=handling.handle(id,riskEventId,request.version(),request.status(),request.reason());
        return new HandlingResponse(riskEventId,changed?"HANDLED":"STALE_OBSERVATION");
    }
}
