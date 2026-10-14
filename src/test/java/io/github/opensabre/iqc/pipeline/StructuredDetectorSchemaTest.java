package io.github.opensabre.iqc.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Structured detector contracts reuse the node variable schema dialect. */
class StructuredDetectorSchemaTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void nestedEntityOutputEnforcesRequiredTypesEnumsAndExtraFields() throws Exception {
        var schema=json.readTree("""
                {"type":"object","required":["entities"],"additionalProperties":false,
                 "properties":{"entities":{"type":"array","items":{"type":"object",
                  "required":["type","start"],"additionalProperties":false,
                  "properties":{"type":{"type":"string","enum":["PERSON","ORG"]},"start":{"type":"integer"}}}}}}
                """);
        ValueSchema.checkData(schema,json.readTree("{\"entities\":[{\"type\":\"PERSON\",\"start\":0}]}"));
        for(var invalid:java.util.List.of("{}","{\"entities\":[{\"type\":\"OTHER\",\"start\":0}]}",
                "{\"entities\":[{\"type\":\"ORG\",\"start\":0.5}]}",
                "{\"entities\":[],\"unexpected\":true}")) {
            var value=json.readTree(invalid);
            assertThatThrownBy(()->ValueSchema.checkData(schema,value)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void unsupportedContractFailsBeforeValueValidation() throws Exception {
        var schema=json.readTree("{\"type\":\"string\",\"pattern\":\".*\"}");
        assertThatThrownBy(()->ValueSchema.checkData(schema,json.getNodeFactory().textNode("ok")))
                .hasMessageContaining("Unsupported output schema keyword");
        var valid=json.readTree("{\"type\":\"null\"}");
        ValueSchema.checkData(valid,json.nullNode());
        assertThatThrownBy(()->ValueSchema.checkData(valid,null)).hasMessageContaining("Structured value required");
    }
}
