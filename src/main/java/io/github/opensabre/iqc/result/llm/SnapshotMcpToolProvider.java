package io.github.opensabre.iqc.result.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Creates request-scoped MCP callbacks from immutable Agent 2.0 snapshots. */
@Component
public class SnapshotMcpToolProvider {
    private final ObjectMapper objectMapper;
    private final SecretReferenceResolver secrets;

    public SnapshotMcpToolProvider(ObjectMapper objectMapper, SecretReferenceResolver secrets) {
        this.objectMapper = objectMapper;
        this.secrets = secrets;
    }

    /** Opens snapshotted MCP clients only for AGENT_LLM and closes all clients after the model call. */
    public Session open(JsonNode agentSnapshot) {
        JsonNode config = config(agentSnapshot);
        if (!"2.0".equals(config.path("schemaVersion").asText())
                || !"AGENT_LLM".equals(config.path("mode").asText())) return Session.empty();
        List<McpSyncClient> clients = new ArrayList<>();
        List<ToolCallback> callbacks = new ArrayList<>();
        try {
            for (JsonNode server : config.path("assetSnapshots").path("mcpServers")) {
                McpSyncClient client = createClient(server);
                client.initialize();
                clients.add(client);
                Set<String> allowed = allowedTools(server.path("allowedToolsJson").asText("[]"));
                for (McpSchema.Tool tool : client.listTools().tools()) {
                    // Empty means deny-all: a published Agent must explicitly authorize every callable tool.
                    if (!allowed.contains(tool.name())) continue;
                    callbacks.add(callback(server.path("code").asText("mcp"), client, tool));
                }
            }
            return new Session(callbacks.toArray(ToolCallback[]::new), clients);
        } catch (RuntimeException exception) {
            clients.forEach(this::closeQuietly);
            throw exception;
        }
    }

    protected McpSyncClient createClient(JsonNode server) {
        Duration timeout = Duration.ofSeconds(Math.max(1, server.path("timeoutSeconds").asInt(30)));
        McpClientTransport transport = transport(server, timeout);
        return McpClient.sync(transport).requestTimeout(timeout).initializationTimeout(timeout).build();
    }

    private McpClientTransport transport(JsonNode server, Duration timeout) {
        String endpoint = server.path("endpoint").asText();
        var requestCustomizer = (java.util.function.Consumer<HttpRequest.Builder>) builder -> applyAuth(builder, server);
        if ("SSE".equals(server.path("transport").asText())) {
            return HttpClientSseClientTransport.builder(endpoint).connectTimeout(timeout)
                    .customizeRequest(requestCustomizer).build();
        }
        return HttpClientStreamableHttpTransport.builder(endpoint).connectTimeout(timeout)
                .customizeRequest(requestCustomizer).build();
    }

    private void applyAuth(HttpRequest.Builder request, JsonNode server) {
        String authType = server.path("authType").asText("NONE");
        if ("NONE".equals(authType)) return;
        String secret = secrets.resolve(server.path("secretRef").asText());
        if ("BEARER".equals(authType)) {
            request.header("Authorization", "Bearer " + secret);
            return;
        }
        try {
            Map<String, String> headers = objectMapper.readValue(secret, new TypeReference<>() { });
            headers.forEach(request::header);
        } catch (Exception exception) {
            throw new IllegalArgumentException("MCP 自定义请求头密钥必须是 JSON 对象", exception);
        }
    }

    private ToolCallback callback(String serverCode, McpSyncClient client, McpSchema.Tool tool) {
        String exposedName = sanitize(serverCode) + "__" + sanitize(tool.name());
        ToolDefinition definition = ToolDefinition.builder().name(exposedName)
                .description("MCP[" + serverCode + "] " + (tool.description() == null ? tool.name() : tool.description()))
                .inputSchema(writeJson(tool.inputSchema())).build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String arguments) {
                try {
                    Map<String, Object> values = objectMapper.readValue(arguments, new TypeReference<>() { });
                    return writeJson(client.callTool(new McpSchema.CallToolRequest(tool.name(), values)));
                } catch (RuntimeException exception) {
                    throw exception;
                } catch (Exception exception) {
                    throw new IllegalArgumentException("MCP 工具参数不是合法 JSON", exception);
                }
            }
        };
    }

    private Set<String> allowedTools(String json) {
        try {
            JsonNode node = objectMapper.readTree(json == null || json.isBlank() ? "[]" : json);
            Set<String> names = new HashSet<>();
            node.forEach(item -> names.add(item.asText()));
            return names;
        } catch (Exception exception) {
            throw new IllegalArgumentException("MCP 工具白名单不是合法 JSON 数组", exception);
        }
    }

    private JsonNode config(JsonNode snapshot) {
        if (snapshot == null) return objectMapper.nullNode();
        JsonNode config = snapshot.path("configJson");
        if (!config.isTextual()) return config;
        try { return objectMapper.readTree(config.asText()); }
        catch (Exception exception) { throw new IllegalArgumentException("Agent 配置快照不是合法 JSON", exception); }
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("MCP 数据序列化失败", exception); }
    }
    private static String sanitize(String value) { return value.replaceAll("[^a-zA-Z0-9_-]", "_"); }
    private void closeQuietly(McpSyncClient client) { try { client.close(); } catch (RuntimeException ignored) { } }

    public static final class Session implements AutoCloseable {
        private final ToolCallback[] callbacks;
        private final List<McpSyncClient> clients;
        private Session(ToolCallback[] callbacks, List<McpSyncClient> clients) { this.callbacks = callbacks; this.clients = clients; }
        private static Session empty() { return new Session(new ToolCallback[0], List.of()); }
        public ToolCallback[] callbacks() { return callbacks.clone(); }
        @Override public void close() { clients.forEach(client -> { try { client.close(); } catch (RuntimeException ignored) { } }); }
    }
}
