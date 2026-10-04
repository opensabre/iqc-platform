package io.github.opensabre.iqc.agent;

import io.github.opensabre.iqc.governance.IqcException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentConfigurationModeTest {
    @Test
    void capabilityAgentHasNoTaskModeOrRuleSet() {
        assertThatCode(() -> capability(null, null).validated()).doesNotThrowAnyException();
        assertThatThrownBy(() -> capability("RULE_ONLY", null).validated()).hasMessageContaining("只配置 LLM 能力");
        assertThatThrownBy(() -> capability(null, "set-1").validated()).hasMessageContaining("只配置 LLM 能力");
    }

    @Test
    void capabilityAgentStillRequiresModelAndPrompt() {
        var config = new AgentConfiguration("3.0", null, "prompt", null, null, null, null,
                null, List.of(), List.of(), List.of(), null, null);
        assertThatThrownBy(config::validated).hasMessageContaining("主模型");
    }

    private AgentConfiguration capability(String mode, String setId) {
        return new AgentConfiguration("3.0", mode, "prompt", null, null, null, null,
                "model-1", List.of(), List.of("mcp-1"), List.of("skill-1"), null, setId);
    }

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

    @Test
    void llmThenRuleIsAValidModelBackedMode() {
        assertThatCode(() -> config("LLM_THEN_RULE", "先提取候选再按规则复核", "model-1").validated())
                .doesNotThrowAnyException();
    }

    private AgentConfiguration config(String mode, String prompt, String modelId) {
        return new AgentConfiguration("2.0", mode, prompt, null, null, null, null,
                modelId, List.of(), List.of(), List.of(), null,
                "RULE_ONLY".equals(mode) ? "rule-set-1" : null);
    }
}
