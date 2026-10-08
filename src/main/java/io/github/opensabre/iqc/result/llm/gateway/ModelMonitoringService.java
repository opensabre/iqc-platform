package io.github.opensabre.iqc.result.llm.gateway;

import io.github.opensabre.iqc.shared.IqcModelMonitoringClient;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.governance.IqcException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.Set;

/** Read-only fixed-query facade; no caller-supplied PromQL, endpoint or application selector. */
@Service @RequiredArgsConstructor
public class ModelMonitoringService {
    private final IqcModelMonitoringClient control;
    private final IqcDataScope scope;
    private static final Set<String> RANGES=Set.of("15m","1h","6h","24h","7d","30d");
    private static final Set<String> SERIES=Set.of("invocationTps","unknownRatio","p50","p95","p99","reportedTokens","usageCoverage","admission","settlement");
    public IqcModelMonitoringClient.History history(String range){
        if(!scope.isIqcAdministrator())throw IqcException.accessDenied("仅质检管理员可查看全局模型运行监控");
        if(!RANGES.contains(range==null?"":range))throw IqcException.invalidArgument("监控时间范围无效");
        IqcModelMonitoringClient.History data;
        try{var response=control.history(range);
            if(response==null || !response.isSuccess() || response.getData()==null)throw new IllegalStateException("monitoring response unavailable");
            data=response.getData();
        }catch(RuntimeException unavailable){throw IqcException.invalidState("模型监控查询不可用，请检查控制面与采集服务");}
        if(!range.equals(data.range()) || data.start()==null || data.end()==null || !data.start().isBefore(data.end())
                || data.stepSeconds()<1 || data.series()==null || !data.series().keySet().equals(SERIES)
                || data.series().values().stream().anyMatch(value->value==null || value.length()>1048576))
            throw IqcException.invalidState("模型监控返回格式无效");
        return new IqcModelMonitoringClient.History(data.range(),data.start(),data.end(),data.stepSeconds(),Map.copyOf(data.series()));
    }
}
