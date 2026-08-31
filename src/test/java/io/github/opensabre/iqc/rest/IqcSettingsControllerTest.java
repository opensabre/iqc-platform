package io.github.opensabre.iqc.rest;

import io.github.opensabre.iqc.result.llm.LlmQualityProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IqcSettingsControllerTest {
    @Test
    void settingsExposeSafeModelStateWithoutSecrets() {
        LlmQualityProperties properties = new LlmQualityProperties();
        properties.setEndpoint("https://model.example.test/v1/chat/completions");
        properties.setApiKey("secret-api-key");
        properties.setModel("quality-model");

        Map<String, Object> settings = new IqcSettingsController(properties).settings();

        assertThat(settings).containsKey("model");
        Map<?, ?> model = (Map<?, ?>) settings.get("model");
        assertThat(model.get("enabled")).isEqualTo(true);
        assertThat(model.get("configurationSource")).isEqualTo("AGENT_SNAPSHOT");
        assertThat(model.get("endpointConfigured")).isEqualTo(true);
        assertThat(model.get("model")).isEqualTo("quality-model");
        assertThat(model.containsKey("apiKey")).isFalse();
        assertThat(settings.toString()).doesNotContain("secret-api-key");
        assertThat(settings.toString()).doesNotContain("model.example.test");
    }
}
