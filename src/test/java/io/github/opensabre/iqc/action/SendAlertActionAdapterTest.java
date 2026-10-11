package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.common.core.entity.vo.Result;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.shared.IqcNotificationClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SendAlertActionAdapterTest {
    private final SendAlertPreparationService preparation = mock(SendAlertPreparationService.class);
    private final IqcNotificationClient notifications = mock(IqcNotificationClient.class);
    private final IqcDataScope scope = mock(IqcDataScope.class);
    private final SendAlertActionAdapter adapter = new SendAlertActionAdapter(preparation, notifications, scope);
    private final SendAlertConfiguration configuration = new SendAlertConfiguration("IQC_ALERT", "EMAIL", "test@example.invalid", Map.of());
    private ActionAdapter.Request request;
    @BeforeEach void fixture() throws Exception {
        var json = new ObjectMapper();
        var action = new ActionPolicy.Action("alert", ActionExecutionContract.Type.SEND_ALERT,
                new ActionPolicy.Condition(ActionPolicy.Kind.ALWAYS, null, null, List.of()),
                ActionPolicy.Idempotency.PRODUCTION_SOURCE_V1, null, ActionPolicy.FailureHandling.RECORD_ONLY, json.valueToTree(configuration));
        String policy = json.writeValueAsString(new ActionPolicy(ActionPolicy.SCHEMA, List.of(action)));
        var source = new ActionExecutionContract.Source("task", "execution", "conversation", "result", "result",
                SelectorAssetService.hash(policy), "alert", "alice");
        String key = source.idempotencyKey(ActionExecutionContract.Type.SEND_ALERT);
        request = new ActionAdapter.Request(key, key, source, adapter.definition(), 1, "{}", SelectorAssetService.hash("{}"), policy);
        when(preparation.prepareSend(request)).thenReturn(configuration);
        when(preparation.prepareLookup(request)).thenReturn(configuration);
    }
    private IqcNotificationClient.Status success() {
        return new IqcNotificationClient.Status("iqc-notification-status-v1", request.idempotencyKey(), "b".repeat(32),
                "c".repeat(64), configuration.declaredHash(), "SUCCESS", "receipt");
    }
    @Test void preparesThenSendsOnceAndAcceptsOnlyMatchingDurableSuccess() {
        when(notifications.send(request.idempotencyKey(), configuration.send())).thenReturn(Result.success(success()));
        assertEquals(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS, adapter.execute(request).outcome());
        var order = inOrder(preparation, notifications);
        order.verify(preparation).prepareSend(request);
        order.verify(notifications).send(request.idempotencyKey(), configuration.send());
        verify(notifications, never()).lookup(any());
    }
    @Test void failedPreparationNeverCallsNotificationService() {
        when(preparation.prepareSend(request)).thenThrow(new IllegalStateException("source changed"));
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN, adapter.execute(request).outcome());
        verifyNoInteractions(notifications);
    }
    @Test void lostResponseNeverRetriesOrInventsConfirmedFailure() {
        when(notifications.send(any(), any())).thenThrow(new IllegalStateException("response lost"));
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN, adapter.execute(request).outcome());
        verify(notifications, times(1)).send(request.idempotencyKey(), configuration.send());
        verify(notifications, never()).lookup(any());
    }
    @Test void reconcileOnlyGetsHistoricalEvidenceWithoutSendAuthorization() {
        when(notifications.lookup(request.idempotencyKey())).thenReturn(Result.success(success()));
        assertEquals(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS, adapter.reconcile(request).outcome());
        verify(preparation).prepareLookup(request); verify(preparation, never()).prepareSend(any());
        verify(notifications, never()).send(any(), any()); verifyNoInteractions(scope);
    }
    @Test void missingHistoricalEvidenceOrLookupFailureCannotSend() {
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN, adapter.reconcile(request).outcome());
        when(notifications.lookup(any())).thenThrow(new IllegalStateException("unavailable"));
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN, adapter.reconcile(request).outcome());
        verify(notifications, never()).send(any(), any());
    }
    @Test void failedEnvelopeIsNeverAcceptedAsSuccess() {
        when(notifications.send(any(), any())).thenReturn(Result.fail());
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN, adapter.execute(request).outcome());
        when(notifications.lookup(any())).thenReturn(Result.fail());
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN, adapter.reconcile(request).outcome());
    }
}
