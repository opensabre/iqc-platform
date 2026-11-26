package io.github.opensabre.iqc.integration.iac;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.common.core.entity.vo.Result;
import io.github.opensabre.iqc.result.llm.SnapshotChatModelRouter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** IQC forwards immutable snapshots, preserves usage, and never substitutes local assets on remote failure. */
class IacIntegrationTest {
    private final IacConsumerClient client = mock(IacConsumerClient.class);
    private final ChatModel legacy = mock(ChatModel.class);
    private final SnapshotChatModelRouter router = new SnapshotChatModelRouter(client,legacy);
    private final ObjectMapper json = new ObjectMapper();
    @Test void sendsTaskOverridesAndPreservesReportedZeroTokens() throws Exception {
        var snapshot = json.readTree("""
                {"configJson":{"schemaVersion":"3.0","assetSnapshots":{"primaryModel":{"id":"m1"}}},
                "taskOverrides":{"temperature":0,"timeoutSeconds":1}}
                """);
        String before = snapshot.toString();
        when(client.invoke(any())).thenReturn(Result.success(new IacConsumerClient.ModelReply("answer","actual-model",0,12)));
        var response = router.callWithTools(new Prompt("business prompt"),snapshot);
        var request = org.mockito.ArgumentCaptor.forClass(IacConsumerClient.ModelRequest.class);
        verify(client).invoke(request.capture());
        assertThat(request.getValue().toolsEnabled()).isTrue();
        assertThat(request.getValue().snapshot().toString()).isEqualTo(before);
        assertThat(request.getValue().messages().getFirst().content()).isEqualTo("business prompt");
        assertThat(response.getMetadata().getUsage().getPromptTokens()).isZero();
        assertThat(response.getMetadata().getModel()).isEqualTo("actual-model");
        assertThat(snapshot.toString()).isEqualTo(before);
        verifyNoInteractions(legacy);
    }
    @Test void sendsTaskIdOutsideTheFrozenCapabilitySnapshot() throws Exception {
        var snapshot = json.createObjectNode().put("iacBusinessReference", "task-123");
        snapshot.putObject("configJson").put("schemaVersion", "3.0");
        when(client.invoke(any())).thenReturn(Result.success(new IacConsumerClient.ModelReply("answer", null, null, null)));

        router.call(new Prompt("business prompt"), snapshot);

        var request = org.mockito.ArgumentCaptor.forClass(IacConsumerClient.ModelRequest.class);
        verify(client).invoke(request.capture());
        assertThat(request.getValue().businessReference()).isEqualTo("task-123");
        assertThat(request.getValue().snapshot().has("iacBusinessReference")).isFalse();
        assertThat(snapshot.path("iacBusinessReference").asText()).isEqualTo("task-123");
    }
    @Test void remoteTransportFailureDoesNotFallBackToDeploymentModel() throws Exception {
        when(client.invoke(any())).thenThrow(new IllegalStateException("IAC unavailable"));
        assertThatThrownBy(() -> router.call(new Prompt("q"),json.readTree("{\"configJson\":{\"schemaVersion\":\"3.0\"}}")))
                .hasMessage("IAC unavailable");
        verifyNoInteractions(legacy);
    }
    @Test void rejectsNullAndFailedWrappedResponses() {
        assertThatThrownBy(() -> IacResponses.required(null)).hasMessageContaining("IAC");
        assertThatThrownBy(() -> IacResponses.required(new Result<>())).hasMessageContaining("IAC");
    }
}
