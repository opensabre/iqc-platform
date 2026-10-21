package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActionExecutionReceiptTest {
    private final ObjectMapper json=new ObjectMapper();
    private final ActionClaimService claims=mock(ActionClaimService.class);
    private final ActionSettlementService settlements=mock(ActionSettlementService.class);
    private final ActionAdapterRegistry adapters=mock(ActionAdapterRegistry.class);
    private final ActionExecutionMapper rows=mock(ActionExecutionMapper.class);
    private final IqcDataScope scope=mock(IqcDataScope.class);
    private final ActionAdapter adapter=mock(ActionAdapter.class);
    private final ActionExecutionService service=new ActionExecutionService(claims,settlements,adapters,rows,scope,json);
    private ActionAdapter.Request request;
    private ScheduledActorAccessService.Resolved actor;
    private ActionExecution row;
    private String id;
    private final String token="a".repeat(32);
    @BeforeEach void fixture() throws Exception {
        var action=new ActionPolicy.Action("save",ActionExecutionContract.Type.SAVE_RESULT,
                new ActionPolicy.Condition(ActionPolicy.Kind.ALWAYS,null,null,List.of()),ActionPolicy.Idempotency.PRODUCTION_SOURCE_V1,
                null,ActionPolicy.FailureHandling.RECORD_ONLY,json.createObjectNode());
        String body=json.writeValueAsString(new ActionPolicy(ActionPolicy.SCHEMA,List.of(action)));
        var source=new ActionExecutionContract.Source("task","execution","conversation","result","result",SelectorAssetService.hash(body),"save","alice");
        var definition=new ActionAdapter.Definition(ActionExecutionContract.Type.SAVE_RESULT,1,true);
        id=source.idempotencyKey(definition.type());
        request=new ActionAdapter.Request(id,id,source,definition,1,"{}",SelectorAssetService.hash("{}"),body);
        actor=new ScheduledActorAccessService.Resolved("user","alice",false,"group",Set.of(),ActionEnqueueService.REQUIRED_GRANTS,Instant.now());
        when(scope.owner()).thenReturn("alice");when(scope.groupId()).thenReturn("group");
        when(claims.claim(id,0,actor)).thenReturn(new ActionClaimService.Claim(request,1,token));
        row=new ActionExecution();row.setStatus("RUNNING");row.setVersion(1L);row.setClaimToken(token);
        when(rows.selectById(id)).thenReturn(row);
        when(adapters.require(any())).thenReturn(new ActionAdapterRegistry.Binding(definition,adapter));
        when(settlements.settle(eq(request),eq(1L),eq(token),any())).thenReturn(true);
    }
    @Test void v2StandalonePolicyKeepsExactConditionAtExecutionRegistryBoundary() throws Exception {
        var condition=new ActionPolicy.Condition(ActionPolicy.Kind.BUSINESS_EQUALS,"/amount",
                json.getNodeFactory().numberNode(new java.math.BigDecimal("0.900000000000000001")),List.of());
        var action=new ActionPolicy.Action("save",ActionExecutionContract.Type.SAVE_RESULT,condition,
                ActionPolicy.Idempotency.PRODUCTION_SOURCE_V1,null,ActionPolicy.FailureHandling.RECORD_ONLY,json.createObjectNode());
        String body=json.writeValueAsString(new ActionPolicy(ActionPolicy.SCHEMA,List.of(action)));
        var source=new ActionExecutionContract.Source("task","execution","conversation","result","result",SelectorAssetService.hash(body),"save","alice");
        var definition=request.adapter();
        String task="{\"pipelineSnapshot\":{\"release\":{\"schemaVersion\":\"iqc-pipeline-release-v2\"}}}";
        String input=json.createObjectNode().put("schemaVersion","iqc-action-production-input-v1").put("taskSnapshotJson",task).toString();
        id=source.idempotencyKey(definition.type());
        request=new ActionAdapter.Request(id,id,source,definition,1,input,SelectorAssetService.hash(input),body);
        when(claims.claim(id,0,actor)).thenReturn(new ActionClaimService.Claim(request,1,token));
        when(rows.selectById(id)).thenReturn(row);
        when(settlements.settle(eq(request),eq(1L),eq(token),any())).thenReturn(true);
        when(adapter.execute(request)).thenReturn(success());
        assertEquals(ActionExecutionService.Dispatch.SETTLED,service.execute(id,0,actor));
        var capture=ArgumentCaptor.forClass(ActionPolicy.Action.class);
        verify(adapters).require(capture.capture());
        assertEquals(0,new java.math.BigDecimal("0.900000000000000001").compareTo(capture.getValue().condition().value().decimalValue()));
        assertEquals(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,recorded().outcome());
    }

    private ActionAdapter.Receipt success() {
        return new ActionAdapter.Receipt(id,ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,"receipt","result",null);
    }
    private ActionAdapter.Receipt recorded() {
        var capture=ArgumentCaptor.forClass(ActionAdapter.Receipt.class);
        verify(settlements).settle(eq(request),eq(1L),eq(token),capture.capture());return capture.getValue();
    }
    @Test void noClaimCannotExecuteOrSettle() {
        when(claims.claim(id,0,actor)).thenReturn(null);
        assertEquals(ActionExecutionService.Dispatch.NOT_CLAIMED,service.execute(id,0,actor));
        verifyNoInteractions(adapter,settlements,rows,adapters);
    }
    @Test void actualReceiptIsPassedToSettlementOnce() {
        var receipt=success();when(adapter.execute(request)).thenReturn(receipt);
        assertEquals(ActionExecutionService.Dispatch.SETTLED,service.execute(id,0,actor));
        assertSame(receipt,recorded());verify(adapter,times(1)).execute(request);
    }
    @Test void exceptionAfterPotentialSideEffectIsUnknownAndNeverResent() {
        when(adapter.execute(request)).thenThrow(new IllegalStateException("response lost"));
        assertEquals(ActionExecutionService.Dispatch.SETTLED,service.execute(id,0,actor));
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN,recorded().outcome());
        assertNull(recordedEvidence());verify(adapter,times(1)).execute(request);
    }
    private String recordedEvidence() {return recorded().evidenceReference();}
    @Test void missingReceiptIsUnknown() {
        assertEquals(ActionExecutionService.Dispatch.SETTLED,service.execute(id,0,actor));
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN,recorded().outcome());
    }
    @Test void receiptForAnotherRequestCannotBecomeConfirmedSuccess() {
        when(adapter.execute(request)).thenReturn(new ActionAdapter.Receipt("b".repeat(64),ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,"receipt","result",null));
        assertEquals(ActionExecutionService.Dispatch.SETTLED,service.execute(id,0,actor));
        assertEquals(id,recorded().idempotencyKey());assertEquals(ActionExecutionContract.Outcome.UNKNOWN,recorded().outcome());
    }
    @Test void settlementOutagePreservesPendingWithoutRetryingAdapter() {
        when(adapter.execute(request)).thenReturn(success());
        when(settlements.settle(eq(request),eq(1L),eq(token),any())).thenThrow(new IllegalStateException("database unavailable"));
        assertEquals(ActionExecutionService.Dispatch.SETTLEMENT_PENDING,service.execute(id,0,actor));
        assertEquals(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,recorded().outcome());verify(adapter,times(1)).execute(request);
    }
    @Test void quarantinedClaimCannotSendOrSettle() {
        row.setStatus("REQUIRES_RECONCILIATION");
        assertEquals(ActionExecutionService.Dispatch.STALE_RECEIPT,service.execute(id,0,actor));verifyNoInteractions(adapter,settlements,adapters);
    }
    @Test void replacedClaimTokenCannotSendOrSettle() {
        row.setClaimToken("b".repeat(32));
        assertEquals(ActionExecutionService.Dispatch.STALE_RECEIPT,service.execute(id,0,actor));verifyNoInteractions(adapter,settlements,adapters);
    }
    @Test void staleSettlementCannotResend() {
        when(adapter.execute(request)).thenReturn(success());when(settlements.settle(eq(request),eq(1L),eq(token),any())).thenReturn(false);
        assertEquals(ActionExecutionService.Dispatch.STALE_RECEIPT,service.execute(id,0,actor));verify(adapter,times(1)).execute(request);
    }
}
