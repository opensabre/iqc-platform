package io.github.opensabre.iqc.integration.iac;

import io.github.opensabre.common.core.entity.vo.Result;
import io.github.opensabre.iqc.governance.IqcException;

/** A missing catalog entry differs from an unavailable or failed remote service. */
public final class IacResponses {
    private IacResponses() { }

    public static <T> T required(Result<T> response) {
        if (response == null || !Result.SUCCESSFUL_CODE.equals(response.getCode()) || response.getData() == null)
            throw IqcException.invalidState("IAC 智能体服务调用失败，请检查服务状态和授权");
        return response.getData();
    }

    public static <T> T optional(Result<T> response) {
        if (response != null && "IAC-1004".equals(response.getCode())) return null;
        return required(response);
    }
}
