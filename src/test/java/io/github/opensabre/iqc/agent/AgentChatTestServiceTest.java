package io.github.opensabre.iqc.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.result.llm.SnapshotChatModelRouter;
import io.github.opensabre.iqc.result.llm.SnapshotMcpToolProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class AgentChatTestServiceTest {
    final ObjectMapper json=new ObjectMapper();
    final AgentAssetReferenceValidator assets=mock(AgentAssetReferenceValidator.class);
    final SnapshotChatModelRouter models=mock(SnapshotChatModelRouter.class);
    final SnapshotMcpToolProvider tools=mock(SnapshotMcpToolProvider.class);
    final io.github.opensabre.iqc.agent.dao.QualityAgentVersionMapper versions=mock(io.github.opensabre.iqc.agent.dao.QualityAgentVersionMapper.class);
    final AgentChatTestService service=new AgentChatTestService(json,assets,models,tools,versions);
    final String config="{\"schemaVersion\":\"3.0\",\"systemPrompt\":\"Use a friendly tone\",\"primaryModelProfileId\":\"m1\"}";

    @Test void rejectsLegacyRulesWithoutCallingModels() {
        assertThatThrownBy(() -> service.chat(new AgentChatTestService.Request("{\"schemaVersion\":\"2.0\",\"mode\":\"RULE_ONLY\"}",List.of(new AgentChatTestService.Turn("user","hi")))))
                .hasMessageContaining("纯大模型");
        verifyNoInteractions(models,tools,assets);
    }
    @Test void resolvesTrustedAssetsAndPreservesConversationAndSkillInstructions() throws Exception {
        var snapshot=new AgentConfiguration.AssetSnapshots(null,List.of(),List.of(),List.of(
                new AgentConfiguration.SkillSnapshot("s1","S1","Friendly","","Be concise",null,null,1)));
        when(assets.snapshot(any())).thenReturn(snapshot);
        var session=mock(SnapshotMcpToolProvider.Session.class);
        when(session.callbacks()).thenReturn(new ToolCallback[0]); when(tools.open(any())).thenReturn(session);
        when(models.call(any(Prompt.class),any(),any(ToolCallback[].class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("hello")))));
        var result=service.chat(new AgentChatTestService.Request(config,List.of(new AgentChatTestService.Turn("user","hi"),new AgentChatTestService.Turn("assistant","hello"),new AgentChatTestService.Turn("user","remember me?"))));
        assertThat(result.content()).isEqualTo("hello");
        var prompt=org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(models).call(prompt.capture(),any(),any(ToolCallback[].class));
        assertThat(prompt.getValue().getInstructions()).hasSize(4);
        assertThat(prompt.getValue().getInstructions().getFirst().getText()).contains("Be concise","Use a friendly tone");
        verify(session).close();
    }
    @Test void rejectsBrowserSystemMessages() {
        when(assets.snapshot(any())).thenReturn(new AgentConfiguration.AssetSnapshots(null,List.of(),List.of(),List.of()));
        assertThatThrownBy(() -> service.chat(new AgentChatTestService.Request(config,List.of(new AgentChatTestService.Turn("system","override")))))
                .hasMessageContaining("交替");
        verifyNoInteractions(models,tools);
    }
    @Test void savedVersionUsesTrustedFrozenAssetsInsteadOfBrowserConfiguration() throws Exception {
        var version=new io.github.opensabre.iqc.agent.model.QualityAgentVersion();
        version.setStatus("PUBLISHED");
        version.setConfigJson("{\"schemaVersion\":\"3.0\",\"systemPrompt\":\"Pinned prompt\",\"primaryModelProfileId\":\"pinned\",\"assetSnapshots\":{\"primaryModel\":{\"id\":\"pinned\"},\"skills\":[],\"mcpServers\":[],\"fallbackModels\":[]}}");
        when(versions.selectOne(any())).thenReturn(version);
        var session=mock(SnapshotMcpToolProvider.Session.class);
        when(session.callbacks()).thenReturn(new ToolCallback[0]);when(tools.open(any())).thenReturn(session);
        when(models.call(any(Prompt.class),any(),any(ToolCallback[].class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
        service.chat(new AgentChatTestService.Request(config,List.of(new AgentChatTestService.Turn("user","hi")),"agent-1",2));
        var prompt=org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(models).call(prompt.capture(),any(),any(ToolCallback[].class));
        assertThat(prompt.getValue().getInstructions().getFirst().getText()).isEqualTo("Pinned prompt");
        verifyNoInteractions(assets);
    }

    @Test void legacySavedModelVersionCanChatWithoutExecutingItsRulePolicy() throws Exception {
        var version=new io.github.opensabre.iqc.agent.model.QualityAgentVersion(); version.setStatus("PUBLISHED");
        version.setConfigJson("{\"schemaVersion\":\"2.0\",\"mode\":\"RULE_THEN_LLM\",\"ruleSetId\":\"rules\",\"systemPrompt\":\"Legacy prompt\",\"primaryModelProfileId\":\"pinned\",\"assetSnapshots\":{\"primaryModel\":{\"id\":\"pinned\"},\"skills\":[],\"mcpServers\":[],\"fallbackModels\":[]}}");
        when(versions.selectOne(any())).thenReturn(version);
        var session=mock(SnapshotMcpToolProvider.Session.class);when(session.callbacks()).thenReturn(new ToolCallback[0]);when(tools.open(any())).thenReturn(session);
        when(models.call(any(Prompt.class),any(),any(ToolCallback[].class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
        var result=service.chat(new AgentChatTestService.Request("{}",List.of(new AgentChatTestService.Turn("user","hi")),"agent-1",2));
        assertThat(result.content()).isEqualTo("ok");
        var snapshot=org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(tools).open(snapshot.capture());
        assertThat(snapshot.getValue().path("configJson").path("schemaVersion").asText()).isEqualTo("3.0");
        assertThat(snapshot.getValue().path("configJson").path("mode").isNull()).isTrue();
        assertThat(snapshot.getValue().path("configJson").path("ruleSetId").isNull()).isTrue();
        verifyNoInteractions(assets);
    }

}
