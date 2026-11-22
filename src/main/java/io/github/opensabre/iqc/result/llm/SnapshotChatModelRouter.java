package io.github.opensabre.iqc.result.llm;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.integration.iac.IacConsumerClient;
import io.github.opensabre.iqc.integration.iac.IacResponses;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.List;

/** Sends managed capability execution to its IAC owner; IQC retains business prompts and parsing. */
@Component
public class SnapshotChatModelRouter {
    private final IacConsumerClient client;
    private final ChatModel legacyModel;

    @Autowired
    public SnapshotChatModelRouter(IacConsumerClient client, ObjectProvider<ChatModel> legacyModel) {
        this(client, legacyModel.getIfAvailable());
    }

    public SnapshotChatModelRouter(IacConsumerClient client, ChatModel legacyModel) {
        this.client = client;
        this.legacyModel = legacyModel;
    }

    public ChatResponse call(Prompt prompt, JsonNode snapshot) { return invoke(prompt, snapshot, false); }

    public ChatResponse callWithTools(Prompt prompt, JsonNode snapshot) { return invoke(prompt, snapshot, true); }

    private ChatResponse invoke(Prompt prompt, JsonNode snapshot, boolean tools) {
        // Legacy deployment-only models are not IAC assets. Never use them as fallback for IAC errors.
        if (snapshot == null || !hasManagedConfiguration(snapshot)) {
            if (legacyModel == null) throw new IllegalStateException("旧版部署默认模型未启用");
            return taskDefault(snapshot).call(prompt);
        }
        var turns = prompt.getInstructions().stream().map(message ->
                new IacConsumerClient.Turn(message.getMessageType().getValue(), message.getText())).toList();
        var reply = IacResponses.required(client.invoke(new IacConsumerClient.ModelRequest(snapshot, turns, tools)));
        if (reply.content() == null) throw new IllegalStateException("IAC 模型返回为空");
        var metadata = ChatResponseMetadata.builder();
        if (reply.model() != null) metadata.model(reply.model());
        if (reply.inputTokens() != null && reply.outputTokens() != null)
            metadata.usage(new DefaultUsage(reply.inputTokens(), reply.outputTokens()));
        return new ChatResponse(List.of(new Generation(new AssistantMessage(reply.content()))), metadata.build());
    }

    /** Legacy deployment models need a per-call client copy for transport timeouts to actually apply. */
    ChatModel taskDefault(JsonNode snapshot) {
        JsonNode overrides = snapshot == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : snapshot.path("taskOverrides");
        if (!overrides.has("temperature") && !overrides.has("timeoutSeconds")) return legacyModel;
        if (!(legacyModel instanceof org.springframework.ai.openai.OpenAiChatModel model))
            throw new IllegalStateException("当前默认模型不支持任务温度或超时参数");
        var options = model.getOptions().mutate();
        if (overrides.has("temperature")) options.temperature(overrides.path("temperature").asDouble());
        if (overrides.has("timeoutSeconds")) options.timeout(java.time.Duration.ofSeconds(overrides.path("timeoutSeconds").asInt()));
        return org.springframework.ai.openai.OpenAiChatModel.builder().options(options.build()).build();
    }

    private boolean hasManagedConfiguration(JsonNode snapshot) {
        JsonNode config = snapshot.path("configJson");
        if (config.isTextual()) {
            try { config = new com.fasterxml.jackson.databind.ObjectMapper().readTree(config.asText()); }
            catch (Exception invalid) { throw new IllegalArgumentException("冻结智能体配置无效", invalid); }
        }
        return "3.0".equals(config.path("schemaVersion").asText()) || "2.0".equals(config.path("schemaVersion").asText());
    }

}
