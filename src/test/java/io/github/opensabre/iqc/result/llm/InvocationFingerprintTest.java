package io.github.opensabre.iqc.result.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.result.llm.gateway.GatewayCallService;
import io.github.opensabre.iqc.result.llm.gateway.ModelGateway;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Checks actual router response evidence without contacting a supplier or creating gateway records. */
class InvocationFingerprintTest {
    final ObjectMapper json=new ObjectMapper();
    final ModelGateway gateway=mock(ModelGateway.class);
    final ChatModel model=mock(ChatModel.class);
    final ChatResponse response=new ChatResponse(List.of(new Generation(new AssistantMessage("unchanged output"))));
    final SnapshotChatModelRouter router;
    String modelKey="a".repeat(64); boolean handlePresent=true;
    InvocationFingerprintTest(){
        ObjectProvider<ChatModel> defaults=mock(ObjectProvider.class);
        router=spy(new SnapshotChatModelRouter(defaults,json,mock(SecretReferenceResolver.class),gateway));
        doReturn(model).when(router).create(any());when(model.call(any(Prompt.class))).thenReturn(response);
        when(gateway.invokeTracked(any(),any(),any(),any(),any(),any())).thenAnswer(call->{
            ChatResponse result=((Supplier<ChatResponse>)call.getArgument(2)).get();
            return new ModelGateway.Invocation<>(result,handlePresent?new GatewayCallService.Handle("call",modelKey):null);
        });
    }
    com.fasterxml.jackson.databind.JsonNode profile() throws Exception {
        return json.readTree("{\"id\":\"model\",\"versionNo\":1,\"provider\":\"OPENAI\",\"modelName\":\"pinned\",\"endpoint\":\"https://invalid.example/v1\"}");
    }
    ChatResponse invoke(Prompt prompt,String task) throws Exception {
        return router.callProfile(prompt,profile(),new GatewayCallService.Context(task,"rule","owner"));
    }
    @Test void sameFrameworkInputAcrossTasksIsStableButPromptAndActualModelAreDistinct() throws Exception {
        var prompt=new Prompt(List.of(new SystemMessage("policy"),new UserMessage("conversation and upstream PASS")));
        var first=SnapshotChatModelRouter.invocationFingerprint(invoke(prompt,"task-a"));
        assertThat(first).isNotNull();
        assertThat(SnapshotChatModelRouter.invocationFingerprint(invoke(prompt,"task-b"))).isEqualTo(first);
        var changed=SnapshotChatModelRouter.invocationFingerprint(invoke(new Prompt(List.of(new SystemMessage("policy"),new UserMessage("conversation and upstream FAIL"))),"task-a"));
        assertThat(changed.inputHash()).isNotEqualTo(first.inputHash());
        var policy=SnapshotChatModelRouter.invocationFingerprint(invoke(new Prompt(List.of(new SystemMessage("new policy"),new UserMessage("conversation and upstream PASS"))),"task-a"));
        assertThat(policy.inputHash()).isNotEqualTo(first.inputHash());
        modelKey="b".repeat(64);
        assertThat(SnapshotChatModelRouter.invocationFingerprint(invoke(prompt,"task-a")).modelKey()).isEqualTo(modelKey);
        verify(model,times(5)).call(any(Prompt.class));
    }
    @Test void absentGatewayHandleDoesNotManufactureEvidenceOrChangeOutput() throws Exception {
        handlePresent=false;var result=invoke(new Prompt("input"),"task");
        assertThat(SnapshotChatModelRouter.invocationFingerprint(result)).isNull();
        assertThat(result.getResult().getOutput().getText()).isEqualTo("unchanged output");
        verify(model).call(any(Prompt.class));
    }
    @Test void unknownOptionsRemainUnmeasured() throws Exception {
        var prompt=new Prompt("input",org.springframework.ai.chat.prompt.ChatOptions.builder().temperature(0.5).build());
        assertThat(SnapshotChatModelRouter.invocationFingerprint(invoke(prompt,"task"))).isNull();
        verify(model).call(any(Prompt.class));
    }
    @Test void mediaCannotBeComparedUsingTextOnlyEvidence() throws Exception {
        var media=new org.springframework.ai.content.Media(org.springframework.util.MimeTypeUtils.IMAGE_PNG,
                java.net.URI.create("https://invalid.example/attachment.png"));
        var prompt=new Prompt(UserMessage.builder().text("same text").media(media).build());
        var result=invoke(prompt,"task");
        assertThat(SnapshotChatModelRouter.invocationFingerprint(result)).isNull();
        assertThat(result.getResult().getOutput().getText()).isEqualTo("unchanged output");
    }
    @Test void changedToolDefinitionChangesEvidenceWithoutExecutingTheTool() throws Exception {
        var snapshot=json.createObjectNode();var config=snapshot.putObject("configJson");config.put("schemaVersion","3.0");
        config.putObject("assetSnapshots").set("primaryModel",profile());
        var tool=mock(org.springframework.ai.tool.ToolCallback.class);
        when(tool.getToolDefinition()).thenReturn(org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name("lookup").description("original lookup").inputSchema("{\"type\":\"object\"}").build());
        var first=SnapshotChatModelRouter.invocationFingerprint(router.call(new Prompt("input"),snapshot,new org.springframework.ai.tool.ToolCallback[]{tool}));
        when(tool.getToolDefinition()).thenReturn(org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name("lookup").description("changed lookup").inputSchema("{\"type\":\"object\"}").build());
        var second=SnapshotChatModelRouter.invocationFingerprint(router.call(new Prompt("input"),snapshot,new org.springframework.ai.tool.ToolCallback[]{tool}));
        assertThat(first).isNotNull();assertThat(second).isNotNull();assertThat(second.inputHash()).isNotEqualTo(first.inputHash());
        verify(tool,never()).call(anyString());
    }
    @Test void pinnedProfileChangesAreIncludedInInputEvidence() throws Exception {
        var prompt=new Prompt("same input");var first=SnapshotChatModelRouter.invocationFingerprint(invoke(prompt,"task"));
        var changed=(com.fasterxml.jackson.databind.node.ObjectNode)profile();changed.put("modelName","fallback-model");
        var second=SnapshotChatModelRouter.invocationFingerprint(router.callProfile(prompt,changed,new GatewayCallService.Context("task","rule","owner")));
        assertThat(second.inputHash()).isNotEqualTo(first.inputHash());
    }
    @Test void oldEvaluationJsonOmitsNewEvidenceAndRoundTrips() throws Exception {
        var old=new LlmQualityProvider.LlmEvaluation(true,false,"reason","{\"hit\":false,\"reason\":\"reason\"}");
        String encoded=json.writeValueAsString(old);
        assertThat(json.readTree(encoded).has("invocationFingerprint")).isFalse();
        assertThat(json.writeValueAsString(json.readValue(encoded,LlmQualityProvider.LlmEvaluation.class))).isEqualTo(encoded);
        assertThatThrownBy(()->new SnapshotChatModelRouter.InvocationFingerprint("iqc-llm-invocation-fingerprint-v1","invalid","a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
