package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.ratelimit.annotations.RateLimit;
import io.github.opensabre.iqc.result.llm.LlmQualityProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/iqc/settings")
public class IqcSettingsController {
    private final LlmQualityProperties llmProperties;

    public IqcSettingsController(LlmQualityProperties llmProperties) {
        this.llmProperties = llmProperties;
    }

    @GetMapping
    @ResourcePermission(code = "iqc:settings:view", name = "查看系统设置", type = "iqc", description = "查看 IQC 模型和治理配置状态")
    @RateLimit(sceneCode = "iqc-settings-query", maxCount = 60, period = 60)
    public Map<String, Object> settings() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("enabled", llmProperties.isEnabled());
        model.put("provider", llmProperties.getProvider());
        model.put("endpointConfigured", llmProperties.getEndpoint() != null && !llmProperties.getEndpoint().isBlank());
        model.put("model", llmProperties.getModel() == null || llmProperties.getModel().isBlank() ? "未配置" : llmProperties.getModel());
        model.put("connectTimeoutMillis", llmProperties.getConnectTimeoutMillis());
        model.put("readTimeoutMillis", llmProperties.getReadTimeoutMillis());
        model.put("maxAttempts", llmProperties.getMaxAttempts());
        model.put("rateLimitMaxCount", llmProperties.getRateLimitMaxCount());
        model.put("rateLimitPeriod", llmProperties.getRateLimitPeriod());

        Map<String, Object> governance = new LinkedHashMap<>();
        governance.put("audit", "OpenSabre Framework / base-sysadmin");
        governance.put("rateLimit", "OpenSabre GovernanceRateLimiter");
        governance.put("usageCounter", "OpenSabre UsageCounterRecorder");
        governance.put("dictionary", "OpenSabre Dictionary");
        governance.put("errorCatalog", "OpenSabre Error Catalog");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("application", "iqc-platform");
        response.put("model", model);
        response.put("governance", governance);
        response.put("timestamp", Instant.now());
        return response;
    }
}
