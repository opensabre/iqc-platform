package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.iqc.risk.RiskCenterQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/iqc/risk-events")
public class RiskCenterController {
    private final RiskCenterQueryService queries;
    @GetMapping
    @ResourcePermission(code="iqc:risk:center:view",name="查看风险中心",type="iqc",description="按原结果范围分页查看已验证的实际风险事件")
    public RiskCenterQueryService.Page page(@RequestParam(required=false) String afterId,
            @RequestParam(required=false) String status,@RequestParam(required=false) String riskLevel){return queries.page(afterId,status,riskLevel);}
}
