package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Logical adapter boundaries; actual source/claim SQL and transaction proxies require separate acceptance. */
class ExternalActionAdapterTest {
    private final ObjectMapper json=new ObjectMapper();
    private final ExternalActionPreparationService preparation=mock(ExternalActionPreparationService.class);
    private final HttpExternalActionTransport transport=mock(HttpExternalActionTransport.class);
    private final IqcDataScope scope=mock(IqcDataScope.class);
    private ExternalActionConfiguration configuration() { return new ExternalActionConfiguration("receiver",1,json.createObjectNode()); }
    private ExternalActionDestinations.Destination destination(ActionExecutionContract.Type type) {
        return new ExternalActionDestinations.Destination("receiver",1,type,URI.create("https://receiver.example/events"),
                URI.create("https://receiver.example/receipts/"),100,1000,4096,null);
    }
    private ExternalActionAdapter adapter(ActionExecutionContract.Type type) {
        return new ExternalActionAdapter(type,preparation,new ExternalActionDestinations(List.of(destination(type))),transport,scope);
    }
    private ActionAdapter.Request request(ActionExecutionContract.Type type) {
        var source=new ActionExecutionContract.Source("task","execution","conversation","result","result",
                SelectorAssetService.hash("{}"),"external","alice");
        var key=source.idempotencyKey(type);
        return new ActionAdapter.Request(key,key,source,ExternalActionPreparationService.definition(type),1,"{}",SelectorAssetService.hash("{}"),"{}");
    }
    @ParameterizedTest @EnumSource(value=ActionExecutionContract.Type.class,names={"WEBHOOK","CREATE_WORK_ORDER","CREATE_COACHING_TASK"})
    void allTypesPrepareThenAuthorizeAndSendOnce(ActionExecutionContract.Type type) {
        var request=request(type);var config=configuration();var route=destination(type);
        when(preparation.prepareSend(request)).thenReturn(config);
        var receipt=new ActionAdapter.Receipt(request.idempotencyKey(),ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,"receipt:1","result:1",null);
        when(transport.send(request,config,route)).thenReturn(receipt);
        assertSame(receipt,adapter(type).execute(request));
        var ordered=inOrder(preparation,scope,transport);
        ordered.verify(preparation).prepareSend(request);
        ordered.verify(scope).requireScheduledResources(ActionEnqueueService.REQUIRED_GRANTS);
        ordered.verify(transport).send(request,config,route);
        verify(transport,never()).lookup(any(),any(),any());
    }
    @Test void authorizationLostAfterPreparationCannotSend() {
        var request=request(ActionExecutionContract.Type.WEBHOOK);
        when(preparation.prepareSend(request)).thenReturn(configuration());
        doThrow(new IllegalStateException("grant revoked")).when(scope).requireScheduledResources(any());
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN,adapter(ActionExecutionContract.Type.WEBHOOK).execute(request).outcome());
        verifyNoInteractions(transport);
    }
    @Test void failedPreparationCannotSend() {
        var request=request(ActionExecutionContract.Type.WEBHOOK);
        when(preparation.prepareSend(request)).thenThrow(new IllegalStateException("claim changed"));
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN,adapter(ActionExecutionContract.Type.WEBHOOK).execute(request).outcome());
        verifyNoInteractions(transport,scope);
    }
    @Test void historicalLookupCannotAuthorizeSendAndNeverFallsBackToPost() {
        var type=ActionExecutionContract.Type.WEBHOOK;var request=request(type);var config=configuration();
        when(preparation.prepareLookup(request)).thenReturn(config);
        when(transport.lookup(request,config,destination(type))).thenThrow(new IllegalStateException("receipt unavailable"));
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN,adapter(type).reconcile(request).outcome());
        verify(preparation).prepareLookup(request);verify(preparation,never()).prepareSend(any());
        verify(transport).lookup(request,config,destination(type));verify(transport,never()).send(any(),any(),any());
        verifyNoInteractions(scope);
    }
    @Test void mismatchedTypeAndUnavailableVersionRejectBeforeIo() {
        var adapter=adapter(ActionExecutionContract.Type.WEBHOOK);
        assertThrows(IllegalArgumentException.class,()->adapter.execute(request(ActionExecutionContract.Type.CREATE_WORK_ORDER)));
        assertThrows(IllegalArgumentException.class,()->adapter.validateConfiguration(json.valueToTree(
                new ExternalActionConfiguration("receiver",2,json.createObjectNode()))));
        verifyNoInteractions(preparation,transport,scope);
    }
}
