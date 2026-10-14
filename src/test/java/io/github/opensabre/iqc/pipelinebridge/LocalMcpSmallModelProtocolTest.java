package io.github.opensabre.iqc.pipelinebridge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.result.llm.SnapshotMcpToolProvider;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.opensabre.iqc.result.llm.gateway.*;
import java.util.function.*;

/** Real SDK loopback transport and dedicated adapter; gateway admission/settlement remains a mock. */
class LocalMcpSmallModelProtocolTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void realSdkCarriesProcessedConversationAndExactDeployment() throws Exception {
        var calls=new AtomicInteger();var initializes=new AtomicInteger();var lists=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/mcp",exchange->{
            try {
                if(!"POST".equals(exchange.getRequestMethod())){exchange.sendResponseHeaders(405,-1);return;}
                var request=json.readTree(exchange.getRequestBody());
                if(!request.has("id")){exchange.sendResponseHeaders(202,-1);return;}
                var response=json.createObjectNode().put("jsonrpc","2.0");response.set("id",request.get("id"));
                var result=response.putObject("result");
                switch(request.path("method").asText()) {
                    case "initialize" -> {
                        initializes.incrementAndGet();result.put("protocolVersion",request.path("params").path("protocolVersion").asText());
                        result.putObject("capabilities").putObject("tools");result.putObject("serverInfo").put("name","local-fixture").put("version","1");
                    }
                    case "tools/list" -> {
                        lists.incrementAndGet();var tool=result.putArray("tools").addObject().put("name","inspect");
                        tool.putObject("inputSchema").put("type","object");tool.putObject("outputSchema").put("type","object");
                        tool.putObject("annotations").put("readOnlyHint",true).put("destructiveHint",false);
                    }
                    case "tools/call" -> {
                        calls.incrementAndGet();var args=request.path("params").path("arguments");
                        if(!"inspect".equals(request.path("params").path("name").asText()))throw new IllegalStateException("Unexpected tool");
                        result.put("isError",false);result.putArray("content");var output=result.putObject("structuredContent");
                        if(args.path("model").has("secretRef")||!"Processed conversation".equals(args.path("messages").path(0).path("content").asText()))
                            throw new IllegalStateException("Unsafe or missing inference input");
                        output.put("schemaVersion","iqc-small-model-response-v1").put("modelId",args.path("model").path("id").asText())
                                .put("modelVersion",args.path("model").path("versionNo").intValue()).put("inputHash",args.path("inputHash").asText())
                                .put("modelName",args.path("model").path("modelName").asText()).put("deploymentRef",args.path("model").path("deploymentRef").asText())
                                .put("operation",args.path("operation").asText()).put("state","KNOWN").put("value",true).put("reason","Fixture inference");
                        output.putObject("usage").put("inputTokens",10).put("outputTokens",2).put("totalTokens",12);
                        output.putArray("evidence").addObject().put("reference","fixture:record:1");
                    }
                    default -> throw new IllegalStateException("Unexpected method");
                }
                byte[] body=json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            } finally {exchange.close();}
        });
        server.start();
        try {
            var context=new SmallModelDetectorEngineTest().context();
            var config=(ObjectNode)context.node().config();var mcp=(ObjectNode)config.get("mcpSnapshot");
            mcp.put("endpoint","http://127.0.0.1:"+server.getAddress().getPort()+"/mcp").put("transport","STREAMABLE_HTTP").put("authType","NONE").put("timeoutSeconds",5);
            var node=context.node();
            var configured=new PipelineDefinition.Node(node.nodeId(),node.type(),node.name(),node.version(),node.dependsOn(),node.inputMapping(),config,node.condition(),node.timeoutMs(),node.retries(),node.onError(),node.fallbackNodeId(),node.outputSchema(),node.enabled());
            var gateway=mock(ModelGateway.class);
            doAnswer(invocation->{
                JsonNode output=invocation.<Supplier<JsonNode>>getArgument(2).get();
                var usage=invocation.<Function<JsonNode,GatewayCallService.Usage>>getArgument(3).apply(output);
                assertThat(usage.status()).isEqualTo("COMPLETE");assertThat(usage.totalTokens()).isEqualTo(12);
                return output;
            }).when(gateway).invoke(any(),any(),any(),any());
            var engine=new SmallModelDetectorEngine(json,new SnapshotMcpToolProvider(json,ref->{throw new AssertionError("No secret in fixture");},"server:1:inspect"),gateway);
            var result=engine.execute(new NodeContext(configured,context.input(),context.variables(),context.upstream(),context.mode(),context.attempt()));
            assertThat(result.status()).isEqualTo(NodeResult.Status.SUCCESS);assertThat(result.findings().getFirst().value().booleanValue()).isTrue();
            assertThat(initializes.get()).isEqualTo(1);assertThat(lists.get()).isEqualTo(1);assertThat(calls.get()).isEqualTo(1);
            assertThat(result.findings().getFirst().evidence().getFirst().reference()).isEqualTo("fixture:record:1");
        } finally {server.stop(0);}
    }
}
