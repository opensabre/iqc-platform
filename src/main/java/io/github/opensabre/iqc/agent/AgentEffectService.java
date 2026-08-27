package io.github.opensabre.iqc.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.agent.dao.QualityAgentMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.result.dao.InspectionResultMapper;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Aggregates persisted task results by immutable Agent version snapshot.
 */
@Service
@RequiredArgsConstructor
public class AgentEffectService {
    private final QualityAgentMapper agentMapper;
    private final InspectionTaskMapper taskMapper;
    private final InspectionResultMapper resultMapper;
    private final ObjectMapper objectMapper;

    /**
     * Returns effectiveness metrics for one Agent version without re-running historical conversations.
     */
    public AgentEffectReport report(String agentId, int versionNo) {
        if (agentMapper.selectById(agentId) == null) throw IqcException.notFound("Agent 不存在: " + agentId);
        List<InspectionTask> tasks = taskMapper.selectList(Wrappers.<InspectionTask>lambdaQuery().eq(InspectionTask::getAgentId, agentId));
        List<String> taskIds = tasks.stream().filter(task -> snapshotVersion(task.getAgentSnapshotJson()) == versionNo).map(InspectionTask::getId).toList();
        List<InspectionResult> results = taskIds.isEmpty() ? List.of() : resultMapper.selectList(
                Wrappers.<InspectionResult>lambdaQuery().in(InspectionResult::getTaskId, taskIds));
        long hits = results.stream().filter(result -> "HIT".equals(result.getResultStatus())).count();
        long highRisk = results.stream().filter(result -> "HIGH".equalsIgnoreCase(result.getRiskLevel())).count();
        long errors = results.stream().filter(result -> "ERROR".equals(result.getResultStatus()) || "PARTIAL_ERROR".equals(result.getResultStatus())).count();
        double averageScore = results.stream().map(InspectionResult::getScore).filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).average().orElse(0);
        return new AgentEffectReport(agentId, versionNo, taskIds.size(), results.size(), decimal(averageScore), rate(hits, results.size()),
                rate(highRisk, results.size()), rate(errors, results.size()));
    }

    private int snapshotVersion(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return -1;
        try { return objectMapper.readTree(snapshot).path("versionNo").asInt(-1); }
        catch (Exception ignored) { return -1; }
    }

    private BigDecimal rate(long count, long total) {
        return total == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(count * 100.0 / total).setScale(1, RoundingMode.HALF_UP);
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    public record AgentEffectReport(String agentId, int versionNo, long taskCount, long resultCount,
                                    BigDecimal averageScore, BigDecimal hitRate, BigDecimal highRiskRate,
                                    BigDecimal errorRate) { }
}
