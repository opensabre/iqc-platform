package io.github.opensabre.iqc.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.agent.dao.QualityAgentMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.result.dao.InspectionResultMapper;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Aggregates persisted task results by immutable Agent version snapshot.
 */
@Service
public class AgentEffectService {
    private final QualityAgentMapper agentMapper;
    private final InspectionTaskMapper taskMapper;
    private final InspectionResultMapper resultMapper;
    private final ObjectMapper objectMapper;

    private final io.github.opensabre.iqc.shared.IqcDataScope scope;
    @org.springframework.beans.factory.annotation.Autowired
    public AgentEffectService(QualityAgentMapper agents,InspectionTaskMapper tasks,InspectionResultMapper results,ObjectMapper json,
            io.github.opensabre.iqc.shared.IqcDataScope scope){agentMapper=agents;taskMapper=tasks;resultMapper=results;objectMapper=json;this.scope=scope;}
    public AgentEffectService(QualityAgentMapper agents,InspectionTaskMapper tasks,InspectionResultMapper results,ObjectMapper json){this(agents,tasks,results,json,null);}

    /**
     * Returns effectiveness metrics for one Agent version without re-running historical conversations.
     */
    public AgentEffectReport report(String agentId, int versionNo) {
        if(versionNo<1)throw IqcException.invalidArgument("Agent版本必须为正整数");
        if(scope==null)throw IqcException.invalidState("Agent效果数据范围组件不可用");
        String owner=scope.owner();if(owner==null||owner.isBlank()||"system".equals(owner))throw IqcException.accessDenied("Agent效果读取缺少当前身份");
        if (agentMapper.selectById(agentId) == null) throw IqcException.notFound("Agent 不存在: " + agentId);
        var query=Wrappers.<InspectionTask>lambdaQuery().eq(InspectionTask::getAgentId,agentId);
        if(!scope.canViewAll()){
            String group=scope.groupId();query.and(value->value.eq(InspectionTask::getCreatedBy,owner)
                    .or(group!=null&&!group.isBlank(),nested->nested.eq(InspectionTask::getOwnerGroupId,group)));
        }
        List<InspectionTask> tasks=taskMapper.selectList(query);
        List<String> taskIds = tasks.stream().filter(task -> snapshotVersion(task.getAgentSnapshotJson()) == versionNo).map(InspectionTask::getId).toList();
        List<InspectionResult> results = taskIds.isEmpty() ? List.of() : resultMapper.selectList(
                Wrappers.<InspectionResult>lambdaQuery().in(InspectionResult::getTaskId, taskIds));
        long hits = results.stream().filter(result -> "HIT".equals(result.getResultStatus())).count();
        long highRisk = results.stream().filter(result -> "HIGH".equalsIgnoreCase(result.getRiskLevel())).count();
        long errors = results.stream().filter(result -> "ERROR".equals(result.getResultStatus()) || "PARTIAL_ERROR".equals(result.getResultStatus())).count();
        long scored=results.stream().filter(result->result.getScore()!=null).count();
        BigDecimal totalScore=results.stream().map(InspectionResult::getScore).filter(java.util.Objects::nonNull)
                .map(value->BigDecimal.valueOf(value.longValue())).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal averageScore=scored==0?null:totalScore.divide(BigDecimal.valueOf(scored),1,RoundingMode.HALF_UP);
        return new AgentEffectReport(agentId, versionNo, taskIds.size(), results.size(),
                averageScore, rate(hits, results.size()),
                rate(highRisk, results.size()), rate(errors, results.size()));
    }

    private int snapshotVersion(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return -1;
        try { return objectMapper.readTree(snapshot).path("versionNo").asInt(-1); }
        catch (Exception ignored) { return -1; }
    }

    private BigDecimal rate(long count, long total) {
        return total==0?null:BigDecimal.valueOf(count).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total),1,RoundingMode.HALF_UP);
    }

    public record AgentEffectReport(String agentId, int versionNo, long taskCount, long resultCount,
                                    BigDecimal averageScore, BigDecimal hitRate, BigDecimal highRiskRate,
                                    BigDecimal errorRate) { }
}
