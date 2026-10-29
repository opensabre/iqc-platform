package io.github.opensabre.iqc.action;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exact destination ownership and URI boundaries; no network is used. */
class ExternalActionDestinationsTest {
    private ExternalActionDestinations.Destination destination(String send){
        return new ExternalActionDestinations.Destination("receiver",1,ActionExecutionContract.Type.WEBHOOK,
                URI.create(send),URI.create("https://receiver.invalid/receipts/"),1000,5000,16384,"IQC_RECEIVER_TOKEN");
    }
    @Test void immutableRegistryRejectsDuplicateVersionsLatestFallbackAndWrongActionType(){
        var value=destination("https://receiver.invalid/events");
        var list=new ArrayList<>(List.of(value));var routes=new ExternalActionDestinations(list);list.clear();
        var payload=new ObjectMapper().createObjectNode();
        assertSame(value,routes.require(new ExternalActionConfiguration("receiver",1,payload),ActionExecutionContract.Type.WEBHOOK));
        assertThrows(IllegalArgumentException.class,()->new ExternalActionDestinations(List.of(value,value)));
        assertThrows(IllegalArgumentException.class,()->routes.require(new ExternalActionConfiguration("receiver",2,payload),ActionExecutionContract.Type.WEBHOOK));
        assertThrows(IllegalArgumentException.class,()->routes.require(new ExternalActionConfiguration("receiver",1,payload),ActionExecutionContract.Type.CREATE_WORK_ORDER));
    }
    @Test void endpointAndReceiptPathsCannotCarryCredentialsOrCallerInput(){
        for(String uri:List.of("http://receiver.invalid/events","https://user:secret@receiver.invalid/events",
                "https://receiver.invalid/events?token=secret","https://receiver.invalid/events#fragment"))
            assertThrows(IllegalArgumentException.class,()->destination(uri));
        var value=destination("https://receiver.invalid/events");
        assertEquals(URI.create("https://receiver.invalid/receipts/"+"a".repeat(64)),value.receiptUri("a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->value.receiptUri("../other"));
    }
}
