package io.github.opensabre.iqc.result.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.governance.ratelimit.GovernanceRateLimiter;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiLlmQualityProviderTest {

    @Test
    void evaluatesStructuredResponseAndSanitizesSensitiveInput() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        GovernanceRateLimiter rateLimiter = mock(GovernanceRateLimiter.class);
        UsageCounterRecorder recorder = mock(UsageCounterRecorder.class);
        LlmQualityProperties properties = new LlmQualityProperties();
        properties.setModel("qwen-plus");
        properties.setMaxAttempts(1);
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("{\"hit\":true,\"reason\":\"存在违规承诺\"}")))));
        SpringAiLlmQualityProvider provider = new SpringAiLlmQualityProvider(chatModel, null, new ObjectMapper(),
                rateLimiter, recorder, properties);

        var agent = new ObjectMapper().readTree("{\"configJson\":\"{\\\"systemPrompt\\\":\\\"重点检查返现承诺\\\"}\"}");
        var evaluation = provider.evaluate("联系 13812345678 返现",
                new ObjectMapper().readTree("{\"id\":\"r-1\"}"), agent, "record-1");

        assertThat(evaluation.supported()).isTrue();
        assertThat(evaluation.hit()).isTrue();
        assertThat(evaluation.reason()).isEqualTo("存在违规承诺");
        var promptCaptor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getContents()).contains("[手机号]").doesNotContain("13812345678");
        assertThat(promptCaptor.getValue().getSystemMessage().getText()).contains("重点检查返现承诺");
        verify(recorder, org.mockito.Mockito.times(2)).record(any());
    }

    @Test
    void malformedResponseFailsClosed() {
        SpringAiLlmQualityProvider provider = new SpringAiLlmQualityProvider(mock(ChatModel.class), null,
                new ObjectMapper(), mock(GovernanceRateLimiter.class), mock(UsageCounterRecorder.class),
                new LlmQualityProperties());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.parseEvaluation("{\"reason\":\"missing hit\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hit");
    }

    @Test
    void schemaTwoUsesImmutableSkillSnapshotsInPrompt() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("{\"hit\":false,\"reason\":\"合规\"}")))));
        LlmQualityProperties properties = new LlmQualityProperties(); properties.setModel("snapshot-model"); properties.setMaxAttempts(1);
        SpringAiLlmQualityProvider provider = new SpringAiLlmQualityProvider(chatModel, null, new ObjectMapper(),
                mock(GovernanceRateLimiter.class), mock(UsageCounterRecorder.class), properties);
        var agent = new ObjectMapper().readTree("""
                {"configJson":{"schemaVersion":"2.0","systemPrompt":"执行合规检查","assetSnapshots":{"skills":[
                  {"name":"承诺识别","instructions":"识别无法兑现的承诺","versionNo":3}]}}}
                """);

        provider.evaluate("可以保证通过", new ObjectMapper().readTree("{\"id\":\"r-1\"}"), agent, "record-2");

        var captor = org.mockito.ArgumentCaptor.forClass(Prompt.class); verify(chatModel).call(captor.capture());
        assertThat(captor.getValue().getSystemMessage().getText()).contains("执行合规检查", "Skill[承诺识别]", "识别无法兑现的承诺");
    }
}
