package io.github.opensabre.iqc.governance;

import io.github.opensabre.common.core.entity.vo.Result;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将 IQC 领域服务中的业务异常映射到 OpenSabre 统一 Result/错误码协议。
 * 基础设施异常不在这里吞掉，继续交给 Framework 默认处理器。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class IqcExceptionHandlerAdvice {

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<?> invalidArgument(IllegalArgumentException exception) {
        return Result.fail(IqcErrorType.INVALID_ARGUMENT, exception.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    public Result<?> accessDenied(SecurityException exception) {
        return Result.fail(IqcErrorType.ACCESS_DENIED, exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public Result<?> invalidState(IllegalStateException exception) {
        return Result.fail(IqcErrorType.INVALID_STATE, exception.getMessage());
    }
}
