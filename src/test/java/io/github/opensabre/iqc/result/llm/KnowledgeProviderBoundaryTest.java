package io.github.opensabre.iqc.result.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.governance.ratelimit.GovernanceRateLimiter;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import io.github.opensabre.iqc.knowledge.KnowledgeTaskSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Provider request boundaries retain separate conversation and verified reference messages. */
class KnowledgeProviderBoundaryTest {
    private final ObjectMapper json=new ObjectMapper();
    private final String reference="verified reference body";
    private com.fasterxml.jackson.databind.JsonNode agent(){
        var config=json.createObjectNode().put("schemaVersion","3.0").put("systemPrompt","inspection instructions");
        config.putArray("knowledgeAssets").addObject().put("knowledgeBaseId","kb").put("versionNo",2);
        return json.createObjectNode().set("configJson",config);
    }
    private ModelCallContext context(){
        var capability=mock(KnowledgeTaskSnapshotService.ExecutionContext.class);
        when(capability.material(any(),eq("task"),eq("owner"),eq("group"))).thenReturn(reference);
        return new ModelCallContext("task","execution","node",0,"owner","group","conversation",capability);
    }
    @Test void springAiPassesSeparateReferenceAndConversationMessagesThroughRouter(){
        var router=mock(SnapshotChatModelRouter.class);var direct=mock(ChatModel.class);
        when(router.call(any(),any(),any(),any())).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"hit\":false,\"reason\":\"ok\"}")))));
        var props=new LlmQualityProperties();props.setMaxAttempts(1);
        var provider=new SpringAiLlmQualityProvider(direct,router,json,mock(GovernanceRateLimiter.class),mock(UsageCounterRecorder.class),props);
        assertThat(provider.evaluate("original conversation",json.createObjectNode().put("id","rule"),agent(),null,"record",context()).supported()).isTrue();
        var capture=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(router).call(capture.capture(),any(),any(),any());
        var messages=capture.getValue().getInstructions();assertThat(messages).hasSize(3);
        assertThat(messages.get(0).getText()).contains("禁止以资料文档替代会话消息").doesNotContain(reference);
        assertThat(messages.get(1).getText()).isEqualTo(reference);
        assertThat(messages.get(2).getText()).contains("original conversation").doesNotContain(reference);
        verifyNoInteractions(direct);
    }
    @Test void springAiRejectsMissingCapabilityBeforeRouterCall(){
        var router=mock(SnapshotChatModelRouter.class);var direct=mock(ChatModel.class);
        var provider=new SpringAiLlmQualityProvider(direct,router,json,mock(GovernanceRateLimiter.class),mock(UsageCounterRecorder.class),new LlmQualityProperties());
        var result=provider.evaluate("conversation",json.createObjectNode(),agent(),null,"record",null);
        assertThat(result.supported()).isFalse();
        assertThat(result.reason()).contains("KNOWLEDGE_CONTEXT_NOT_FROZEN");
        verifyNoInteractions(router,direct);
    }
    @Test void httpRejectsKnowledgeWithoutGatewayBeforeRateLimiterOrNetwork(){
        var limiter=mock(GovernanceRateLimiter.class);
        var provider=new HttpLlmQualityProvider(json,limiter,mock(UsageCounterRecorder.class),new LlmQualityProperties());
        var result=provider.evaluate("conversation",json.createObjectNode(),agent(),null,"record",context());
        assertThat(result.supported()).isFalse();
        assertThat(result.reason()).contains("KNOWLEDGE_REQUIRES_MODEL_GATEWAY");
        verifyNoInteractions(limiter);
    }
}
