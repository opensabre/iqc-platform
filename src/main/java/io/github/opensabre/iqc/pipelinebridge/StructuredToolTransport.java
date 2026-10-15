package io.github.opensabre.iqc.pipelinebridge;

import com.fasterxml.jackson.databind.*;
import io.github.opensabre.iqc.pipeline.ValueSchema;
import io.github.opensabre.iqc.result.llm.SnapshotMcpToolProvider;
import java.nio.charset.StandardCharsets;

/** Shared bounded application payload and structured output checks for approved read-only tools. */
final class StructuredToolTransport {
    private StructuredToolTransport() { }
    static JsonNode invoke(ObjectMapper json,SnapshotMcpToolProvider tools,JsonNode config,JsonNode request) {
        try {
        ValueSchema.checkData(config.path("toolInputSchema"),request);
        String body=json.writeValueAsString(request);
        if(body.getBytes(StandardCharsets.UTF_8).length>65536)throw new IllegalArgumentException("Structured tool request too large");
        JsonNode output;
        try(var session=tools.openReadOnlyTool(config.path("mcpSnapshot"),config.path("toolName").asText(),config.path("toolInputSchema"),config.path("toolOutputSchema"))) {
            String response=session.callbacks()[0].call(body);
            if(response.getBytes(StandardCharsets.UTF_8).length>65536)throw new IllegalArgumentException("Structured tool response too large");
            var envelope=json.readTree(response);
            if(!envelope.path("isError").isBoolean() || envelope.path("isError").booleanValue() || !envelope.path("structuredContent").isObject())
                throw new IllegalArgumentException("Structured tool requires successful structured MCP output");
            output=envelope.get("structuredContent");
        }
        ValueSchema.checkData(config.path("toolOutputSchema"),output);
        return output;
        } catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid structured tool JSON",invalid);}
    }
}
