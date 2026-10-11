package io.github.opensabre.iqc.action;

import io.github.opensabre.iqc.governance.IqcException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Local orchestration only; real transaction rollback requires the explicitly invoked MySQL IT. */
class ActionReconciliationReasonTest {
    private final ActionExecutionMapper actions=mock(ActionExecutionMapper.class);
    private final ActionReconciliationEventMapper journal=mock(ActionReconciliationEventMapper.class);
    private final ActionReconciliationAttemptService attempts=mock(ActionReconciliationAttemptService.class);
    private final ActionReconciliationStateService state=new ActionReconciliationStateService(actions,journal,attempts);
    private final String token="a".repeat(32);
    private final ActionExecutionContract.Source source=new ActionExecutionContract.Source("task","eid","conversation","result","decision",
            io.github.opensabre.iqc.selector.SelectorAssetService.hash("{}"),"alert","alice");
    private final ActionAdapter.Request request=new ActionAdapter.Request("action",source.idempotencyKey(ActionExecutionContract.Type.SEND_ALERT),source,
            new ActionAdapter.Definition(ActionExecutionContract.Type.SEND_ALERT,1,false),1,"{}",io.github.opensabre.iqc.selector.SelectorAssetService.hash("{}"),"{}");
    private final ActionAdapter.Receipt receipt=new ActionAdapter.Receipt(request.idempotencyKey(),ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,"proof","artifact",null);
    private boolean confirm(long version,String reason) {
        return state.confirm(request,version,token,receipt,"alice",reason,attempt(version,reason));
    }
    private ActionReconciliationAttemptService.Attempt attempt(long version,String reason) {
        return new ActionReconciliationAttemptService.Attempt("a".repeat(32),"action","result",version,"alice","user-1",reason==null?null:reason.trim());
    }
    private void winner() {
        when(actions.reconcileSuccessBound(eq(request),eq(2L),eq(token),any(),eq("proof"),eq("artifact"),eq("alice"))).thenReturn(1);
    }
    @Test void winningTransitionAttachesNormalizedReasonToTheExactNextEvent() {
        winner();when(journal.recordReason("action",3,2,"result","alice","已确认原始产物")).thenReturn(1);
        assertThat(confirm(2,"  已确认原始产物  ")).isTrue();
        verify(journal).recordReason("action",3,2,"result","alice","已确认原始产物");
        verify(attempts).confirmed(attempt(2,"已确认原始产物"));
    }
    @Test void lostObservationDoesNotAttachAReasonToAnotherOperatorsEvent() {
        assertThat(confirm(2,"核对原因")).isFalse();verifyNoInteractions(journal);
    }
    @Test void missingOrMismatchedEventCannotBeReportedAsConfirmation() {
        winner();assertThatThrownBy(()->confirm(2,"核对原因")).isInstanceOf(IqcException.class);
    }
    @Test void journalDatabaseFailureEscapesTheTransactionBoundary() {
        winner();when(journal.recordReason(anyString(),anyLong(),anyLong(),anyString(),anyString(),anyString()))
                .thenThrow(new IllegalStateException("journal unavailable"));
        assertThatThrownBy(()->confirm(2,"核对原因")).hasMessage("journal unavailable");
    }
    @Test void invalidReasonAndVersionCannotReachEitherMapper() {
        for(String reason:new String[]{null," ","a".repeat(1001)})
            assertThatThrownBy(()->confirm(2,reason)).isInstanceOf(IqcException.class);
        assertThatThrownBy(()->confirm(0,"核对原因")).isInstanceOf(IqcException.class);
        verifyNoInteractions(actions,journal);
    }
    @Test void exhaustedVersionDoesNotOverflowOrInventAnEvent() {
        assertThat(confirm(Long.MAX_VALUE,"核对原因")).isFalse();verifyNoInteractions(actions,journal);
    }
    @Test void attemptCompletionFailureCannotReportSuccessfulConfirmation() {
        winner();when(journal.recordReason(anyString(),anyLong(),anyLong(),anyString(),anyString(),anyString())).thenReturn(1);
        doThrow(new IllegalStateException("attempt unavailable")).when(attempts).confirmed(any());
        assertThatThrownBy(()->confirm(2,"核对原因")).hasMessage("attempt unavailable");
    }
    @Test void broaderActorOrUnconfirmedReceiptCannotReachTheStateMapper() {
        assertThatThrownBy(()->state.confirm(request,2,token,receipt,"bob","核对原因",attempt(2,"核对原因"))).isInstanceOf(IqcException.class);
        var unknown=new ActionAdapter.Receipt(request.idempotencyKey(),ActionExecutionContract.Outcome.UNKNOWN,null,null,"UNAVAILABLE");
        assertThatThrownBy(()->state.confirm(request,2,token,unknown,"alice","核对原因",attempt(2,"核对原因"))).isInstanceOf(IqcException.class);
        verifyNoInteractions(actions,journal);
    }

}
