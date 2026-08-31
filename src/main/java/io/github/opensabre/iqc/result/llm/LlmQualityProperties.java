package io.github.opensabre.iqc.result.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** IQC 的 OpenAI-compatible 模型调用配置；密钥只从配置中心或环境变量注入。 */
@Data
@ConfigurationProperties(prefix = "iqc.llm")
public class LlmQualityProperties {
    /** spring-ai is the Boot 4 compatible default; http remains available for rollback. */
    private String provider = "spring-ai";
    private String endpoint;
    private String path = "/v1/chat/completions";
    private String apiKey;
    private String model;
    private int connectTimeoutMillis = 3000;
    private int readTimeoutMillis = 15000;
    private int maxAttempts = 2;
    private int retryBackoffMillis = 200;
    private int rateLimitMaxCount = 120;
    private int rateLimitPeriod = 60;
}
