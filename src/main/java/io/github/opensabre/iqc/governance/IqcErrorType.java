package io.github.opensabre.iqc.governance;

import io.github.opensabre.common.core.exception.ErrorType;

public enum IqcErrorType implements ErrorType {

    CONVERSATION_IMPORT_INVALID("IQC-1001", "会话文件解析失败"),
    INSPECTION_TASK_NOT_EXECUTABLE("IQC-1002", "质检任务当前不可执行"),
    INVALID_ARGUMENT("IQC-1003", "请求参数不合法"),
    RESOURCE_NOT_FOUND("IQC-1004", "IQC 业务资源不存在"),
    ACCESS_DENIED("IQC-1005", "无权访问 IQC 业务资源"),
    INVALID_STATE("IQC-1006", "IQC 业务状态不允许当前操作");

    private final String code;
    private final String message;

    IqcErrorType(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMesg() {
        return message;
    }
}
