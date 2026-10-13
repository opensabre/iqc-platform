package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Logical source/claim boundaries; transaction commit and actual SQL require separate integration tests. */
class ExternalActionPreparationServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ActionProductionSourceValidator sources = mock(ActionProductionSourceValidator.class);
    private final ActionExecutionMapper actions = mock(ActionExecutionMapper.class);
    private final IqcDataScope scope = mock(IqcDataScope.class);
    private final ExternalActionPreparationService service = new ExternalActionPreparationService(sources, actions, scope, json);
    private ActionAdapter.Request request;
    private ActionExecution row;
    private ActionProductionSourceValidator.Reference reference;
    private ScheduledActorAccessService.CreationBound bound;

    @BeforeEach void fixture() throws Exception {
        var action = new ActionPolicy.Action("alert", ActionExecutionContract.Type.WEBHOOK,
                new ActionPolicy.Condition(ActionPolicy.Kind.ALWAYS, null, null, List.of()),
                ActionPolicy.Idempotency.PRODUCTION_SOURCE_V1, null, ActionPolicy.FailureHandling.RECORD_ONLY,
                json.readTree("{\"destinationId\":\"receiver\",\"destinationVersion\":1,\"payload\":{}}"));
        var policy = new ActionPolicy(ActionPolicy.SCHEMA, List.of(action));
        String body = json.writeValueAsString(policy), input = "{\"frozen\":true}";
        var source = new ActionExecutionContract.Source("task", "execution", "conversation", "result", "result",
                SelectorAssetService.hash(body), "alert", "alice");
        String key = source.idempotencyKey(ActionExecutionContract.Type.WEBHOOK);
        request = new ActionAdapter.Request(key, key, source, ExternalActionPreparationService.definition(ActionExecutionContract.Type.WEBHOOK),
                1, input, SelectorAssetService.hash(input), body);
        reference = new ActionProductionSourceValidator.Reference("task", "execution", "conversation", "result", "alice");
        bound = new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1", "user", "alice", false, "group");
        var context = new ActionPolicy.Context(Map.of(), Map.of(), InspectionScoring.ScoreStatus.FINAL, null, "UNKNOWN", null);
        var validated = new ActionProductionSourceValidator.Validated(reference, "group", "result", input,
                request.inputSnapshotHash(), policy, context, bound, "SUCCEEDED");
        when(sources.validate(reference)).thenReturn(validated);
        when(sources.validateHistorical(reference)).thenReturn(validated);
        when(scope.owner()).thenReturn("alice");
        row = new ActionExecution(); row.setId(key); row.setIdempotencyKey(key);
        row.setTaskId("task"); row.setExecutionId("execution"); row.setConversationId("conversation");
        row.setSourceResultId("result"); row.setDecisionReference("result"); row.setActionId("alert");
        row.setActionType("WEBHOOK"); row.setActionPolicyHash(source.actionPolicyHash());
        row.setOwner("alice"); row.setOwnerGroupId("group"); row.setPolicySnapshotJson(body);
        row.setInputSnapshotJson(input); row.setInputSnapshotHash(request.inputSnapshotHash());
        row.setStatus("RUNNING"); row.setVersion(1L); row.setAttemptCount(1);
        row.setClaimToken("a".repeat(32)); row.setClaimedAtUtc(LocalDateTime.now());
        when(actions.selectOne(any())).thenReturn(row); when(actions.selectById(key)).thenReturn(row);
    }

    @Test void sendPreparationRequiresFreshGrantsAndBoundBeforeReturningFrozenConfiguration() {
        assertEquals("receiver", service.prepareSend(request).destinationId());
        verify(scope, times(2)).requireScheduledResources(ActionEnqueueService.REQUIRED_GRANTS);
        verify(scope).requireScheduledBound(bound);
        verify(sources).validate(reference); verify(sources, never()).validateHistorical(any());
        verify(actions, never()).updateById(any(ActionExecution.class));
    }
    @Test void missingAlertGrantRejectsBeforeSourceLocks() {
        doThrow(IqcException.accessDenied("missing grant")).when(scope).requireScheduledResources(ActionEnqueueService.REQUIRED_GRANTS);
        assertThrows(IqcException.class, () -> service.prepareSend(request));
        verifyNoInteractions(sources, actions);
    }
    @Test void changedClaimAndFrozenSourceCannotPrepareSend() {
        row.setAttemptCount(2);
        assertThrows(IqcException.class, () -> service.prepareSend(request));
        row.setAttemptCount(1); row.setInputSnapshotJson("{}");
        assertThrows(IqcException.class, () -> service.prepareSend(request));
        row.setInputSnapshotJson(request.inputSnapshotJson()); row.setStatus("REQUIRES_RECONCILIATION");
        assertThrows(IqcException.class, () -> service.prepareSend(request));
    }
    @Test void lookupUsesHistoricalProofWithoutSendGrantsOrNewClaim() {
        row.setStatus("REQUIRES_RECONCILIATION");
        assertEquals("receiver", service.prepareLookup(request).destinationId());
        verify(sources).validateHistorical(reference); verify(sources, never()).validate(any());
        verify(scope, never()).requireScheduledResources(any()); verify(scope, never()).requireScheduledBound(any());
        verify(actions, never()).selectOne(any()); verify(actions, never()).updateById(any(ActionExecution.class));
    }
    @Test void ownerMismatchRejectsBothSendAndLookupBeforeSourceRead() {
        when(scope.owner()).thenReturn("other");
        assertThrows(IqcException.class, () -> service.prepareSend(request));
        assertThrows(IqcException.class, () -> service.prepareLookup(request));
        verifyNoInteractions(sources, actions);
    }
}
