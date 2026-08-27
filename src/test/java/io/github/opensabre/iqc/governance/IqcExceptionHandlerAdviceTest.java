package io.github.opensabre.iqc.governance;

import io.github.opensabre.common.core.entity.vo.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IqcExceptionHandlerAdviceTest {

    private final IqcExceptionHandlerAdvice advice = new IqcExceptionHandlerAdvice();

    @Test
    void mapsInvalidArgumentsToIqcErrorCode() {
        Result<?> result = advice.invalidArgument(new IllegalArgumentException("bad request"));

        assertEquals(IqcErrorType.INVALID_ARGUMENT.getCode(), result.getCode());
        assertEquals(IqcErrorType.INVALID_ARGUMENT.getMesg() + "：bad request", result.getMesg());
    }

    @Test
    void mapsSecurityFailuresToIqcErrorCode() {
        Result<?> result = advice.accessDenied(new SecurityException("denied"));

        assertEquals(IqcErrorType.ACCESS_DENIED.getCode(), result.getCode());
    }

    @Test
    void mapsInvalidStateToIqcErrorCode() {
        Result<?> result = advice.invalidState(new IllegalStateException("wrong state"));

        assertEquals(IqcErrorType.INVALID_STATE.getCode(), result.getCode());
    }
}
