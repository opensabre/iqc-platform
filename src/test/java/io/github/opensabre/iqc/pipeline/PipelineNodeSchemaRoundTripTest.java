package io.github.opensabre.iqc.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Persisted optional schemas must keep the same validation semantics after JSON decoding. */
class PipelineNodeSchemaRoundTripTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void absentSchemaSurvivesRepeatedFrozenRoundTrips() throws Exception {
        var node = node(null);
        for (int round = 0; round < 3; round++) {
            node = json.readValue(json.writeValueAsString(node), PipelineDefinition.Node.class);
            assertNull(node.outputSchema());
            ValueSchema.validateSchema(node.outputSchema());
        }
    }

    @Test void concreteSchemasRemainValidatedAfterRoundTrip() throws Exception {
        var schema = json.createObjectNode().put("type", "object");
        var restored = json.readValue(json.writeValueAsString(node(schema)), PipelineDefinition.Node.class);
        assertEquals(schema, restored.outputSchema());
        ValueSchema.validateSchema(restored.outputSchema());
        var invalid = json.readValue(json.writeValueAsString(node(json.getNodeFactory().textNode("invalid"))), PipelineDefinition.Node.class);
        assertThrows(IllegalArgumentException.class, () -> ValueSchema.validateSchema(invalid.outputSchema()));
    }

    private PipelineDefinition.Node node(com.fasterxml.jackson.databind.JsonNode schema) {
        return new PipelineDefinition.Node("metric", "METRIC", "Metric", "v1", List.of(), Map.of(),
                null, null, 1000, 0, PipelineDefinition.OnError.FAIL_PIPELINE, null, schema, true);
    }
}
