package io.github.opensabre.iqc.pipelinebridge;

import com.fasterxml.jackson.databind.*;
import io.github.opensabre.iqc.finding.FindingDefinition;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.result.llm.*;
import io.github.opensabre.iqc.result.llm.gateway.*;
import java.util.*;

/** Dedicated inference through an approved tool, admitted and settled by the original model gateway. */
public final class SmallModelDetectorEngine implements EngineAdapter {
    private final ObjectMapper json;
    private final SnapshotMcpToolProvider tools;
    private final ModelGateway gateway;
    public SmallModelDetectorEngine(ObjectMapper json,SnapshotMcpToolProvider tools,ModelGateway gateway){
        this.json=Objects.requireNonNull(json);this.tools=Objects.requireNonNull(tools);this.gateway=Objects.requireNonNull(gateway);
    }
    @Override public String type(){return "SMALL_MODEL";}
    @Override public NodeResult execute(NodeContext context) throws Exception {
        var config=context.node().config();
        var definition=json.treeToValue(config.path("findingDefinition"),FindingDefinition.class);
        SmallModelContract.validateConfiguration(context.node().version(),config,definition);
        var input=context.input();ValueSchema.checkData(config.path("inputSchema"),input);
        var messages=context.variables().get("__messages");var task=context.variables().get("__task");
        if(messages==null||!messages.isArray()||messages.isEmpty()||messages.size()>10000
                ||input.has("messages")&&!input.get("messages").equals(messages)||task==null||!task.isObject())
            throw new IllegalArgumentException("Trusted processed conversation and task required");
        String conversationId=messages.get(0).path("conversationId").asText();
        for(var message:messages)if(!conversationId.equals(message.path("conversationId").asText()))
            throw new IllegalArgumentException("Inference requires one trusted conversation");
        var provenance=new ModelCallContext(required(task,"id"),required(task,"currentExecutionId"),context.node().nodeId(),
                context.attempt(),required(task,"createdBy"),task.hasNonNull("ownerGroupId")?required(task,"ownerGroupId"):null,conversationId);
        var model=config.path("modelSnapshot");
        var safeModel=json.createObjectNode();
        for(String field:List.of("id","versionNo","provider","modelName","endpoint","deploymentRef","maxOutputTokens","outputTokenParameter"))
            if(model.hasNonNull(field))safeModel.set(field,model.get(field));
        var request=json.createObjectNode().put("schemaVersion","iqc-small-model-request-v1").put("operation",config.path("operation").asText());
        request.set("model",safeModel);request.set("input",input);request.set("messages",messages);
        String hash=PipelineAssetService.hash(json.writeValueAsString(List.of(request,messages)));
        request.put("inputHash",hash);
        var identity=new GatewayCallService.ModelIdentity(model.path("provider").asText(),model.path("endpoint").asText(),model.path("modelName").asText(),
                FrozenModelGatewayMetadata.price(model,true),FrozenModelGatewayMetadata.outputConstraint(model,true),FrozenModelGatewayMetadata.deployment(model));
        var callContext=new GatewayCallService.Context(provenance.executionId()+":"+conversationId+":"+context.node().nodeId()+":"+context.attempt(),
                context.node().nodeId(),provenance.owner(),provenance);
        JsonNode output=gateway.invoke(identity,callContext,()->{
            gateway.assertToolExecutionAllowed(callContext);
            var result=StructuredToolTransport.invoke(json,tools,config,request);
            ValueSchema.checkData(config.path("outputSchema"),result);
            if(!"iqc-small-model-response-v1".equals(result.path("schemaVersion").asText())
                    ||!hash.equals(result.path("inputHash").asText())||!model.path("id").asText().equals(result.path("modelId").asText())
                    ||!result.path("modelVersion").isIntegralNumber()||!result.path("modelVersion").canConvertToInt()
                    ||model.path("versionNo").intValue()!=result.path("modelVersion").intValue()
                    ||!model.path("deploymentRef").asText().equals(result.path("deploymentRef").asText())
                    ||!model.path("modelName").asText().equals(result.path("modelName").asText())
                    ||!config.path("operation").asText().equals(result.path("operation").asText()))
                throw new IllegalArgumentException("Inference receipt differs from frozen request");
            return result;
        },SmallModelDetectorEngine::usage);
        return StructuredToolFindingCodec.decode(json,context,output,provenance.executionId(),hash,type(),SmallModelContract.VERSION);
    }
    private static String required(JsonNode node,String key){
        if(!node.path(key).isTextual()||node.path(key).asText().isBlank())throw new IllegalArgumentException("Trusted inference identity required");
        return node.path(key).asText();
    }
    static GatewayCallService.Usage usage(JsonNode output){
        var usage=output.path("usage");
        if(usage.isMissingNode()||usage.isNull())return GatewayCallService.Usage.missing();
        return new GatewayCallService.Usage(token(usage,"inputTokens"),token(usage,"outputTokens"),token(usage,"totalTokens"),output.path("modelName").asText());
    }
    private static Long token(JsonNode usage,String key){
        var value=usage.get(key);if(value==null||value.isNull())return null;
        if(!value.isIntegralNumber()||!value.canConvertToLong())throw new IllegalArgumentException("Exact inference usage required");
        return value.longValue();
    }
}
