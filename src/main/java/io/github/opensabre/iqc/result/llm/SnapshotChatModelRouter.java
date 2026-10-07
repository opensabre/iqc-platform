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
    private final io.github.opensabre.iqc.result.llm.gateway.ModelGateway gateway;
    @Autowired
    public SnapshotChatModelRouter(ObjectProvider<ChatModel> defaultModel, ObjectMapper objectMapper, SecretReferenceResolver secrets,
            io.github.opensabre.iqc.result.llm.gateway.ModelGateway gateway) {
        this.defaultModel=defaultModel.getIfAvailable();this.objectMapper=objectMapper;this.secrets=secrets;this.gateway=gateway;
    }
    public SnapshotChatModelRouter(ChatModel defaultModel, ObjectMapper objectMapper, SecretReferenceResolver secrets) { this.defaultModel=defaultModel; this.objectMapper=objectMapper; this.secrets=secrets; this.gateway=null; }

    /** Uses the deployment default for legacy Agents and ordered snapshot models for schema 2.0. */
    public ChatResponse call(Prompt prompt, JsonNode agentSnapshot) {
        return call(prompt, agentSnapshot, new ToolCallback[0]);
    }
    public ChatResponse call(Prompt prompt, JsonNode agentSnapshot, ToolCallback[] callbacks) {
        return call(prompt,agentSnapshot,callbacks,new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.Context(null,null,null));
    }
    public ChatResponse call(Prompt prompt, JsonNode agentSnapshot, ToolCallback[] callbacks,
            io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.Context context) {
        Prompt executablePrompt = callbacks.length == 0 ? prompt : new Prompt(prompt.getInstructions(),
                ToolCallingChatOptions.builder().toolCallbacks(callbacks).build());
        JsonNode config=config(agentSnapshot);
        boolean managed = io.github.opensabre.iqc.agent.AgentConfiguration.usesManagedAssets(config.path("schemaVersion").asText());
        if ("3.0".equals(config.path("schemaVersion").asText()) && (!config.path("assetSnapshots").isObject() || !config.path("assetSnapshots").path("primaryModel").isObject()))
            throw new IllegalStateException("Agent 缺少已发布的模型资产快照");
        if(!managed || config.path("assetSnapshots").isMissingNode()) {
            if(defaultModel==null) throw new IllegalStateException("系统默认模型未启用");
            var options=defaultModel.getDefaultOptions();
            String model=options==null || options.getModel()==null?"deployment-default":options.getModel();
            String endpoint=options instanceof OpenAiChatOptions openai && openai.getBaseUrl()!=null?openai.getBaseUrl():"deployment-default";
            return invoke(defaultModel,executablePrompt,new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.ModelIdentity(
                    defaultModel.getClass().getSimpleName(),endpoint,model),context);
        }
        List<JsonNode> candidates=new ArrayList<>(); candidates.add(config.path("assetSnapshots").path("primaryModel"));
        config.path("assetSnapshots").path("fallbackModels").forEach(candidates::add);
        RuntimeException last=null;
        for(JsonNode candidate:candidates) {
            ChatModel selected;
            try{selected=create(candidate);}catch(io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException denied){throw denied;}catch(RuntimeException unavailable){last=unavailable;continue;}
            var identity=new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.ModelIdentity(candidate.path("provider").asText(),
                    candidate.path("endpoint").asText(),candidate.path("modelName").asText(),price(candidate,callbacks.length==0),outputConstraint(candidate,callbacks.length==0));
            int retries=Math.max(0,Math.min(candidate.path("maxRetries").asInt(0),5));
            for(int attempt=0;attempt<=retries;attempt++)try{return invoke(selected,executablePrompt,identity,context);}
            catch(io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException denied){throw denied;}
            catch(RuntimeException exception){last=exception;}
        }
        throw last==null?new IllegalStateException("Agent 没有可用模型快照"):last;
    }
    /** Administrative connection checks share the same admission and response accounting boundary. */
    public ChatResponse callProfile(Prompt prompt,JsonNode profile,
            io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.Context context){
        var selected=create(profile);
        var identity=new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.ModelIdentity(profile.path("provider").asText(),
                profile.path("endpoint").asText(),profile.path("modelName").asText(),price(profile,true),outputConstraint(profile,true));
        return invoke(selected,prompt,identity,context);
    }
    private static io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.OutputConstraint outputConstraint(JsonNode profile,boolean singleResponseScope){
        var cap=profile.get("maxOutputTokens");if(cap==null || cap.isNull())return null;
        if(!cap.isIntegralNumber() || !cap.canConvertToInt())throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_OUTPUT_LIMIT_INVALID");
        return new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.OutputConstraint(cap.intValue(),profile.path("outputTokenParameter").asText(""),singleResponseScope);
    }
    private static io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.PriceBinding price(JsonNode profile,boolean supported){
        if(!profile.path("id").isTextual() || !profile.path("versionNo").isIntegralNumber() || !profile.path("versionNo").canConvertToInt())return null;
        JsonNode value=profile.get("pricingJson");
        if(value!=null && !value.isNull() && !value.isTextual())throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_PRICE_PROTOCOL_INVALID");
        return new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.PriceBinding(profile.path("id").textValue(),profile.path("versionNo").intValue(),
                value==null || value.isNull()?null:value.textValue(),supported);
    }
    private ChatResponse invoke(ChatModel model,Prompt prompt,
            io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.ModelIdentity identity,
            io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.Context context) {
        if(identity.output()!=null && prompt.getOptions()!=null && (prompt.getOptions().getMaxTokens()!=null
                || (prompt.getOptions() instanceof OpenAiChatOptions openai && openai.getMaxCompletionTokens()!=null)))
            throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_OUTPUT_OVERRIDE_NOT_ALLOWED");
        return gateway==null?model.call(prompt):gateway.invoke(identity,context,()->model.call(prompt),SnapshotChatModelRouter::usage);
    }
    public static io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.Usage usage(ChatResponse response) {
        if(response==null || response.getMetadata()==null || response.getMetadata().getUsage()==null)
            return io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.Usage.missing();
        var metadata=response.getMetadata();var usage=metadata.getUsage();
        if(usage.getNativeUsage()==null && (usage.getPromptTokens()==null || usage.getPromptTokens()==0)
                && (usage.getCompletionTokens()==null || usage.getCompletionTokens()==0) && (usage.getTotalTokens()==null || usage.getTotalTokens()==0))
            return io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.Usage.missing();
        return new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.Usage(
                usage.getPromptTokens()==null?null:usage.getPromptTokens().longValue(),
                usage.getCompletionTokens()==null?null:usage.getCompletionTokens().longValue(),
                usage.getTotalTokens()==null?null:usage.getTotalTokens().longValue(),metadata.getModel(),usage.getCacheReadInputTokens(),usage.getCacheWriteInputTokens());
    }
    public ChatModel create(JsonNode model) {
        String provider=model.path("provider").asText();
        if(!List.of("SPRING_AI","OPENAI","DASHSCOPE","OLLAMA").contains(provider)) throw new IllegalStateException("当前 Spring AI 运行时不支持模型供应商: "+provider);
        JsonNode cap=model.get("maxOutputTokens");
        String parameter=model.path("outputTokenParameter").asText("");
        if(cap!=null && !cap.isNull()){
            if(!cap.isIntegralNumber() || !cap.canConvertToInt() || cap.intValue()<1 || cap.intValue()>1000000)
                throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_OUTPUT_LIMIT_INVALID");
            if(!List.of("MAX_TOKENS","MAX_COMPLETION_TOKENS").contains(parameter))
                throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_OUTPUT_PARAMETER_INVALID");
        }else if(!parameter.isBlank())throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_OUTPUT_LIMIT_MISSING");
        String apiKey="OLLAMA".equals(provider)&&model.path("secretRef").asText("").isBlank()?"ollama":secrets.resolve(model.path("secretRef").asText());
        var builder=OpenAiChatOptions.builder().baseUrl(model.path("endpoint").asText()).apiKey(apiKey)
                .model(model.path("modelName").asText()).temperature(model.path("temperature").asDouble(0.1))
                .timeout(Duration.ofSeconds(model.path("timeoutSeconds").asInt(60))).maxRetries(0);
        if(cap!=null && !cap.isNull()){
            if("MAX_TOKENS".equals(parameter))builder.maxTokens(cap.intValue());
            else builder.maxCompletionTokens(cap.intValue());
        }
        OpenAiChatOptions options=builder.build();
        return OpenAiChatModel.builder().options(options).build();
    }
    private JsonNode config(JsonNode snapshot){if(snapshot==null)return objectMapper.nullNode();JsonNode config=snapshot.path("configJson");if(config.isTextual())try{return objectMapper.readTree(config.asText());}catch(Exception ignored){return objectMapper.nullNode();}return config;}
}
