package io.github.opensabre.iqc.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.conversation.dao.*;
import io.github.opensabre.iqc.conversation.model.*;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.model.InspectionTask;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual capture/decode and canonical hashing; archive Mappers and actor scope are substitutes. */
class OfflineProductionSnapshotTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final ConversationMapper conversations=mock(ConversationMapper.class);
    private final ConversationMessageMapper messages=mock(ConversationMessageMapper.class);
    private final IqcDataScope scope=mock(IqcDataScope.class);
    private final ProductionInputSnapshotService service=new ProductionInputSnapshotService(conversations,messages,scope,json);
    private ObjectNode capture(boolean aligned){
        var conversation=new Conversation();conversation.setId("conversation");conversation.setCreatedBy("alice");conversation.setOwnerGroupId("team");conversation.setSourceFingerprint("revision");
        var message=new ConversationMessage();message.setId("message");message.setConversationId("conversation");message.setSequenceNo(1);message.setSpeakerRole("user");message.setContent("hello");message.setRelativeTime(java.time.LocalTime.MIDNIGHT);
        if(aligned){message.setStartMs(86400001L);message.setEndMs(86400500L);message.setConfidence(new BigDecimal("0.500000"));}
        when(conversations.selectById("conversation")).thenReturn(conversation);when(messages.selectList(any())).thenReturn(List.of(message));when(scope.canView("alice","team")).thenReturn(true);
        var root=json.createObjectNode();root.putObject("executionStrategy").put("mode","PIPELINE");root.set("productionInputs",service.capture(List.of("conversation")));return root;
    }
    private InspectionTask task(ObjectNode root){
        var task=new InspectionTask();task.setId("task");task.setTaskType("PIPELINE");task.setStatus("CREATED");task.setTotalMessages(1);task.setConversationIdsJson("[\"conversation\"]");task.setRuleSnapshotJson(root.toString());return task;
    }
    @Test void alignedCaptureUsesV2AndRestoresFrozenCoordinatesAfterArchiveChanges(){
        var root=capture(true);assertEquals(ProductionInputSnapshotService.ALIGNED_SCHEMA,root.path("productionInputs").path("schemaVersion").asText());
        var task=task(root);var restored=service.prepare(task,root).messages().get("conversation").getFirst();
        assertEquals(86400001L,restored.getStartMs());assertEquals(86400500L,restored.getEndMs());assertEquals(0,restored.getConfidence().compareTo(new BigDecimal("0.5")));
        clearInvocations(conversations,messages);when(conversations.selectById("conversation")).thenThrow(new AssertionError("must not reload archive"));
        assertEquals(86400001L,service.prepare(task,root).messages().get("conversation").getFirst().getStartMs());verifyNoInteractions(conversations,messages);
    }
    @Test void legacyCaptureKeepsV1CanonicalBodyAndDecodesWithoutNewFields(){
        var root=capture(false);var envelope=root.path("productionInputs");assertEquals(ProductionInputSnapshotService.SCHEMA,envelope.path("schemaVersion").asText());
        var message=envelope.path("conversations").get(0).path("messages").get(0);assertEquals(5,message.size());assertFalse(message.has("startMs"));
        var restored=service.prepare(task(root),root).messages().get("conversation").getFirst();assertNull(restored.getStartMs());assertNull(restored.getEndMs());assertNull(restored.getConfidence());
    }
    @Test void alteredCoordinatesOrV1ProtocolCannotMasqueradeAsFrozenAlignedInput(){
        var root=capture(true);var envelope=(ObjectNode)root.path("productionInputs");var body=envelope.path("conversations");
        ((ObjectNode)body.get(0).path("messages").get(0)).put("endMs",86400600L);
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->service.prepare(task(root),root));
        envelope.put("contentHash",io.github.opensabre.iqc.selector.SelectorAssetService.hash(body.toString()));envelope.put("schemaVersion",ProductionInputSnapshotService.SCHEMA);
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->service.prepare(task(root),root));
    }
}
