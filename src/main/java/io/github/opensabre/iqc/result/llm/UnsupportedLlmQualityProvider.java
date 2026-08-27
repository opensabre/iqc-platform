package io.github.opensabre.iqc.result.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 未配置模型时显式失败，避免把 LLM 规则静默当成未命中。 */
@Component
@ConditionalOnProperty(prefix = "iqc.llm", name = "enabled", havingValue = "false", matchIfMissing = true)
public class UnsupportedLlmQualityProvider implements LlmQualityProvider {
    @Override
    public LlmEvaluation evaluate(String content, JsonNode rule, String recordId) {
        return new LlmEvaluation(false, false, "LLM 规则未配置可用适配器");
    }
}
