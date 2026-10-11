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

    private final ActionStateEventMapper stateEvents=mock(ActionStateEventMapper.class);
    private ActionRecoveryService recoveryFixture() {
        service.enqueue(reference,actor());var row=saved.get();row.setStatus("BLOCKED_ADAPTER");
        when(rows.selectById(row.getId())).thenReturn(row);when(rows.selectOne(any())).thenReturn(row);
        var binding=adapters.require(policy.actions().getFirst());when(binding.adapter().ready(any(),any())).thenReturn(true);
        when(rows.recoverBlocked(anyString(),anyLong(),anyString(),any())).thenReturn(1);
        when(stateEvents.recordRecoveryReason(anyString(),anyLong(),anyLong(),anyString(),anyString(),anyString())).thenReturn(1);
        return new ActionRecoveryService(rows,sources,adapters,stateEvents,scope,json);
    }
    private ScheduledActorAccessService.Resolved recoveryActor() {
        var grants=new java.util.HashSet<>(ActionEnqueueService.REQUIRED_GRANTS);grants.add(ActionRecoveryService.RESOURCE);
        return new ScheduledActorAccessService.Resolved("user","alice",false,"group",Set.of(),grants,Instant.now());
    }
    @Test void recoveryRecordsNormalizedReasonWithoutExecutingTheAdapter() {
        var recovery=recoveryFixture();var row=saved.get();
        assertTrue(recovery.recover("result",row.getId(),0,recoveryActor()," 恢复原因 "));
        verify(stateEvents).recordRecoveryReason(row.getId(),1,0,"result","alice","恢复原因");
        verify(adapters.require(policy.actions().getFirst()).adapter(),never()).execute(any());
    }
    @Test void recoveryReasonFailureEscapesInsteadOfReportingSuccessfulRecovery() {
        var recovery=recoveryFixture();when(stateEvents.recordRecoveryReason(anyString(),anyLong(),anyLong(),anyString(),anyString(),anyString())).thenReturn(0);
        assertThrows(IqcException.class,()->recovery.recover("result",saved.get().getId(),0,recoveryActor(),"恢复原因"));
    }
    @Test void recoveryRejectsUnknownEvenWhenItWasIncorrectlyMarkedBlocked() {
        var recovery=recoveryFixture();saved.get().setAttemptCount(1);saved.get().setOutcome("UNKNOWN");
        assertFalse(recovery.recover("result",saved.get().getId(),0,recoveryActor(),"恢复原因"));
        verify(rows,never()).recoverBlocked(anyString(),anyLong(),anyString(),any());verifyNoInteractions(stateEvents);
    }
    @Test void recoveryCannotUseAnOrdinaryEnqueueGrantOrStaleObservation() {
        var recovery=recoveryFixture();
        assertThrows(IqcException.class,()->recovery.recover("result",saved.get().getId(),0,actor(),"恢复原因"));
        assertFalse(recovery.recover("result",saved.get().getId(),1,recoveryActor(),"恢复原因"));
        verify(rows,never()).recoverBlocked(anyString(),anyLong(),anyString(),any());verifyNoInteractions(stateEvents);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs={500,1000,2000})
    void claimMustRecheckDeadlineAfterAdapterReadiness(long elapsedMillis) {
        json.findAndRegisterModules();
        var start=Instant.parse("2026-10-11T00:00:00Z");
        var clock=new AtomicReference<Instant>(start);
        var original=action("save",ActionPolicy.Kind.ALWAYS);
        var retry=new io.github.opensabre.iqc.task.retry.AutomaticRetryPolicy(
                "iqc-automatic-retry-v1",2,1,1,1,start.plusSeconds(1));
        policy=new ActionPolicy(ActionPolicy.SCHEMA,List.of(new ActionPolicy.Action(original.actionId(),original.type(),
                original.condition(),original.idempotency(),retry,original.failureHandling(),original.configuration())));
        source("user");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"deadline-review"),ActionExecution.class);
        var adapter=mock(ActionAdapter.class);
        when(adapter.requiredResources()).thenReturn(Set.of());
        when(adapter.ready(any(),any())).thenAnswer(call->{clock.set(start.plusMillis(elapsedMillis));return true;});
        when(adapters.require(any())).thenReturn(new ActionAdapterRegistry.Binding(
                new ActionAdapter.Definition(ActionExecutionContract.Type.SAVE_RESULT,1,true),adapter));
        when(rows.selectById(anyString())).thenAnswer(call->saved.get());
        when(rows.selectOne(any())).thenAnswer(call->saved.get());
        when(rows.claim(anyString(),anyLong(),anyString(),any())).thenReturn(1);
        try(var time=mockStatic(Instant.class,CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenAnswer(call->clock.get());
            service.enqueue(reference,actor());
            var claims=new ActionClaimService(rows,sources,adapters,scope,json);
            var claim=claims.claim(saved.get().getId(),0,actor());
            var completedAt=java.time.LocalDateTime.ofInstant(clock.get(),java.time.ZoneOffset.UTC);
            if(elapsedMillis>=1000) {
                assertNull(claim,"Deadline crossed during ready must prevent claim");
                verify(rows,never()).claim(anyString(),anyLong(),anyString(),any());
                verify(rows).blockReady(saved.get().getId(),0,"EXPIRED","ACTION_ATTEMPTS_OR_DEADLINE_EXHAUSTED",completedAt);
            } else {
                assertNotNull(claim,"Readiness before deadline must still permit claim");
                verify(rows).claim(eq(saved.get().getId()),eq(0L),anyString(),eq(completedAt));
                verify(rows,never()).blockReady(anyString(),anyLong(),anyString(),anyString(),any());
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs={500,1000,2000})
    void recoveryRechecksDeadlineAfterReadiness(long elapsedMillis) {
        json.findAndRegisterModules();
        var start=Instant.parse("2026-10-11T00:00:00Z");var clock=new AtomicReference<Instant>(start);
        var original=action("save",ActionPolicy.Kind.ALWAYS);
        policy=new ActionPolicy(ActionPolicy.SCHEMA,List.of(new ActionPolicy.Action(original.actionId(),original.type(),
                original.condition(),original.idempotency(),new io.github.opensabre.iqc.task.retry.AutomaticRetryPolicy(
                "iqc-automatic-retry-v1",2,1,1,1,start.plusSeconds(1)),original.failureHandling(),original.configuration())));
        source("user");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"recovery-deadline"),ActionExecution.class);
        try(var time=mockStatic(Instant.class,CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenAnswer(call->clock.get());
            var recovery=recoveryFixture();var row=saved.get();var owner=recoveryActor();
            var adapter=adapters.require(policy.actions().getFirst()).adapter();
            when(adapter.ready(any(),any())).thenAnswer(call->{clock.set(start.plusMillis(elapsedMillis));return true;});
            boolean recovered=recovery.recover("result",row.getId(),0,owner,"期限边界");
            assertEquals(elapsedMillis<1000,recovered);
            if(recovered)verify(rows).recoverBlocked(row.getId(),0,"alice",java.time.LocalDateTime.ofInstant(clock.get(),java.time.ZoneOffset.UTC));
            else {verify(rows,never()).recoverBlocked(anyString(),anyLong(),anyString(),any());verifyNoInteractions(stateEvents);}
            verify(adapter,never()).execute(any());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs={9223372036854775804L,9223372036854775805L,9223372036854775806L,9223372036854775807L})
    void recoveryReservesVersionSpaceForClaimAndSettlement(long observedVersion) {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"recovery-version"),ActionExecution.class);
        var recovery=recoveryFixture();var row=saved.get();row.setVersion(observedVersion);
        clearInvocations(rows,stateEvents,sources);
        if(observedVersion>Long.MAX_VALUE-3) {
            assertThrows(IqcException.class,()->recovery.recover("result",row.getId(),observedVersion,recoveryActor(),"版本边界"));
            verifyNoInteractions(rows,stateEvents,sources);
        } else {
            assertTrue(recovery.recover("result",row.getId(),observedVersion,recoveryActor(),"版本边界"));
            verify(stateEvents).recordRecoveryReason(row.getId(),observedVersion+1,observedVersion,"result","alice","版本边界");
            assertTrue(observedVersion+1<=Long.MAX_VALUE-2);
        }
    }

}
