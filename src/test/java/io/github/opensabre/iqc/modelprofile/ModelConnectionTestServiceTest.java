package io.github.opensabre.iqc.modelprofile;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.modelprofile.dao.IqcModelProfileMapper;
import io.github.opensabre.iqc.modelprofile.model.IqcModelProfile;
import io.github.opensabre.iqc.result.llm.SnapshotChatModelRouter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelConnectionTestServiceTest {

    @Test
    void reportsSuccessWithoutReturningCredentials() {
        IqcModelProfileMapper mapper = mock(IqcModelProfileMapper.class);
        SnapshotChatModelRouter router = mock(SnapshotChatModelRouter.class);
        ChatModel model = mock(ChatModel.class);
        IqcModelProfile profile = new IqcModelProfile();
        profile.setProvider("OPENAI");
        profile.setModelName("gpt-test");
        profile.setSecretRef("env:IQC_MODEL_API_KEY");
        when(mapper.selectById("model-1")).thenReturn(profile);
        when(router.create(any())).thenReturn(model);
        when(model.call(any(Prompt.class))).thenReturn(mock(ChatResponse.class));

        var result = new ModelConnectionTestService(mapper, router, new ObjectMapper()).test("model-1");

        assertThat(result.success()).isTrue();
        assertThat(result.message()).doesNotContain("IQC_MODEL_API_KEY");
    }

    @Test
    void redactsProviderFailureDetails() {
        IqcModelProfileMapper mapper = mock(IqcModelProfileMapper.class);
        SnapshotChatModelRouter router = mock(SnapshotChatModelRouter.class);
        IqcModelProfile profile = new IqcModelProfile();
        profile.setProvider("OPENAI");
        profile.setModelName("gpt-test");
        when(mapper.selectById("model-1")).thenReturn(profile);
        when(router.create(any())).thenThrow(new IllegalStateException("Authorization: Bearer leaked-secret"));

        var result = new ModelConnectionTestService(mapper, router, new ObjectMapper()).test("model-1");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).doesNotContain("leaked-secret").doesNotContain("Authorization");
    }
}
