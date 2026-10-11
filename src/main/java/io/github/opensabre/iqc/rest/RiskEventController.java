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
    @GetMapping
    @ResourcePermission(code="iqc:risk:view",name="查看结果风险事件",type="iqc",description="查看已授权生产结果的实际风险事件")
    public List<RiskEventQueryService.View> list(@PathVariable String id){return queries.list(id);}
}
