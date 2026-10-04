package io.github.opensabre.iqc.result.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SnapshotChatModelRouterTest {
    @Test
    void capabilityAgentNeverFallsBackToUnconfiguredDeploymentDefault() throws Exception {
        ChatModel defaultModel = mock(ChatModel.class);
        var router = new SnapshotChatModelRouter(defaultModel, new ObjectMapper(), mock(SecretReferenceResolver.class));
        var snapshot = new ObjectMapper().readTree("{\"configJson\":{\"schemaVersion\":\"3.0\"}}");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> router.call(mock(Prompt.class), snapshot))
                .hasMessageContaining("缺少已发布");
        verifyNoInteractions(defaultModel);
    }

    @Test
    void capabilityAgentUsesPinnedModelWithoutMode() throws Exception {
        ChatModel pinned = mock(ChatModel.class), defaultModel = mock(ChatModel.class);
        ChatResponse expected = mock(ChatResponse.class);
        when(pinned.call(any(Prompt.class))).thenReturn(expected);
        var router = spy(new SnapshotChatModelRouter(defaultModel, new ObjectMapper(), mock(SecretReferenceResolver.class)));
        doReturn(pinned).when(router).create(any());
        var snapshot = new ObjectMapper().readTree("{\"configJson\":{\"schemaVersion\":\"3.0\",\"assetSnapshots\":{\"primaryModel\":{\"id\":\"p\"}}}}");
        assertThat(router.call(mock(Prompt.class), snapshot)).isSameAs(expected);
        verifyNoInteractions(defaultModel);
    }

    @Test
    void fallsBackInSnapshotOrderWhenPrimaryFails() throws Exception {
        ChatModel primary=mock(ChatModel.class),fallback=mock(ChatModel.class),defaultModel=mock(ChatModel.class);
        ChatResponse expected=mock(ChatResponse.class); when(primary.call(any(Prompt.class))).thenThrow(new IllegalStateException("primary down")); when(fallback.call(any(Prompt.class))).thenReturn(expected);
        SnapshotChatModelRouter router=spy(new SnapshotChatModelRouter(defaultModel,new ObjectMapper(),mock(SecretReferenceResolver.class)));
        doReturn(primary,fallback).when(router).create(any());
        var snapshot=new ObjectMapper().readTree("""
          {"configJson":{"schemaVersion":"2.0","assetSnapshots":{"primaryModel":{"id":"p"},"fallbackModels":[{"id":"f"}]}}}
          """);
        assertThat(router.call(mock(Prompt.class),snapshot)).isSameAs(expected);
        verify(primary).call(any(Prompt.class));
        verify(fallback).call(any(Prompt.class));
        verifyNoInteractions(defaultModel);
    }
}
