package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActionReconciliationTest {
    final ObjectMapper json=new ObjectMapper();
    final ActionReconciliationEventMapper journal=mock(ActionReconciliationEventMapper.class);
    final ActionExecutionMapper actions=mock(ActionExecutionMapper.class);
    final ConversationInspectionResultMapper results=mock(ConversationInspectionResultMapper.class);
    final InspectionTaskMapper tasks=mock(InspectionTaskMapper.class);
    final ScheduledActorAccessService actors=mock(ScheduledActorAccessService.class);
    final IqcDataScope scope=mock(IqcDataScope.class);
    final ActionAdapter adapter=mock(ActionAdapter.class);
    final ActionAdapter.Definition definition=new ActionAdapter.Definition(ActionExecutionContract.Type.SAVE_RESULT,1,true);
    ActionReconciliationService service;
    ActionExecution row;
    String id;
    @BeforeEach void setup() throws Exception {
        when(adapter.definition()).thenReturn(definition);
        service=new ActionReconciliationService(actions,new ActionAdapterRegistry(List.of(adapter)),results,tasks,actors,scope,json,new ActionReconciliationStateService(actions,journal));
        when(journal.recordReason(anyString(),anyLong(),anyLong(),anyString(),anyString(),anyString())).thenReturn(1);
        var bound=new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1","user-1","alice",false,null);
        when(actors.capture()).thenReturn(bound);when(scope.owner()).thenReturn("alice");when(scope.canView("alice",null)).thenReturn(true);
        var task=new InspectionTask();task.setId("task");task.setCreatedBy("alice");task.setCurrentExecutionId("new-eid");when(tasks.selectById("task")).thenReturn(task);
        var result=new ConversationInspectionResult();result.setId("result");result.setTaskId("task");result.setExecutionId("eid");result.setConversationId("conversation");when(results.selectById("result")).thenReturn(result);
        var policy=new ActionPolicy(ActionPolicy.SCHEMA,List.of(new ActionPolicy.Action("save",definition.type(),
                new ActionPolicy.Condition(ActionPolicy.Kind.ALWAYS,null,null,List.of()),ActionPolicy.Idempotency.PRODUCTION_SOURCE_V1,
                null,ActionPolicy.FailureHandling.RECORD_ONLY,json.createObjectNode())));
        String body=json.writeValueAsString(policy);
        var source=new ActionExecutionContract.Source("task","eid","conversation","result","result",SelectorAssetService.hash(body),"save","alice");
        id=source.idempotencyKey(definition.type());
        String input=json.createObjectNode().put("schemaVersion","iqc-action-production-input-v1").put("taskId","task")
                .put("executionId","eid").put("conversationId","conversation").put("resultId","result").put("owner","alice")
                .put("taskSnapshotJson",json.createObjectNode().set("actionOwnerBound",json.valueToTree(bound)).toString()).toString();
        row=new ActionExecution();row.setId(id);row.setIdempotencyKey(id);row.setTaskId("task");row.setExecutionId("eid");
        row.setConversationId("conversation");row.setSourceResultId("result");row.setDecisionReference("result");row.setOwner("alice");
        row.setActionId("save");row.setActionType("SAVE_RESULT");row.setActionPolicyHash(source.actionPolicyHash());row.setPolicySnapshotJson(body);
        row.setInputSnapshotJson(input);row.setInputSnapshotHash(SelectorAssetService.hash(input));row.setVersion(3L);row.setAttemptCount(1);
        row.setStatus("REQUIRES_RECONCILIATION");row.setOutcome("UNKNOWN");row.setClaimToken("a".repeat(32));row.setClaimedAtUtc(LocalDateTime.now().minusHours(1));
        when(actions.selectById(id)).thenReturn(row);
    }
    private void success() {when(adapter.reconcile(any())).thenReturn(new ActionAdapter.Receipt(id,ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,"artifact","artifact",null));}
    @Test void confirmsHistoricalArtifactWithoutExecutingOrCreatingAnotherAttempt() {
        success();when(actions.reconcileSuccessBound(any(),eq(3L),eq(row.getClaimToken()),any(),eq("artifact"),eq("artifact"),eq("alice"))).thenReturn(1);
        assertEquals("CONFIRMED",service.reconcile("result",id,3,"核对产物").state());verify(adapter,never()).execute(any());
        assertEquals(1,row.getAttemptCount());assertEquals("a".repeat(32),row.getClaimToken());
    }
    @Test void absentArtifactNeverSettlesOrExecutes() {
        assertEquals("UNCONFIRMED",service.reconcile("result",id,3,"核对").state());
        verify(actions,never()).reconcileSuccessBound(any(),anyLong(),anyString(),any(),anyString(),anyString(),anyString());verify(adapter,never()).execute(any());
    }
    @Test void unknownReceiptNeverSettles() {
        when(adapter.reconcile(any())).thenReturn(new ActionAdapter.Receipt(id,ActionExecutionContract.Outcome.UNKNOWN,null,null,"UNAVAILABLE"));
        assertEquals("UNCONFIRMED",service.reconcile("result",id,3,"核对").state());verify(actions,never()).reconcileSuccessBound(any(),anyLong(),anyString(),any(),anyString(),anyString(),anyString());
    }
    @Test void changedStableIdentityCannotLookup() {
        when(actors.capture()).thenReturn(new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1","replacement","alice",true,null));
        assertThrows(RuntimeException.class,()->service.reconcile("result",id,3,"核对"));verify(adapter,never()).reconcile(any());
    }
    @Test void staleVersionCannotLookup() {
        assertEquals("STALE_OBSERVATION",service.reconcile("result",id,2,"核对").state());verify(adapter,never()).reconcile(any());
    }
    @Test void concurrentCasWinnerCannotBeOverwritten() {
        success();assertEquals("STALE_OBSERVATION",service.reconcile("result",id,3,"核对").state());verify(adapter,never()).execute(any());
    }
    @Test void foreignReceiptCannotSettle() {
        when(adapter.reconcile(any())).thenReturn(new ActionAdapter.Receipt("b".repeat(64),ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,"artifact","artifact",null));
        assertThrows(RuntimeException.class,()->service.reconcile("result",id,3,"核对"));verify(actions,never()).reconcileSuccessBound(any(),anyLong(),anyString(),any(),anyString(),anyString(),anyString());
    }
    @Test void broaderViewerCannotConfirmAnotherOwnersAction() {
        when(scope.owner()).thenReturn("bob");assertThrows(RuntimeException.class,()->service.reconcile("result",id,3,"核对"));verify(adapter,never()).reconcile(any());
    }
}
