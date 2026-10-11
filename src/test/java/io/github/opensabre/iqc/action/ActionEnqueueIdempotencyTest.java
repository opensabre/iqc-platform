package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActionEnqueueIdempotencyTest {
    private final ObjectMapper json=new ObjectMapper();
    private final ActionProductionSourceValidator sources=mock(ActionProductionSourceValidator.class);
    private final ActionExecutionMapper rows=mock(ActionExecutionMapper.class);
    private final ActionDiscoveryMapper discovery=mock(ActionDiscoveryMapper.class);
    private final ActionAdapterRegistry adapters=mock(ActionAdapterRegistry.class);
    private final IqcDataScope scope=mock(IqcDataScope.class);
    private final ActionEnqueueService service=new ActionEnqueueService(sources,rows,discovery,adapters,scope,json);
    private final ActionProductionSourceValidator.Reference reference=new ActionProductionSourceValidator.Reference("task","execution","conversation","result","alice");
    private final AtomicReference<ActionExecution> saved=new AtomicReference<>();
    private ActionPolicy policy;
    @BeforeEach void prepare() {
        when(scope.owner()).thenReturn("alice");when(scope.groupId()).thenReturn("group");
        policy=new ActionPolicy(ActionPolicy.SCHEMA,List.of(action("save",ActionPolicy.Kind.ALWAYS)));
        source("user");
        var adapter=mock(ActionAdapter.class);
        when(adapter.requiredResources()).thenReturn(Set.of());
        when(adapters.require(any())).thenReturn(new ActionAdapterRegistry.Binding(
                new ActionAdapter.Definition(ActionExecutionContract.Type.SAVE_RESULT,1,true),adapter));
        when(rows.insert(any(ActionExecution.class))).thenAnswer(call->{saved.set(call.getArgument(0));return 1;});
    }
    private ActionPolicy.Action action(String id,ActionPolicy.Kind kind) {
        var condition=new ActionPolicy.Condition(kind,null,kind==ActionPolicy.Kind.RESULT_STATUS?json.valueToTree("ERROR"):null,List.of());
        return new ActionPolicy.Action(id,ActionExecutionContract.Type.SAVE_RESULT,condition,
                ActionPolicy.Idempotency.PRODUCTION_SOURCE_V1,null,ActionPolicy.FailureHandling.RECORD_ONLY,json.createObjectNode());
    }
    private void source(String user) {
        var bound=new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1",user,"alice",false,"group");
        var context=new ActionPolicy.Context(Map.of(),Map.of(),InspectionScoring.ScoreStatus.FINAL,null,"UNKNOWN",null);
        when(sources.validate(reference)).thenReturn(new ActionProductionSourceValidator.Validated(reference,"group","result",
                "{\"frozen\":true}",SelectorAssetService.hash("{\"frozen\":true}"),policy,context,bound));
    }
    private ScheduledActorAccessService.Resolved actor() {
        return new ScheduledActorAccessService.Resolved("user","alice",false,"group",Set.of(),ActionEnqueueService.REQUIRED_GRANTS,Instant.now());
    }
    @Test void emptyPolicyCompletesDiscoveryWithoutCreatingActions() {
        policy=new ActionPolicy(ActionPolicy.SCHEMA,List.of());source("user");
        assertTrue(service.enqueue(reference,actor()).isEmpty());
        verify(discovery).complete("result");verifyNoInteractions(rows,adapters);
    }
    @Test void emptyPolicyStillRejectsReusedUsername() {
        policy=new ActionPolicy(ActionPolicy.SCHEMA,List.of());source("old-user");
        assertThrows(IqcException.class,()->service.enqueue(reference,actor()));
        verifyNoInteractions(discovery,rows,adapters);
    }
    @Test void missingOperationGrantBlocksLedgerWithoutCreatingAttempt() {
        var adapter=mock(ActionAdapter.class);
        when(adapter.requiredResources()).thenReturn(Set.of("iqc:review:create"));
        when(adapters.require(any())).thenReturn(new ActionAdapterRegistry.Binding(
                new ActionAdapter.Definition(ActionExecutionContract.Type.SAVE_RESULT,1,true),adapter));
        service.enqueue(reference,actor());
        assertEquals("BLOCKED_AUTHORIZATION",saved.get().getStatus());
        assertEquals("ACTION_OPERATION_GRANT_MISSING",saved.get().getLastErrorCode());
        assertEquals(0,saved.get().getAttemptCount());assertNull(saved.get().getOutcome());
        verify(discovery).complete("result");
    }
    @Test void matchingActionFreezesSourceAndStartsWithoutAttemptOrOutcome() throws Exception {
        var ids=service.enqueue(reference,actor());var row=saved.get();
        assertEquals(List.of(row.getId()),ids);assertEquals(row.getId(),row.getIdempotencyKey());
        assertEquals("PENDING",row.getStatus());assertEquals(0,row.getAttemptCount());assertEquals(0L,row.getVersion());
        assertEquals("alice",row.getOwner());assertEquals("group",row.getOwnerGroupId());
        assertEquals("result",row.getSourceResultId());assertEquals("execution",row.getExecutionId());
        assertEquals(json.writeValueAsString(policy),row.getPolicySnapshotJson());
        assertEquals(SelectorAssetService.hash(row.getPolicySnapshotJson()),row.getActionPolicyHash());
        assertEquals(SelectorAssetService.hash(row.getInputSnapshotJson()),row.getInputSnapshotHash());
        assertNull(row.getOutcome());
    }
    @Test void unknownConditionCreatesNoActionAndDoesNotCheckAdapter() {
        policy=new ActionPolicy(ActionPolicy.SCHEMA,List.of(action("save",ActionPolicy.Kind.RESULT_STATUS)));source("user");
        assertTrue(service.enqueue(reference,actor()).isEmpty());verify(discovery).complete("result");verifyNoInteractions(rows,adapters);
    }
    @Test void reusedUsernameCannotReplaceFrozenStableIdentity() {
        source("old-user");assertThrows(IqcException.class,()->service.enqueue(reference,actor()));
        verifyNoInteractions(rows,adapters);
    }
    @Test void duplicateRequestKeepsExistingTerminalStateAndAttemptCount() {
        var first=service.enqueue(reference,actor());var row=saved.get();row.setStatus("COMPLETE");row.setAttemptCount(3);
        when(rows.selectById(row.getId())).thenReturn(row);
        assertEquals(first,service.enqueue(reference,actor()));
        assertEquals("COMPLETE",row.getStatus());assertEquals(3,row.getAttemptCount());
        verify(rows,times(1)).insert(any(ActionExecution.class));verify(adapters,times(1)).require(any());
    }
    @Test void sameKeyWithDifferentFrozenInputIsRejected() {
        service.enqueue(reference,actor());var row=saved.get();row.setInputSnapshotJson("changed");
        when(rows.selectById(row.getId())).thenReturn(row);
        assertThrows(IqcException.class,()->service.enqueue(reference,actor()));verify(rows,times(1)).insert(any(ActionExecution.class));
    }
    @Test void concurrentDuplicateConfirmsIdenticalWinner() {
        when(rows.selectById(anyString())).thenAnswer(call->saved.get());
        when(rows.insert(any(ActionExecution.class))).thenAnswer(call->{saved.set(call.getArgument(0));throw new DuplicateKeyException("race");});
        assertEquals(List.of(savedIdAfterEnqueue()),List.of(saved.get().getId()));
        assertEquals("PENDING",saved.get().getStatus());
    }
    private String savedIdAfterEnqueue() {return service.enqueue(reference,actor()).getFirst();}
    @Test void duplicateWithoutWinnerCannotClaimSuccess() {
        when(rows.insert(any(ActionExecution.class))).thenThrow(new DuplicateKeyException("race"));
        assertThrows(IqcException.class,()->service.enqueue(reference,actor()));
    }
    @Test void unavailableAdapterBlocksOnlyItsActionWithoutInventingOutcome() {
        policy=new ActionPolicy(ActionPolicy.SCHEMA,List.of(action("blocked",ActionPolicy.Kind.ALWAYS),action("available",ActionPolicy.Kind.ALWAYS)));source("user");
        when(adapters.require(argThat(a->a.actionId().equals("blocked")))).thenThrow(IqcException.invalidState("not installed"));
        var captured=new java.util.ArrayList<ActionExecution>();
        when(rows.insert(any(ActionExecution.class))).thenAnswer(call->{captured.add(call.getArgument(0));return 1;});
        assertEquals(2,service.enqueue(reference,actor()).size());
        assertEquals("BLOCKED_ADAPTER",captured.get(0).getStatus());assertEquals("PENDING",captured.get(1).getStatus());
        assertEquals(0,captured.get(0).getAttemptCount());assertNull(captured.get(0).getOutcome());
    }
    @Test void zeroRowsInsertedCannotClaimSuccess() {
        when(rows.insert(any(ActionExecution.class))).thenReturn(0);
        assertThrows(IqcException.class,()->service.enqueue(reference,actor()));
    }
    @Test void sharedFrozenValidationRejectsChangedInputBeforeClaimOrRecovery() throws Exception {
        service.enqueue(reference,actor());var row=saved.get();
        assertEquals("save",ActionFrozenPolicy.validate(row,reference,sources.validate(reference),json).action().actionId());
        row.setInputSnapshotJson("{\"different\":true}");
        assertThrows(IqcException.class,()->ActionFrozenPolicy.validate(row,reference,sources.validate(reference),json));
    }
    @Test void sharedAttemptLimitDoesNotResetOrModifyTheOriginalLedger() throws Exception {
        service.enqueue(reference,actor());var row=saved.get();
        var prepared=ActionFrozenPolicy.validate(row,reference,sources.validate(reference),json);
        assertFalse(prepared.exhausted(row,Instant.now()));row.setAttemptCount(1);
        assertTrue(prepared.exhausted(row,Instant.now()));assertEquals(1,row.getAttemptCount());
        assertEquals("PENDING",row.getStatus());assertEquals(0L,row.getVersion());
    }

}
