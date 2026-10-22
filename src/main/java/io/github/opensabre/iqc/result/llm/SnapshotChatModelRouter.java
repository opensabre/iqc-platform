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
    private final org.springframework.ai.model.tool.ToolCallingManager toolManager=org.springframework.ai.model.tool.ToolCallingManager.builder().toolExecutionExceptionProcessor(failure->{throw failure;}).build();
    @org.springframework.beans.factory.annotation.Value("${iqc.llm.gateway.max-tool-rounds:8}") private int maxToolRounds=8;
    @org.springframework.beans.factory.annotation.Value("${iqc.llm.gateway.max-tool-calls:64}") private int maxToolCalls=64;
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
                ToolCallingChatOptions.builder().toolCallbacks(guardTools(callbacks,context)).build());
        JsonNode config=config(agentSnapshot);
        boolean managed = io.github.opensabre.iqc.agent.AgentConfiguration.usesManagedAssets(config.path("schemaVersion").asText());
        if ("3.0".equals(config.path("schemaVersion").asText()) && (!config.path("assetSnapshots").isObject() || !config.path("assetSnapshots").path("primaryModel").isObject()))
            throw new IllegalStateException("Agent 缺少已发布的模型资产快照");
        if(!managed || config.path("assetSnapshots").isMissingNode()) {
            if(defaultModel==null) throw new IllegalStateException("系统默认模型未启用");
            var options=defaultModel.getDefaultOptions();
            String model=options==null || options.getModel()==null?"deployment-default":options.getModel();
            String endpoint=options instanceof OpenAiChatOptions openai && openai.getBaseUrl()!=null?openai.getBaseUrl():"deployment-default";
            var identity=new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.ModelIdentity(
                    options instanceof OpenAiChatOptions?"OPENAI":defaultModel.getClass().getSimpleName(),endpoint,model);
            if(callbacks.length>0){
                if(!(defaultModel instanceof OpenAiChatModel))throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_TOOL_RUNTIME_UNSUPPORTED");
                return invokeToolCycle(defaultModel,executablePrompt,identity,context,null);
            }
            return invoke(defaultModel,executablePrompt,identity,context,null);
        }
        List<JsonNode> candidates=new ArrayList<>(); candidates.add(config.path("assetSnapshots").path("primaryModel"));
        config.path("assetSnapshots").path("fallbackModels").forEach(candidates::add);
        RuntimeException last=null;
        for(JsonNode candidate:candidates) {
            ChatModel selected;
            try{selected=create(candidate);}catch(io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException denied){throw denied;}catch(RuntimeException unavailable){last=unavailable;continue;}
            var identity=new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.ModelIdentity(candidate.path("provider").asText(),
                    candidate.path("endpoint").asText(),candidate.path("modelName").asText(),price(candidate,true),outputConstraint(candidate,true),deployment(candidate));
            int retries=Math.max(0,Math.min(candidate.path("maxRetries").asInt(0),5));
            for(int attempt=0;attempt<=retries;attempt++)try{return invokeToolCycle(selected,executablePrompt,identity,context,candidate);}
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
                profile.path("endpoint").asText(),profile.path("modelName").asText(),price(profile,true),outputConstraint(profile,true),deployment(profile));
        return invoke(selected,prompt,identity,context.forToolRound(0),profile);
    }
    private ToolCallback[] guardTools(ToolCallback[] callbacks,io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.Context context){
        return java.util.Arrays.stream(callbacks).map(delegate->new ToolCallback(){
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition(){return delegate.getToolDefinition();}
            public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata(){return delegate.getToolMetadata();}
            public String call(String arguments){if(gateway!=null)gateway.assertToolExecutionAllowed(context);return delegate.call(arguments);}
            public String call(String arguments,org.springframework.ai.chat.model.ToolContext toolContext){
                if(gateway!=null)gateway.assertToolExecutionAllowed(context);return delegate.call(arguments,toolContext);}
        }).toArray(ToolCallback[]::new);
    }
    public record InvocationFingerprint(String schemaVersion,String modelKey,String inputHash) {
        public InvocationFingerprint {
            if(!"iqc-llm-invocation-fingerprint-v1".equals(schemaVersion)||modelKey==null||!modelKey.matches("[a-f0-9]{64}")
                    ||inputHash==null||!inputHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid invocation fingerprint");
        }
    }
    public static InvocationFingerprint invocationFingerprint(ChatResponse response){
        return response instanceof MeteredResponse metered?metered.fingerprint:response instanceof ToolCycleResponse cycle?cycle.fingerprint:null;
    }
    private static final class MeteredResponse extends ChatResponse {
        private final io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.Handle handle;
        private final InvocationFingerprint fingerprint;
        MeteredResponse(ChatResponse response,io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.Handle handle,String inputHash){
            super(response.getResults(),response.getMetadata());this.handle=handle;
            fingerprint=inputHash!=null&&handle!=null&&handle.modelKey()!=null&&handle.modelKey().matches("[a-f0-9]{64}")
                    ?new InvocationFingerprint("iqc-llm-invocation-fingerprint-v1",handle.modelKey(),inputHash):null;
        }
    }
    /** Marker prevents business-output parsing failures from replaying completed external tools. */
    private static final class ToolCycleResponse extends ChatResponse {
        private final InvocationFingerprint fingerprint;
        ToolCycleResponse(ChatResponse response){super(response.getResults(),response.getMetadata());fingerprint=invocationFingerprint(response);}
    }
    public static boolean toolsExecuted(ChatResponse response){return response instanceof ToolCycleResponse;}
    private ChatResponse invokeToolCycle(ChatModel model,Prompt initial,
            io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.ModelIdentity identity,
            io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.Context context,JsonNode profile){
        Prompt next=initial;int executed=0;var toolIds=new java.util.HashSet<String>();
        for(int round=0;;round++){
            ChatResponse response;
            try{response=invoke(model,next,identity,context.forToolRound(round),profile);}
            catch(io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException denied){throw denied;}
            catch(RuntimeException failure){if(round>0)throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_TOOL_CONTINUATION_UNCONFIRMED");throw failure;}
            if(response==null){if(round>0)throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_TOOL_CONTINUATION_EMPTY");return null;}
            if(!response.hasToolCalls())return round==0?response:new ToolCycleResponse(response);
            if(maxToolRounds<1 || maxToolRounds>32 || maxToolCalls<1 || maxToolCalls>256)
                throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_TOOL_POLICY_INVALID");
            if(round>=maxToolRounds)throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_TOOL_ROUND_LIMIT_REACHED");
            long count;
            try{count=response.getResults().stream().mapToLong(x->x.getOutput().getToolCalls().size()).sum();}
            catch(RuntimeException malformed){throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_TOOL_RESPONSE_INVALID");}
            if(response.getResults().size()!=1 || count<1 || count>maxToolCalls-executed)
                throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_TOOL_CALL_LIMIT_REACHED");
            for(var tool:response.getResult().getOutput().getToolCalls()){
                if(tool.id()==null || tool.id().isBlank() || tool.id().length()>128 || tool.id().codePoints().anyMatch(Character::isISOControl)
                        || !toolIds.add(tool.id()))throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_TOOL_ID_INVALID_OR_REPLAYED");
            }
            try{
                if(gateway!=null){
                    if(!(response instanceof MeteredResponse metered))throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_TOOL_CALL_IDENTITY_MISSING");
                    gateway.markToolsStarted(metered.handle,context);
                }
                var result=toolManager.executeToolCalls(next,response);executed+=Math.toIntExact(count);
                if(result.returnDirect())return new ToolCycleResponse(new ChatResponse(org.springframework.ai.model.tool.ToolExecutionResult.buildGenerations(result)));
                next=new Prompt(result.conversationHistory(),next.getOptions());
            }catch(RuntimeException failure){throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_TOOL_EXECUTION_UNCONFIRMED");}
        }
    }
    private static io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.DeploymentDeclaration deployment(JsonNode profile){
        return FrozenModelGatewayMetadata.deployment(profile);
    }
    private static io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.OutputConstraint outputConstraint(JsonNode profile,boolean singleResponseScope){
        return FrozenModelGatewayMetadata.outputConstraint(profile,singleResponseScope);
    }
    private static io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.PriceBinding price(JsonNode profile,boolean supported){
        return FrozenModelGatewayMetadata.price(profile,supported);
    }
    private ChatResponse invoke(ChatModel model,Prompt prompt,
            io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.ModelIdentity identity,
            io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.Context context,JsonNode profile) {
        if(identity.output()!=null && prompt.getOptions()!=null && (prompt.getOptions().getMaxTokens()!=null
                || (prompt.getOptions() instanceof OpenAiChatOptions openai && openai.getMaxCompletionTokens()!=null)))
            throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_OUTPUT_OVERRIDE_NOT_ALLOWED");
        if(gateway==null)return model.call(prompt);
        String inputHash=invocationInputHash(prompt,profile);
        var result=gateway.invokeTracked(identity,context,()->model.call(prompt),SnapshotChatModelRouter::usage,
                ()->diagnosticRequest(prompt),this::diagnosticResponse);
        return result.response()==null?null:new MeteredResponse(result.response(),result.handle(),inputHash);
    }
    /** Best-effort measurement only: never changes admission, retry, model output or diagnostic retention. */
    private String invocationInputHash(Prompt prompt,JsonNode profile){
        if(profile==null||!profile.isObject())return null;
        try{
            var input=new java.util.LinkedHashMap<String,Object>();input.put("schemaVersion","iqc-llm-invocation-input-v1");
            input.put("profile",profile);input.put("request",objectMapper.readTree(diagnosticRequest(prompt)));
            var definitions=new java.util.ArrayList<java.util.Map<String,Object>>();
            if(prompt.getOptions()!=null){
                // Router-generated options have only standard chat settings and explicit tool callbacks.
                if(!prompt.getOptions().getClass().getName().equals("org.springframework.ai.model.tool.DefaultToolCallingChatOptions"))return null;
                var options=(ToolCallingChatOptions)prompt.getOptions();
                var settings=new java.util.LinkedHashMap<String,Object>();
                settings.put("model",options.getModel());settings.put("temperature",options.getTemperature());
                settings.put("maxTokens",options.getMaxTokens());settings.put("topP",options.getTopP());settings.put("topK",options.getTopK());
                settings.put("presencePenalty",options.getPresencePenalty());settings.put("frequencyPenalty",options.getFrequencyPenalty());
                settings.put("stopSequences",options.getStopSequences());input.put("promptOptions",settings);
                if(options.getToolCallbacks()!=null)for(var callback:options.getToolCallbacks()){
                    var definition=callback.getToolDefinition();var row=new java.util.LinkedHashMap<String,Object>();
                    row.put("name",definition.name());row.put("description",definition.description());row.put("inputSchema",definition.inputSchema());definitions.add(row);
                }
            }
            input.put("toolDefinitions",definitions);
            return io.github.opensabre.iqc.selector.SelectorAssetService.hash(objectMapper.valueToTree(input).toString());
        }catch(RuntimeException|java.io.IOException unavailable){return null;}
    }
    private String diagnosticRequest(Prompt prompt){
        var messages=new java.util.ArrayList<java.util.Map<String,Object>>();
        for(var message:prompt.getInstructions()){
            var item=new java.util.LinkedHashMap<String,Object>();
            item.put("role",message.getMessageType().toString());item.put("text",message.getText());
            if(message instanceof org.springframework.ai.chat.messages.AssistantMessage assistant)item.put("toolCalls",assistant.getToolCalls());
            if(message instanceof org.springframework.ai.chat.messages.ToolResponseMessage tool)item.put("toolResponses",tool.getResponses());
            messages.add(item);
        }
        return objectMapper.valueToTree(java.util.Map.of("source","FRAMEWORK_MESSAGES","messages",messages)).toString();
    }
    private String diagnosticResponse(ChatResponse response){
        if(response==null)return null;
        var generations=response.getResults().stream().map(generation->{
            var item=new java.util.LinkedHashMap<String,Object>();item.put("text",generation.getOutput().getText());
            item.put("toolCalls",generation.getOutput().getToolCalls());return item;
        }).toList();
        return objectMapper.valueToTree(java.util.Map.of("source","FRAMEWORK_GENERATIONS","generations",generations)).toString();
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
        var declaration=deployment(model);
        if(declaration!=null && declaration.contextWindowTokens()!=null && cap!=null && !cap.isNull() && cap.intValue()>declaration.contextWindowTokens())
            throw new io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException("MODEL_OUTPUT_EXCEEDS_CONTEXT_WINDOW");
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
