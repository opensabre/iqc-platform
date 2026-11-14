package io.github.opensabre.iqc.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.agent.dao.QualityAgentMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.llm.LlmQualityProvider;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.rule.dao.QualityRuleMapper;
import io.github.opensabre.iqc.task.dao.*;
import io.github.opensabre.iqc.task.model.*;
import io.github.opensabre.iqc.task.processing.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UnifiedTaskLifecycleTest {
    @Test void runtimeCheckpointsStagesAndCommitsPendingResultWithoutScoringOrAnotherModelCall() throws Exception {
        var mapper = new ObjectMapper();
        var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/mysql/ddl/V1.1.33__ddl_add_engine_execution_result_set.sql")));
        var store = new EngineResultSetStore(jdbc, mapper);
        var tasks = mock(InspectionTaskMapper.class); var messages = mock(ConversationMessageMapper.class);
        var executions = mock(TaskExecutionMapper.class); var items = mock(TaskItemMapper.class);
        var results = mock(ConversationInspectionResultMapper.class);
        var provider = mock(LlmQualityProvider.class);
        var plans = new TaskEnginePlanService(mock(QualityRuleMapper.class), mock(QualityAgentMapper.class), mapper,
                new RegexEngineAdapter(mapper), new DlsEngineAdapter(mapper), new LlmEngineAdapter(provider, mapper));
        var regex = mapper.createObjectNode().put("schemaVersion", "iqc-unified-tags-v1");
        regex.putArray("tags").addObject().put("tagId", "10").put("ruleId", "20").put("ruleVersionNo", 1).put("expression", "退款");
        var llm = regex.deepCopy(); llm.putObject("agentSnapshot");
        var snapshot = new TaskWizardSnapshot(TaskWizardSnapshot.SCHEMA,
                new TaskEnginePlanService.FrozenPlan(TaskEnginePlanService.FrozenPlan.SCHEMA, List.of(
                        new TaskEnginePlanService.FrozenEngine(Operation.LABELING, "tags-REGEX", EngineType.REGEX, 1, regex),
                        new TaskEnginePlanService.FrozenEngine(Operation.LABELING, "tags-LLM", EngineType.LLM, 2, llm))),
                null, mapper.readTree("{\"labels\":[{\"id\":\"10\",\"name\":\"退款\"}]}"));
        var task = new InspectionTask(); task.setId("task"); task.setCurrentExecutionId("ex1"); task.setStatus("RUNNING");
        task.setRuleSnapshotJson(mapper.writeValueAsString(snapshot));
        when(tasks.selectById("task")).thenReturn(task); when(tasks.selectOne(any())).thenReturn(task);
        when(executions.selectList(any())).thenReturn(List.of());
        var message = new ConversationMessage(); message.setId("m1"); message.setConversationId("c1");
        message.setSequenceNo(1); message.setSpeakerRole("customer"); message.setContent("我要退款");
        when(messages.selectList(any())).thenReturn(List.of(message));
        var item = new TaskItem(); item.setId("item"); item.setTaskId("task"); item.setExecutionId("ex1");
        item.setConversationId("c1"); item.setMessageId("m1"); item.setStatus("PENDING");
        when(items.selectById("item")).thenReturn(item); when(items.updateById(any(TaskItem.class))).thenReturn(1);
        when(results.insert(any(ConversationInspectionResult.class))).thenReturn(1);
        var runtime = new TaskWizardRuntime(tasks, messages, executions, store, plans, new ConfiguredTaskResultProcessor(mapper),
                new TaskWizardResultService(tasks, items, results, mapper), mapper, new UnifiedTaskResultProcessor(mapper));
        runtime.execute(task, "ex1", snapshot, List.of(item));
        var saved = store.find("ex1", "c1").orElseThrow();
        assertThat(saved.engines()).extracting(EngineResult::status).containsExactly(ExecutionStatus.SUCCEEDED, ExecutionStatus.SKIPPED);
        assertThat(saved.engines().getFirst().observations().getFirst().status()).isEqualTo(ObservationStatus.MATCHED);
        assertThat(jdbc.queryForObject("SELECT revision FROM iqc_engine_execution_result_set", Integer.class)).isEqualTo(2);
        var persisted = org.mockito.ArgumentCaptor.forClass(ConversationInspectionResult.class);
        verify(results).insert(persisted.capture());
        assertThat(persisted.getValue().getResultStatus()).isEqualTo("PENDING");
        assertThat(persisted.getValue().getScoreStatus()).isEqualTo("PENDING");
        assertThat(persisted.getValue().getFinalScore()).isNull();
        assertThat(mapper.readTree(persisted.getValue().getBusinessItemResultsJson()).at("/output/processingStatus").asText()).isEqualTo("PENDING_POLICY");
        assertThat(item.getStatus()).isEqualTo("SUCCEEDED");
        verifyNoInteractions(provider);
        // A resumed same execution consumes its durable checkpoints and still makes no model call.
        runtime.execute(task, "ex1", snapshot, List.of(item));
        assertThat(store.find("ex1", "c1")).contains(saved);
        verifyNoInteractions(provider);
    }
}
