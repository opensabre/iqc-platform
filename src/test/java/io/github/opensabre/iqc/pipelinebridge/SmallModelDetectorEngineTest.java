package io.github.opensabre.iqc.pipelinebridge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.result.llm.SnapshotMcpToolProvider;
import io.github.opensabre.iqc.result.llm.gateway.*;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmallModelDetectorEngineTest {
    private final ObjectMapper json=new ObjectMapper();
    private final McpSyncClient client=mock(McpSyncClient.class);
    private final ModelGateway gateway=mock(ModelGateway.class);
    private GatewayCallService.Context admission;
    private GatewayCallService.Usage settled;
    private SmallModelDetectorEngine engine(){
        var schema=new McpSchema.JsonSchema("object",null,null,null,null,null);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(new McpSchema.Tool("inspect",null,null,schema,
                Map.of("type","object"),new McpSchema.ToolAnnotations(null,true,false,true,true,false),null)),null));
        doAnswer(invocation->{
            admission=invocation.getArgument(1);
            JsonNode result=invocation.<Supplier<JsonNode>>getArgument(2).get();
            settled=invocation.<Function<JsonNode,GatewayCallService.Usage>>getArgument(3).apply(result);
            return result;
        }).when(gateway).invoke(any(),any(),any(),any());
        return new SmallModelDetectorEngine(json,new SnapshotMcpToolProvider(json,ref->"","server:1:inspect"){
            @Override protected McpSyncClient createClient(JsonNode snapshot){return client;}
        },gateway);
    }
    NodeContext context() throws Exception {
        var original=new SkillApiDetectorEngineTest().context(json.createObjectNode(),Map.of());
        var config=(ObjectNode)original.node().config().deepCopy();config.remove("skillSnapshot");config.put("operation","CLASSIFICATION");
        config.set("modelSnapshot",json.createObjectNode().put("id","model").put("versionNo",3).put("provider","OLLAMA")
                .put("modelName","classifier").put("endpoint","https://models.example.test").put("deploymentRef","classifier-v3").put("secretRef","env:PRIVATE"));
        config.set("inputSchema",json.createObjectNode().put("type","object"));config.set("outputSchema",json.createObjectNode().put("type","object"));
        var node=new PipelineDefinition.Node("model","SMALL_MODEL","Classifier",SmallModelContract.VERSION,List.of(),Map.of(),config,null,3000,0,
                PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
        var messages=json.createArrayNode();messages.addObject().put("conversationId","conversation").put("content","Processed conversation");
        return new NodeContext(node,json.createObjectNode(),Map.of("__messages",messages,
                "__task",json.createObjectNode().put("id","task").put("currentExecutionId","execution").put("createdBy","owner")),Map.of(),PipelineRuntime.Mode.EVALUATION,1);
    }
    private void response(boolean wrongDeployment){response(wrongDeployment,"CLASSIFICATION",true);}
    private void response(boolean wrongDeployment,String operation,Object value){response(wrongDeployment,operation,value,null);}
    private void response(boolean wrongDeployment,String operation,Object value,Double confidence){
        when(client.callTool(any())).thenAnswer(invocation->{
            var request=invocation.getArgument(0,McpSchema.CallToolRequest.class);
            assertThat(((Map<?,?>)request.arguments().get("model")).containsKey("secretRef")).isFalse();
            var messages=(List<?>)request.arguments().get("messages");
            assertThat(messages).hasSize(1);
            assertThat(((Map<?,?>)messages.getFirst()).get("content")).isEqualTo("Processed conversation");
            var output=new HashMap<String,Object>();
            output.put("schemaVersion","iqc-small-model-response-v1");output.put("inputHash",request.arguments().get("inputHash"));
            output.put("modelId","model");output.put("modelVersion",3);output.put("modelName","classifier");
            output.put("deploymentRef",wrongDeployment?"other":"classifier-v3");output.put("operation",operation);
            output.put("state","KNOWN");output.put("value",value);output.put("reason","Classified");
            if(confidence!=null)output.put("confidence",confidence);
            output.put("evidence",List.of(Map.of("reference","inference:1")));
            output.put("usage",Map.of("inputTokens",10,"outputTokens",2,"totalTokens",12));
            return new McpSchema.CallToolResult(List.of(),false,output,null);
        });
    }
    @Test void inferenceUsesOriginalGatewayProvenanceAndExcludesCredentials() throws Exception {
        var engine=engine();response(false);var result=engine.execute(context());
        assertThat(result.findings().getFirst().source().engineType()).isEqualTo("SMALL_MODEL");
        assertThat(admission.execution().taskId()).isEqualTo("task");assertThat(admission.execution().conversationId()).isEqualTo("conversation");
        assertThat(settled.status()).isEqualTo("COMPLETE");assertThat(settled.totalTokens()).isEqualTo(12);
        verify(gateway).assertToolExecutionAllowed(admission);verify(client).close();
    }
    @Test void deploymentMismatchCannotProduceCanonicalFact() throws Exception {
        var engine=engine();response(true);
        assertThatThrownBy(()->engine.execute(context())).hasMessageContaining("Inference receipt differs");
        assertThat(settled).isNull();verify(client).close();
    }
    @Test void gatewayRefusalPreventsAnyRemoteInitialization() throws Exception {
        var engine=engine();doThrow(new IllegalStateException("capacity denied")).when(gateway).invoke(any(),any(),any(),any());
        assertThatThrownBy(()->engine.execute(context())).hasMessageContaining("capacity denied");
        verify(client,never()).initialize();verify(client,never()).callTool(any());
    }
    @Test void dedicatedOperationsPreserveTheirStructuredValueTypes() throws Exception {
        for(var entry:List.of(new Object[]{"CLASSIFICATION","ENUM","positive"},new Object[]{"NER","OBJECT",Map.of("entities",List.of("Acme"))},
                new Object[]{"EMOTION","STRING","positive"},new Object[]{"INTENT","ENUM","positive"},new Object[]{"SEMANTIC_MATCHING","NUMBER",0.75})) {
            reset(client,gateway);
            var engine=engine();var context=context();var config=(ObjectNode)context.node().config();config.put("operation",(String)entry[0]);
            var definition=(ObjectNode)config.path("findingDefinition");definition.put("valueType",(String)entry[1]);
            if("ENUM".equals(entry[1]))definition.putArray("allowedValues").add("positive").add("negative");
            var node=context.node();var configured=new PipelineDefinition.Node(node.nodeId(),node.type(),node.name(),node.version(),node.dependsOn(),node.inputMapping(),
                    config,node.condition(),node.timeoutMs(),node.retries(),node.onError(),node.fallbackNodeId(),node.outputSchema(),node.enabled());
            response(false,(String)entry[0],entry[2]);var result=engine.execute(new NodeContext(configured,context.input(),context.variables(),context.upstream(),context.mode(),context.attempt()));
            assertThat(result.findings().getFirst().value()).isEqualTo(json.valueToTree(entry[2]));
            assertThat(result.findings().getFirst().valueType().name()).isEqualTo(entry[1]);
        }
    }
    @Test void calibratedInferencePublishesTransformedConfidenceToRuntimeAndFusion() throws Exception {
        var engine=engine();response(false,"CLASSIFICATION",true,0.75);
        var context=context();
        var task=io.github.opensabre.iqc.quality.evaluation.CalibrationBridgeFixture.freeze(json,context.node());
        var variables=new HashMap<>(context.variables());variables.put("__task",json.valueToTree(task));
        var graph=new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(context.node()));
        try(var runtime=new PipelineRuntime(List.of(engine),1,2)) {
            var run=runtime.execute(graph,context.input(),variables,PipelineRuntime.Mode.EVALUATION);
            assertThat(run.status()).isEqualTo(PipelineRuntime.Status.SUCCESS);
            assertThat(run.findings()).hasSize(1);
            assertThat(run.findings().getFirst().confidence()).isEqualByComparingTo("0.5");
            var trace=run.trace().getFirst().result();
            var invocation=json.treeToValue(trace.metrics().get("modelInvocation"),io.github.opensabre.iqc.result.llm.SnapshotChatModelRouter.InvocationFingerprint.class);
            assertThat(invocation.deploymentRef()).isEqualTo("classifier-v3");
            assertThat(invocation.modelKey()).matches("[a-f0-9]{64}");
            assertThat(invocation.inputHash()).isEqualTo(run.findings().getFirst().source().inputHash());
            assertThat(json.readValue(json.writeValueAsString(trace),NodeResult.class).metrics().get("modelInvocation"))
                    .isEqualTo(trace.metrics().get("modelInvocation"));
            assertThat(trace.metrics().get(io.github.opensabre.iqc.quality.evaluation.CalibrationRuntime.KEY)
                    .path("rawConfidence").decimalValue()).isEqualByComparingTo("0.75");
            io.github.opensabre.iqc.quality.evaluation.CalibrationRuntime.verify(task,run,json);
            var root=io.github.opensabre.iqc.pipelineasset.PipelineAssetService.readFrozenTree(json,task.getRuleSnapshotJson());
            var policy=json.treeToValue(root.path("pipelineSnapshot").path("release").path("fusion"),io.github.opensabre.iqc.fusion.FusionPolicy.class);
            var fused=io.github.opensabre.iqc.fusion.FindingFusion.fuse(run.findings(),policy).getFirst();
            assertThat(fused.finalConfidence()).isEqualByComparingTo("0.5");
            assertThat(fused.value().booleanValue()).isTrue();
            assertThat(settled.totalTokens()).isEqualTo(12);
        }
    }

}
