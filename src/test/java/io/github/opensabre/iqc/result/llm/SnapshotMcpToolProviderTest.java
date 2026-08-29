package io.github.opensabre.iqc.result.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SnapshotMcpToolProviderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesOnlyAllowedToolsAndForwardsCalls() throws Exception {
        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object",
                Map.of("query", Map.of("type", "string")), List.of("query"), false, null, null);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(
                new McpSchema.Tool("search", null, "Search records", schema, null, null, null),
                new McpSchema.Tool("delete", null, "Delete records", schema, null, null, null)), null));
        when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult("found", false));
        SnapshotMcpToolProvider provider = new SnapshotMcpToolProvider(objectMapper, reference -> "token") {
            @Override protected McpSyncClient createClient(JsonNode server) { return client; }
        };

        try (SnapshotMcpToolProvider.Session session = provider.open(snapshot("AGENT_LLM"))) {
            ToolCallback[] callbacks = session.callbacks();
            assertEquals(1, callbacks.length);
            assertEquals("records__search", callbacks[0].getToolDefinition().name());
            assertTrue(callbacks[0].call("{\"query\":\"A-1\"}").contains("found"));
        }

        verify(client).initialize();
        verify(client).callTool(new McpSchema.CallToolRequest("search", Map.of("query", "A-1")));
        verify(client).close();
    }

    @Test
    void doesNotOpenMcpOutsideAgentMode() throws Exception {
        McpSyncClient client = mock(McpSyncClient.class);
        SnapshotMcpToolProvider provider = new SnapshotMcpToolProvider(objectMapper, reference -> "token") {
            @Override protected McpSyncClient createClient(JsonNode server) { return client; }
        };
        try (SnapshotMcpToolProvider.Session session = provider.open(snapshot("RULE_THEN_LLM"))) {
            assertEquals(0, session.callbacks().length);
        }
        verify(client, never()).initialize();
    }

    private JsonNode snapshot(String mode) throws Exception {
        return objectMapper.readTree("""
                {"configJson":{"schemaVersion":"2.0","mode":"%s","assetSnapshots":{"mcpServers":[{
                  "code":"records","transport":"STREAMABLE_HTTP","endpoint":"https://mcp.example.com/mcp",
                  "authType":"BEARER","secretRef":"secrets/mcp","timeoutSeconds":10,
                  "allowedToolsJson":"[\\\"search\\\"]"
                }]}}}
                """.formatted(mode));
    }
}
