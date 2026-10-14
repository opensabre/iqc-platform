package io.github.opensabre.iqc.pipelinebridge;

import com.fasterxml.jackson.databind.*;
import io.github.opensabre.iqc.finding.*;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.result.llm.SnapshotMcpToolProvider;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs the actual adapter and approval provider; only the remote MCP client is a fixture. */
class SkillApiDetectorEngineTest {
    private final ObjectMapper json=new ObjectMapper();
    private final McpSyncClient client=mock(McpSyncClient.class);
    private SkillApiDetectorEngine engine() throws Exception {
        var schema=new McpSchema.JsonSchema("object",null,null,null,null,null);
        var output=Map.<String,Object>of("type","object");
        var hints=new McpSchema.ToolAnnotations(null,true,false,true,true,false);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(new McpSchema.Tool("inspect",null,null,schema,output,hints,null)),null));
        return new SkillApiDetectorEngine(json,new SnapshotMcpToolProvider(json,ref->"","server:1:inspect") {
            @Override protected McpSyncClient createClient(JsonNode snapshot){return client;}
        });
    }
    NodeContext context(JsonNode input,Map<String,JsonNode> vars) throws Exception {
        var config=json.createObjectNode().put("toolName","inspect");
        config.set("mcpSnapshot",json.createObjectNode().put("id","server").put("versionNo",1).put("allowedToolsJson","[\"inspect\"]"));
        config.set("skillSnapshot",json.createObjectNode().put("id","skill").put("code","CHECK").put("versionNo",3)
                .put("instructions","Check the business record").put("inputSchemaJson","{\"type\":\"object\"}")
                .put("outputSchemaJson","{\"type\":\"object\"}"));
        config.set("toolInputSchema",json.readTree("{\"type\":\"object\"}"));
        config.set("toolOutputSchema",json.readTree("{\"type\":\"object\"}"));
        config.set("findingDefinition",json.valueToTree(new FindingDefinition(FindingDefinition.SCHEMA,"BUSINESS_OK","Business","BUSINESS",null,
                Finding.ValueType.BOOLEAN,List.of(),Finding.Severity.LOW,true,List.of(Finding.EvidenceType.EXTERNAL_RESULT))));
        var node=new PipelineDefinition.Node("skill","SKILL_API","Skill",SkillApiDetectorEngine.VERSION,List.of(),Map.of(),config,null,3000,0,
                PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
        return new NodeContext(node,input,vars,Map.of(),PipelineRuntime.Mode.EVALUATION,1);
    }
    Map<String,JsonNode> vars(){return Map.of("__messages",json.createArrayNode(),"__task",json.createObjectNode().put("currentExecutionId","execution"));}
    @Test void structuredEvidenceProducesFindingWithOriginalExecutionSource() throws Exception {
        var engine=engine();
        var output=Map.of("state","KNOWN","value",true,"reason","Record confirmed","evidence",List.of(Map.of("reference","record:1","details",Map.of("version",2))));
        when(client.callTool(any())).thenAnswer(invocation->{
            var request=invocation.getArgument(0,McpSchema.CallToolRequest.class);
            var response=new HashMap<String,Object>(output);
            response.put("schemaVersion","iqc-skill-response-v1");response.put("skillId","skill");response.put("skillVersion",3);
            response.put("inputHash",request.arguments().get("inputHash"));
            return new McpSchema.CallToolResult(List.of(),false,response,null);
        });
        var result=engine.execute(context(json.createObjectNode().put("recordId","1"),vars()));
        assertThat(result.status()).isEqualTo(NodeResult.Status.SUCCESS);
        var fact=result.findings().getFirst();assertThat(fact.value().booleanValue()).isTrue();
        assertThat(fact.source().engineType()).isEqualTo("SKILL_API");assertThat(fact.source().inputHash()).matches("[0-9a-f]{64}");
        assertThat(fact.evidence().getFirst().reference()).isEqualTo("record:1");
        verify(client).close();
    }
    @Test void replacedTranscriptFailsBeforeRemoteInitialization() throws Exception {
        var engine=engine();var input=json.createObjectNode();input.putArray("messages").add("forged");
        assertThatThrownBy(()->engine.execute(context(input,vars()))).hasMessageContaining("Trusted processed conversation");
        verify(client,never()).initialize();verify(client,never()).callTool(any());
    }
    @Test void originalRuntimeKeepsRemoteFailureWithoutCanonicalFacts() throws Exception {
        var engine=engine();when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult("failure",true));
        var input=json.createObjectNode();var context=context(input,vars());
        try(var runtime=new PipelineRuntime(List.of(engine),1,2)) {
            var run=runtime.execute(new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(context.node())),input,vars(),PipelineRuntime.Mode.EVALUATION);
            assertThat(run.status()).isEqualTo(PipelineRuntime.Status.FAILED);
            assertThat(run.findings()).isEmpty();
            assertThat(run.variables()).doesNotContainKey("skill_value");
        }
    }
    @Test void remoteErrorCannotPublishPassingFact() throws Exception {
        var engine=engine();when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult("failure",true));
        assertThatThrownBy(()->engine.execute(context(json.createObjectNode(),vars()))).hasMessageContaining("successful structured MCP output");
        verify(client).close();
    }
    @Test void wrongInputHashRejectsOtherwiseValidCanonicalOutput() throws Exception {
        var engine=engine();
        var output=Map.of("schemaVersion","iqc-skill-response-v1","skillId","skill","skillVersion",3,"inputHash","wrong",
                "state","KNOWN","value",true,"reason","Cached record","evidence",List.of(Map.of("reference","record:1")));
        when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult(List.of(),false,output,null));
        assertThatThrownBy(()->engine.execute(context(json.createObjectNode(),vars()))).hasMessageContaining("identity differs");
        verify(client).close();
    }
}
