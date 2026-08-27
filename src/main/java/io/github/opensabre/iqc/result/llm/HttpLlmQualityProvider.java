package io.github.opensabre.iqc.result.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.governance.client.dto.RateLimitCheckRequest;
import io.github.opensabre.governance.ratelimit.GovernanceRateLimiter;
import io.github.opensabre.governance.ratelimit.enums.RateLimitAlgorithmType;
import io.github.opensabre.governance.ratelimit.enums.RateLimitDimension;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import io.github.opensabre.governance.usage.UsageOutcome;
import io.github.opensabre.governance.usage.UsageRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 调用 OpenAI-compatible chat completions，并严格校验模型返回的 JSON。 */
@Component
@ConditionalOnExpression("'${iqc.llm.enabled:false}' == 'true' && '${iqc.llm.provider:spring-ai}' == 'http'")
public class HttpLlmQualityProvider implements LlmQualityProvider {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final GovernanceRateLimiter rateLimiter;
    private final UsageCounterRecorder usageCounterRecorder;
    private final LlmQualityProperties properties;

    public HttpLlmQualityProvider(ObjectMapper objectMapper, GovernanceRateLimiter rateLimiter,
                                  UsageCounterRecorder usageCounterRecorder, LlmQualityProperties properties) {
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
        this.usageCounterRecorder = usageCounterRecorder;
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMillis());
        requestFactory.setReadTimeout(properties.getReadTimeoutMillis());
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getEndpoint()).requestFactory(requestFactory);
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + properties.getApiKey());
        }
        this.restClient = builder.build();
    }

    @Override
    public LlmEvaluation evaluate(String content, JsonNode rule, String recordId) {
        String stableId = recordId == null || recordId.isBlank() ? stableId(content, rule) : recordId;
        String usageId = "llm-call:" + stableId;
        String ruleId = rule.path("id").asText("unknown");
        usageCounterRecorder.record(new UsageRecord(usageId + ":attempt", null, "iqc-platform", "LLM_CALL",
                ruleId, "QUALITY_EVALUATION", UsageOutcome.ATTEMPT));
        try {
            var decision = rateLimiter.check(RateLimitCheckRequest.builder()
                    .sceneCode("iqc-llm-call")
                    .key(properties.getModel())
                    .keyPrefix("iqc:llm")
                    .algorithm(RateLimitAlgorithmType.TOKEN_BUCKET)
                    .dimensions(List.of(RateLimitDimension.BUSINESS))
                    .dimensionValues(Map.of(RateLimitDimension.BUSINESS, properties.getModel()))
                    .maxCount(properties.getRateLimitMaxCount())
                    .period(properties.getRateLimitPeriod())
                    .enabled(true)
                    .build());
            if (decision != null && !decision.allowed()) {
                throw new IllegalStateException("LLM 调用达到限次: " + decision.errorMessage());
            }
            LlmEvaluation evaluation = callWithRetry(content, rule);
            usageCounterRecorder.record(new UsageRecord(usageId + ":success", null, "iqc-platform", "LLM_CALL",
                    ruleId, "QUALITY_EVALUATION", UsageOutcome.SUCCESS));
            return evaluation;
        } catch (RuntimeException exception) {
            usageCounterRecorder.record(new UsageRecord(usageId + ":failure", null, "iqc-platform", "LLM_CALL",
                    ruleId, "QUALITY_EVALUATION", UsageOutcome.FAILURE));
            return new LlmEvaluation(false, false, "LLM 调用失败: " + safeMessage(exception));
        }
    }

    private LlmEvaluation callWithRetry(String content, JsonNode rule) {
        RuntimeException last = null;
        int attempts = Math.max(1, Math.min(properties.getMaxAttempts(), 3));
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                JsonNode response = restClient.post().uri(properties.getPath())
                        .body(Map.of("model", properties.getModel(), "temperature", 0,
                                "response_format", Map.of("type", "json_object"),
                                "messages", List.of(
                                        Map.of("role", "system", "content", "你是质检规则执行器。只返回 JSON，不要 Markdown。格式必须是 {\\\"hit\\\":true或false,\\\"reason\\\":\\\"简短理由\\\"}。"),
                                        Map.of("role", "user", "content", prompt(content, rule)))))
                        .retrieve().body(JsonNode.class);
                return parseEvaluation(response);
            } catch (RestClientException | IllegalArgumentException exception) {
                last = new IllegalStateException(exception.getMessage(), exception);
                if (attempt < attempts && properties.getRetryBackoffMillis() > 0) {
                    try { Thread.sleep(Math.min(properties.getRetryBackoffMillis(), 5000)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("LLM 重试被中断", interrupted); }
                }
            }
        }
        throw last == null ? new IllegalStateException("LLM 未返回结果") : last;
    }

    JsonNode parseStructuredResponse(JsonNode response) {
        JsonNode content = response == null ? null : response.path("choices").path(0).path("message").path("content");
        if (content == null || content.isMissingNode()) throw new IllegalArgumentException("LLM 响应缺少 choices[0].message.content");
        try {
            return content.isTextual() ? objectMapper.readTree(stripFence(content.asText())) : content;
        } catch (Exception exception) {
            throw new IllegalArgumentException("LLM 响应不是合法 JSON", exception);
        }
    }

    private LlmEvaluation parseEvaluation(JsonNode response) {
        JsonNode json = parseStructuredResponse(response);
        if (!json.has("hit") || !json.get("hit").isBoolean()) throw new IllegalArgumentException("LLM 响应缺少布尔 hit 字段");
        String reason = json.path("reason").asText("").trim();
        if (reason.isBlank()) throw new IllegalArgumentException("LLM 响应缺少 reason 字段");
        return new LlmEvaluation(true, json.get("hit").asBoolean(), reason);
    }

    private String prompt(String content, JsonNode rule) {
        return "规则配置:" + rule.toString() + "\\n待质检话术:" + LlmTextSanitizer.sanitize(content);
    }

    private String stripFence(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("```")) trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        return trimmed;
    }

    private String stableId(String content, JsonNode rule) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((rule.toString() + "\\n" + content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("生成 LLM 计次标识失败", exception);
        }
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message.replaceAll("(?i)(api[-_ ]?key|authorization)\\s*[:=]\\s*[^,} ]+", "$1=[REDACTED]");
    }
}
