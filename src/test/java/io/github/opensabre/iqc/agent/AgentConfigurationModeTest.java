package io.github.opensabre.iqc.agent;

import io.github.opensabre.iqc.governance.IqcException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentConfigurationModeTest {
    @Test
    void ruleOnlyDoesNotRequirePromptOrModel() {
        AgentConfiguration config = config("RULE_ONLY", "", null);
        assertThatCode(config::validated).doesNotThrowAnyException();
    }

    @Test
    void intelligentModesRequirePromptAndModel() {
        assertThatThrownBy(() -> config("RULE_THEN_LLM", "prompt", null).validated())
                .isInstanceOf(IqcException.class).hasMessageContaining("主模型");
        assertThatThrownBy(() -> config("AGENT_LLM", "", "model-1").validated())
                .isInstanceOf(IqcException.class).hasMessageContaining("提示词");
    }

    private AgentConfiguration config(String mode, String prompt, String modelId) {
        return new AgentConfiguration("2.0", mode, prompt, null, null, null, null,
                modelId, List.of(), List.of(), List.of(), null,
                "RULE_ONLY".equals(mode) ? "rule-set-1" : null);
    }
}
