package io.github.opensabre.iqc.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.agent.dao.QualityAgentMapper;
import io.github.opensabre.iqc.agent.model.QualityAgent;
import io.github.opensabre.iqc.result.dao.InspectionResultMapper;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentEffectServiceTest {
    @Mock QualityAgentMapper agentMapper;
    @Mock InspectionTaskMapper taskMapper;
    @Mock InspectionResultMapper resultMapper;

    @BeforeEach
    void initializeMybatisLambdaMetadata() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "agent-effect-task"), InspectionTask.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "agent-effect-result"), InspectionResult.class);
    }

    @Test
    void aggregatesOnlyTasksUsingRequestedVersionSnapshot() {
        when(agentMapper.selectById("agent-1")).thenReturn(new QualityAgent());
        when(taskMapper.selectList(any())).thenReturn(List.of(task("t1", 1), task("t2", 2)));
        when(resultMapper.selectList(any())).thenReturn(List.of(result("HIT", "HIGH", 60), result("NOT_HIT", "LOW", 100)));

        AgentEffectService.AgentEffectReport report = new AgentEffectService(agentMapper, taskMapper, resultMapper, new ObjectMapper())
                .report("agent-1", 1);

        assertThat(report.taskCount()).isEqualTo(1);
        assertThat(report.resultCount()).isEqualTo(2);
        assertThat(report.averageScore()).isEqualByComparingTo(new BigDecimal("80.0"));
        assertThat(report.hitRate()).isEqualByComparingTo(new BigDecimal("50.0"));
        assertThat(report.highRiskRate()).isEqualByComparingTo(new BigDecimal("50.0"));
        assertThat(report.errorRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private InspectionTask task(String id, int version) {
        InspectionTask task = new InspectionTask(); task.setId(id); task.setAgentSnapshotJson("{\"versionNo\":" + version + "}"); return task;
    }

    private InspectionResult result(String status, String risk, int score) {
        InspectionResult result = new InspectionResult(); result.setResultStatus(status); result.setRiskLevel(risk); result.setScore(score); return result;
    }
}
