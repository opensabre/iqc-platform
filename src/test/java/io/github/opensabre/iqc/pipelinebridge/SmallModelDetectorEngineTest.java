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
    private void response(boolean wrongDeployment){
        when(client.callTool(any())).thenAnswer(invocation->{
            var request=invocation.getArgument(0,McpSchema.CallToolRequest.class);
            assertThat(((Map<?,?>)request.arguments().get("model")).containsKey("secretRef")).isFalse();
            var messages=(List<?>)request.arguments().get("messages");
            assertThat(messages).hasSize(1);
            assertThat(((Map<?,?>)messages.getFirst()).get("content")).isEqualTo("Processed conversation");
            var output=new HashMap<String,Object>();
            output.put("schemaVersion","iqc-small-model-response-v1");output.put("inputHash",request.arguments().get("inputHash"));
            output.put("modelId","model");output.put("modelVersion",3);output.put("modelName","classifier");
            output.put("deploymentRef",wrongDeployment?"other":"classifier-v3");output.put("operation","CLASSIFICATION");
            output.put("state","KNOWN");output.put("value",true);output.put("reason","Classified");
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
}
