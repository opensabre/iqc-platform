package io.github.opensabre.iqc.result.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.governance.ratelimit.GovernanceRateLimiter;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HttpLlmQualityProviderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesOpenAiCompatibleStructuredResponse() throws Exception {
        LlmQualityProperties properties = new LlmQualityProperties();
        properties.setEndpoint("http://localhost:9999");
        HttpLlmQualityProvider provider = new HttpLlmQualityProvider(objectMapper,
                mock(GovernanceRateLimiter.class), mock(UsageCounterRecorder.class), properties);

        var response = objectMapper.createObjectNode();
        response.putArray("choices").addObject().putObject("message")
                .put("content", "```json\n{\"hit\":true,\"reason\":\"命中优惠承诺\"}\n```");

        JsonNode result = provider.parseStructuredResponse(response);

        assertThat(result.path("hit").asBoolean()).isTrue();
        assertThat(result.path("reason").asText()).isEqualTo("命中优惠承诺");
    }

    @Test
    void masksCommonSensitiveValuesBeforeModelBoundary() {
        String sanitized = LlmTextSanitizer.sanitize("手机号 13812345678，邮箱 a@example.com，证件 110101199001011234");

        assertThat(sanitized).doesNotContain("13812345678", "a@example.com", "110101199001011234");
        assertThat(sanitized).contains("[手机号]", "[邮箱]", "[证件号]");
    }
}
