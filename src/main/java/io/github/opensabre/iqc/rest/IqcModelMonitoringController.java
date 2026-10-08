package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.iqc.result.llm.gateway.ModelMonitoringService;
import io.github.opensabre.iqc.shared.IqcModelMonitoringClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/iqc/model-gateway/monitoring")
public class IqcModelMonitoringController {
    private final ModelMonitoringService monitoring;
    @GetMapping("/history")
    @ResourcePermission(code="iqc:model:monitoring:view",name="查看模型运行监控",type="iqc",description="质检管理员查看固定模型性能历史指标")
    public IqcModelMonitoringClient.History history(@RequestParam(defaultValue="1h") String range){return monitoring.history(range);}
}
