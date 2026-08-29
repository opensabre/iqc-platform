package io.github.opensabre.iqc.result.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Routes schema 2.0 Agent calls through the snapshotted primary and ordered fallback models. */
@Component
public class SnapshotChatModelRouter {
    private final ChatModel defaultModel; private final ObjectMapper objectMapper; private final SecretReferenceResolver secrets;
    @Autowired
    public SnapshotChatModelRouter(ObjectProvider<ChatModel> defaultModel, ObjectMapper objectMapper, SecretReferenceResolver secrets) { this(defaultModel.getIfAvailable(), objectMapper, secrets); }
    public SnapshotChatModelRouter(ChatModel defaultModel, ObjectMapper objectMapper, SecretReferenceResolver secrets) { this.defaultModel=defaultModel; this.objectMapper=objectMapper; this.secrets=secrets; }

    /** Uses the deployment default for legacy Agents and ordered snapshot models for schema 2.0. */
    public ChatResponse call(Prompt prompt, JsonNode agentSnapshot) {
        return call(prompt, agentSnapshot, new ToolCallback[0]);
    }
    public ChatResponse call(Prompt prompt, JsonNode agentSnapshot, ToolCallback[] callbacks) {
        Prompt executablePrompt = callbacks.length == 0 ? prompt : new Prompt(prompt.getInstructions(),
                ToolCallingChatOptions.builder().toolCallbacks(callbacks).build());
        JsonNode config=config(agentSnapshot);
        if(!"2.0".equals(config.path("schemaVersion").asText())||config.path("assetSnapshots").isMissingNode()) {
            if(defaultModel==null) throw new IllegalStateException("系统默认模型未启用");
            return defaultModel.call(executablePrompt);
        }
        List<JsonNode> candidates=new ArrayList<>(); candidates.add(config.path("assetSnapshots").path("primaryModel"));
        config.path("assetSnapshots").path("fallbackModels").forEach(candidates::add);
        RuntimeException last=null;
        for(JsonNode candidate:candidates) try{return create(candidate).call(executablePrompt);}catch(RuntimeException exception){last=exception;}
        throw last==null?new IllegalStateException("Agent 没有可用模型快照"):last;
    }
    public ChatModel create(JsonNode model) {
        String provider=model.path("provider").asText();
        if(!List.of("SPRING_AI","OPENAI","DASHSCOPE","OLLAMA").contains(provider)) throw new IllegalStateException("当前 Spring AI 运行时不支持模型供应商: "+provider);
        String apiKey="OLLAMA".equals(provider)&&model.path("secretRef").asText("").isBlank()?"ollama":secrets.resolve(model.path("secretRef").asText());
        OpenAiChatOptions options=OpenAiChatOptions.builder().baseUrl(model.path("endpoint").asText()).apiKey(apiKey)
                .model(model.path("modelName").asText()).temperature(model.path("temperature").asDouble(0.1))
                .timeout(Duration.ofSeconds(model.path("timeoutSeconds").asInt(60))).maxRetries(model.path("maxRetries").asInt(0)).build();
        return OpenAiChatModel.builder().options(options).build();
    }
    private JsonNode config(JsonNode snapshot){if(snapshot==null)return objectMapper.nullNode();JsonNode config=snapshot.path("configJson");if(config.isTextual())try{return objectMapper.readTree(config.asText());}catch(Exception ignored){return objectMapper.nullNode();}return config;}
}
