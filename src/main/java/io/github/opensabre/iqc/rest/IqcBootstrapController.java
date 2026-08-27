package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.governance.ratelimit.annotations.RateLimit;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/iqc/bootstrap")
public class IqcBootstrapController {

    private final UsageCounterRecorder usageCounterRecorder;

    public IqcBootstrapController(UsageCounterRecorder usageCounterRecorder) {
        this.usageCounterRecorder = usageCounterRecorder;
    }

    @GetMapping
    @ResourcePermission(code = "iqc:dashboard:view", name = "查看 IQC 基础状态", type = "iqc", description = "查询 IQC 平台基础状态")
    @Audit(operationType = OperationType.QUERY, description = "查询 IQC 平台基础状态", module = "IQC_BOOTSTRAP")
    @RateLimit(sceneCode = "iqc-bootstrap", maxCount = 60, period = 60)
    public Map<String, Object> bootstrap() {
        usageCounterRecorder.success("IQC_PLATFORM", "bootstrap", "BOOTSTRAP_QUERY");
        return Map.of(
                "application", "iqc-platform",
                "status", "UP",
                "timestamp", Instant.now()
        );
    }
}
