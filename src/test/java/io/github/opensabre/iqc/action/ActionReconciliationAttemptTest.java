package io.github.opensabre.iqc.action;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Tests local orchestration only; commit boundaries require real transactional integration tests. */
class ActionReconciliationAttemptTest {
    private final ActionReconciliationAttemptMapper mapper=mock(ActionReconciliationAttemptMapper.class);
    private final ActionReconciliationAttemptService service=new ActionReconciliationAttemptService(mapper);
    private final ActionReconciliationAttemptService.Attempt attempt=new ActionReconciliationAttemptService.Attempt(
            "a".repeat(32),"action","result",3,"alice","user-1","原因");
    @Test void startGeneratesUniqueIdsAndNormalizesTheReason() {
        when(mapper.start(anyString(),eq("action"),eq("result"),eq(3L),eq("alice"),eq("user-1"),eq("原因"),any())).thenReturn(1);
        var first=service.start("action","result",3,"alice","user-1"," 原因 ");
        var second=service.start("action","result",3,"alice","user-1","原因");
        assertThat(first.id()).matches("[a-f0-9]{32}").isNotEqualTo(second.id());
        assertThat(first.reason()).isEqualTo("原因");
    }
    @Test void startFailureCannotReturnAnAttempt() {
        assertThatThrownBy(()->service.start("action","result",3,"alice","user-1","原因")).isInstanceOf(RuntimeException.class);
    }
    @Test void invalidInputsCannotWrite() {
        assertThatThrownBy(()->service.start("action","result",0,"alice","user-1","原因")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.start("action","result",3,"alice","user-1"," ")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.start("action","result",3,"alice"," ","原因")).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(mapper);
    }
    @Test void eachNonSuccessOutcomeRequiresAnExactTerminalCas() {
        for(var outcome:ActionReconciliationAttemptService.Outcome.values()) {
            when(mapper.finish(eq(attempt.id()),eq("action"),eq("result"),eq(3L),eq("alice"),eq("user-1"),eq("原因"),eq(outcome.name()),isNull(),any())).thenReturn(1);
            service.finish(attempt,outcome);
            verify(mapper).finish(eq(attempt.id()),eq("action"),eq("result"),eq(3L),eq("alice"),eq("user-1"),eq("原因"),eq(outcome.name()),isNull(),any());
        }
    }
    @Test void terminalWriteFailureMustEscapeInsteadOfReportingDurableCompletion() {
        assertThatThrownBy(()->service.finish(attempt,ActionReconciliationAttemptService.Outcome.UNCONFIRMED)).isInstanceOf(RuntimeException.class);
    }
    @Test void confirmationUsesOnlyTheNextObservedVersion() {
        when(mapper.finish(eq(attempt.id()),eq("action"),eq("result"),eq(3L),eq("alice"),eq("user-1"),eq("原因"),eq("CONFIRMED"),eq(4L),any())).thenReturn(1);
        service.confirmed(attempt);
        verify(mapper).finish(eq(attempt.id()),eq("action"),eq("result"),eq(3L),eq("alice"),eq("user-1"),eq("原因"),eq("CONFIRMED"),eq(4L),any());
    }
}
