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
    private static final Set<String> PIPELINE_SERIES=Set.of("pipelineTps","pipelineP95","pipelineActive","nodeAttemptTps","nodeSuccessRatio","nodeP95","nodeSkippedTps","nodeExecutorActive","nodeExecutorQueued");
    private static final Set<String> ALL_SERIES=java.util.stream.Stream.concat(SERIES.stream(),PIPELINE_SERIES.stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> TASK_SERIES=Set.of("taskStates","taskCompletionTps","unmeasuredExecutions","taskMetricsHealth","taskMetricsAge");
    private static final Set<String> WITH_TASK_SERIES=java.util.stream.Stream.concat(ALL_SERIES.stream(),TASK_SERIES.stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static boolean supportedSeries(Set<String> keys){
        var base=new java.util.HashSet<>(keys);base.remove("activeModelCalls");
        return base.equals(SERIES)||base.equals(ALL_SERIES)||base.equals(WITH_TASK_SERIES);
    }
    public IqcModelMonitoringClient.SourceStatus status(){
        if(!scope.isIqcAdministrator())throw IqcException.accessDenied("仅质检管理员可查看模型采集状态");
        IqcModelMonitoringClient.SourceStatus data;
        try{var response=control.status();if(response==null || !response.isSuccess() || response.getData()==null)throw new IllegalStateException();data=response.getData();}
        catch(RuntimeException unavailable){throw IqcException.invalidState("模型监控控制面不可用");}
        if(data.available() && (data.targets()==null || data.targets().length()>1048576))throw IqcException.invalidState("模型采集状态格式无效");
        return new IqcModelMonitoringClient.SourceStatus(data.available(),data.available()?"监控数据源可查询":"监控数据源不可用",data.available()?data.targets():null);
    }
    public IqcModelMonitoringClient.History history(String range){
        if(!scope.isIqcAdministrator())throw IqcException.accessDenied("仅质检管理员可查看全局模型运行监控");
        if(!RANGES.contains(range==null?"":range))throw IqcException.invalidArgument("监控时间范围无效");
        IqcModelMonitoringClient.History data;
        try{var response=control.history(range);
            if(response==null || !response.isSuccess() || response.getData()==null)throw new IllegalStateException("monitoring response unavailable");
            data=response.getData();
        }catch(RuntimeException unavailable){throw IqcException.invalidState("模型监控查询不可用，请检查控制面与采集服务");}
        if(!range.equals(data.range()) || data.start()==null || data.end()==null || !data.start().isBefore(data.end())
                || data.stepSeconds()<1 || data.series()==null || !supportedSeries(data.series().keySet())
                || data.series().values().stream().anyMatch(value->value==null || value.length()>1048576))
            throw IqcException.invalidState("模型监控返回格式无效");
        return new IqcModelMonitoringClient.History(data.range(),data.start(),data.end(),data.stepSeconds(),Map.copyOf(data.series()));
    }
}
