package io.github.opensabre.iqc.governance;

import io.github.opensabre.common.core.exception.BaseException;

/** Domain exception mapped by OpenSabre's standard WebMVC error handler. */
public final class IqcException extends BaseException {
    private IqcException(IqcErrorType type, String message) {
        super(type, message);
    }

    private IqcException(IqcErrorType type, String message, Throwable cause) {
        super(type, message, cause);
    }

    public static IqcException invalidArgument(String message) {
        return new IqcException(IqcErrorType.INVALID_ARGUMENT, message);
    }

    public static IqcException invalidArgument(String message, Throwable cause) {
        return new IqcException(IqcErrorType.INVALID_ARGUMENT, message, cause);
    }

    public static IqcException notFound(String message) {
        return new IqcException(IqcErrorType.RESOURCE_NOT_FOUND, message);
    }

    public static IqcException accessDenied(String message) {
        return new IqcException(IqcErrorType.ACCESS_DENIED, message);
    }

    public static IqcException invalidState(String message) {
        return new IqcException(IqcErrorType.INVALID_STATE, message);
    }

    public static IqcException taskNotExecutable(String message) {
        return new IqcException(IqcErrorType.INSPECTION_TASK_NOT_EXECUTABLE, message);
    }
}
