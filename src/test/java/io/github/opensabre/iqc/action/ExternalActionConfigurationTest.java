package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exact frozen external configuration, independent of network and receiver availability. */
class ExternalActionConfigurationTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void frozenPayloadCannotBeChangedThroughCallerReferences(){
        var payload=json.createObjectNode().put("businessNo","original");
        var value=ExternalActionConfiguration.parse(json.createObjectNode().put("destinationId","ticket-system")
                .put("destinationVersion",1).set("payload",payload));
        payload.put("businessNo","changed");
        ((com.fasterxml.jackson.databind.node.ObjectNode)value.payload()).put("businessNo","other");
        assertEquals("original",value.payload().path("businessNo").textValue());
    }
    @Test void rejectsCallerUrlCredentialsFractionalVersionsAndOversizedPayload(){
        var config=json.createObjectNode().put("destinationId","receiver").put("destinationVersion",1);
        config.set("payload",json.createObjectNode());
        for(String field:new String[]{"url","authorization"}){
            config.put(field,"caller-value");assertThrows(IllegalArgumentException.class,()->ExternalActionConfiguration.parse(config));config.remove(field);
        }
        config.put("destinationVersion",1.5);assertThrows(IllegalArgumentException.class,()->ExternalActionConfiguration.parse(config));
        config.put("destinationVersion",1);config.set("payload",json.createObjectNode().put("text","中".repeat(6000)));
        assertThrows(IllegalArgumentException.class,()->ExternalActionConfiguration.parse(config));
    }
}
