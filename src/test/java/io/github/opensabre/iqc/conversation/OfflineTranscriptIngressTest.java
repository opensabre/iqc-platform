package io.github.opensabre.iqc.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.governance.usage.UsageCounterRecorder;
import io.github.opensabre.iqc.conversation.model.Conversation;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline alignment must affect identity while old ingress and frozen message JSON remain compatible. */
class OfflineTranscriptIngressTest {
    private record Captured(String fingerprint,ConversationParseResult parsed) { }
    private Captured ingest(ApiConversationInput.Message message){
        var imports=mock(ConversationImportService.class);var usage=mock(UsageCounterRecorder.class);
        var captured=new java.util.concurrent.atomic.AtomicReference<Captured>();
        doAnswer(call->{captured.set(new Captured(call.getArgument(1),call.getArgument(2)));return new Conversation();})
                .when(imports).persistVerified(anyString(),anyString(),any(),nullable(String.class),eq("API"),eq("external"),isNull());
        new ConversationApiIngestService(imports,usage).ingest(new ApiConversationInput("external",null,"Transcript",null,List.of(message)),null);
        return captured.get();
    }
    @Test void alignmentPersistsInOriginalDraftAndChangesIdentity(){
        var plain=ingest(new ApiConversationInput.Message("00:00:01","user","hello"));
        var aligned=ingest(new ApiConversationInput.Message("00:00:01","user","hello",1000L,1500L,new BigDecimal("0.75")));
        assertNotEquals(plain.fingerprint(),aligned.fingerprint());
        var message=aligned.parsed().messages().getFirst();assertEquals(1000L,message.startMs());assertEquals(1500L,message.endMs());assertEquals(new BigDecimal("0.75"),message.confidence());
        assertEquals(aligned.fingerprint(),ingest(new ApiConversationInput.Message("00:00:01","user","hello",1000L,1500L,new BigDecimal("0.750000"))).fingerprint());
        assertNotEquals(aligned.fingerprint(),ingest(new ApiConversationInput.Message("00:00:01","user","hello",1000L,1501L,new BigDecimal("0.75"))).fingerprint());
    }
    @Test void malformedIntervalsAndConfidenceAreRejectedBeforePersistence(){
        for(var message:List.of(new ApiConversationInput.Message(null,"user","hello",1L,null,null),
                new ApiConversationInput.Message(null,"user","hello",2L,1L,null),
                new ApiConversationInput.Message(null,"user","hello",-1L,1L,null),
                new ApiConversationInput.Message(null,"user","hello",0L,9007199254740992L,null),
                new ApiConversationInput.Message(null,"user","hello",0L,1L,new BigDecimal("1.1")),
                new ApiConversationInput.Message(null,"user","hello",0L,1L,new BigDecimal("0.1234567")))){
            var imports=mock(ConversationImportService.class);
            assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->new ConversationApiIngestService(imports,mock(UsageCounterRecorder.class))
                    .ingest(new ApiConversationInput("external",null,null,null,List.of(message)),null));verifyNoInteractions(imports);
        }
    }
    @Test void legacyFrozenMessageJsonHasNoAddedNullFields() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var message=new ProductionInputSnapshotService.Message("message",1,"user","hello",java.time.LocalTime.MIDNIGHT);
        var tree=json.valueToTree(message);assertEquals(5,tree.size());assertFalse(tree.has("startMs"));
        assertEquals(message,json.treeToValue(tree,ProductionInputSnapshotService.Message.class));
        var aligned=new ProductionInputSnapshotService.Message("message",1,"user","hello",null,86400001L,86400500L,new BigDecimal("0.500000"));
        assertEquals(86400001L,json.valueToTree(aligned).path("startMs").longValue());assertEquals(new BigDecimal("0.5"),aligned.confidence());
    }
    @Test void jsonMillisecondCoordinatesRejectFractionalAndStringCoercion() throws Exception {
        var json=new ObjectMapper();
        for(String value:List.of("1.5","1.0","\"1\"","-1","9007199254740992")){
            String payload="{\"role\":\"user\",\"content\":\"hello\",\"startMs\":"+value+",\"endMs\":2}";
            assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->json.readValue(payload,ApiConversationInput.Message.class));
            assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->json.readValue(payload,io.github.opensabre.iqc.rest.ConversationImportController.IngestMessage.class));
        }
        assertEquals(1L,json.readValue("{\"role\":\"user\",\"content\":\"hello\",\"startMs\":1,\"endMs\":2}",ApiConversationInput.Message.class).startMs());
    }

}
