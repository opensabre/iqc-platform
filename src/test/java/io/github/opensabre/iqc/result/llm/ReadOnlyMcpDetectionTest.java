package io.github.opensabre.iqc.result.llm;

import com.fasterxml.jackson.databind.*;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Checks deployment capability and frozen protocol before any detection tool invocation. */
class ReadOnlyMcpDetectionTest {
    private final ObjectMapper json=new ObjectMapper();
    private JsonNode server() throws Exception {
        return json.readTree("{\"id\":\"records\",\"versionNo\":2,\"code\":\"records\",\"allowedToolsJson\":\"[\\\"search\\\"]\"}");
    }
    private JsonNode schema() throws Exception {return json.readTree("{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}");}
    private SnapshotMcpToolProvider provider(McpSyncClient client,String approvals) {
        return new SnapshotMcpToolProvider(json,ref->{throw new AssertionError("No secret needed in fixture");},approvals){
            @Override protected McpSyncClient createClient(JsonNode server){return client;}
        };
    }
    @Test void defaultAndWrongVersionApprovalRejectBeforeClientInitialization() throws Exception {
        var client=mock(McpSyncClient.class);var server=server();var schema=schema();
        for(var approvals:List.of("","records:1:search","records:2:delete"))
            assertThatThrownBy(()->provider(client,approvals).openReadOnlyTool(server,"search",schema,schema)).hasMessageContaining("not approved");
        verifyNoInteractions(client);
    }
    @Test void approvedToolHasOneCallbackAndClosesExistingClient() throws Exception {
        var client=mock(McpSyncClient.class);var schema=schema();
        var input=new McpSchema.JsonSchema("object",Map.of(),List.of(),false,null,null);
        var output=Map.<String,Object>of("type","object","properties",Map.of(),"required",List.of(),"additionalProperties",false);
        var hints=new McpSchema.ToolAnnotations(null,true,false,true,true,false);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(new McpSchema.Tool("search",null,null,input,output,hints,null)),null));
        try(var session=provider(client,"records:2:search").openReadOnlyTool(server(),"search",schema,schema)) {
            assertThat(session.callbacks()).hasSize(1);
        }
        verify(client).initialize();verify(client).close();verify(client,never()).callTool(any());
    }
    @Test void failedInitializationClosesClientWithoutToolCall() throws Exception {
        var client=mock(McpSyncClient.class);when(client.initialize()).thenThrow(new IllegalStateException("fixture"));
        var schema=schema();var server=server();
        assertThatThrownBy(()->provider(client,"records:2:search").openReadOnlyTool(server,"search",schema,schema)).hasMessage("fixture");
        verify(client).close();verify(client,never()).callTool(any());
    }
    @Test void capabilityKeySeparatorsCannotAliasAnotherApprovedIdentity() throws Exception {
        var client=mock(McpSyncClient.class);var schema=schema();
        var server=(com.fasterxml.jackson.databind.node.ObjectNode)server();server.put("id","records:2");server.put("versionNo",1);
        assertThatThrownBy(()->provider(client,"records:2:1:search").openReadOnlyTool(server,"search",schema,schema))
                .hasMessageContaining("identity required");
        verifyNoInteractions(client);
    }
    @Test void changedSchemaAndWriteHintRejectAndCloseBeforeAnyToolCall() throws Exception {
        var schema=schema();var server=server();
        var input=new McpSchema.JsonSchema("object",Map.of(),List.of(),false,null,null);
        var output=Map.<String,Object>of("type","object","properties",Map.of(),"required",List.of(),"additionalProperties",false);
        for(boolean readOnly:List.of(true,false)) {
            var client=mock(McpSyncClient.class);
            var hints=new McpSchema.ToolAnnotations(null,readOnly,!readOnly,true,true,false);
            when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(new McpSchema.Tool("search",null,null,input,
                    readOnly?Map.of("type","string"):output,hints,null)),null));
            assertThatThrownBy(()->provider(client,"records:2:search").openReadOnlyTool(server,"search",schema,schema))
                    .hasMessageContaining(readOnly?"differs from frozen":"read-only declaration");
            verify(client).close();verify(client,never()).callTool(any());
        }
    }
}
