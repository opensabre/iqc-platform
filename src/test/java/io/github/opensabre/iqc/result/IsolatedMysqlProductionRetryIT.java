package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.ProductionInputSnapshotService;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.task.dao.*;
import io.github.opensabre.iqc.task.retry.*;
import io.github.opensabre.iqc.schedule.*;
import io.github.opensabre.iqc.shared.IqcDataScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real queue transactions and frozen-input decoding; external asset validation is isolated. */
class IsolatedMysqlProductionRetryIT {
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static InspectionExecutionService queue;
    private static ObjectMapper json;
    private String snapshot;

    @BeforeAll
    static void start() throws Exception {
        // Reuse the exact disposable-server identity guard and formal Flyway path.
        IsolatedMysqlTaskExecutionStateIT.start();
        context = new AnnotationConfigApplicationContext(RetryConfiguration.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        queue = context.getBean(InspectionExecutionService.class);
        json = context.getBean(ObjectMapper.class);
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(queue)).isTrue();
    }

    @AfterAll
    static void close() {
        if (context != null) context.close();
        IsolatedMysqlTaskExecutionStateIT.close();
    }

    @BeforeEach
    void fixture() throws Exception {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_retry_item_write");
        jdbc.execute("DROP TRIGGER IF EXISTS reject_retry_finish_write");
        jdbc.update("DELETE FROM iqc_trigger_attempt_event");
        jdbc.update("DELETE FROM iqc_model_gateway_call");
        jdbc.update("DELETE FROM iqc_task_item");
        jdbc.update("DELETE FROM iqc_task_execution");
        jdbc.update("DELETE FROM iqc_inspection_task");
        var input = new ProductionInputSnapshotService.Input("conversation", "archive-revision",
                json.createObjectNode().put("businessType", "原业务"), List.of(
                new ProductionInputSnapshotService.Message("message-done", 1, "agent", "原成功文本", null),
                new ProductionInputSnapshotService.Message("message-failed", 2, "customer", "原失败文本", null)));
        var body = json.valueToTree(List.of(input));
        var root = json.createObjectNode();
        root.putObject("executionStrategy").put("mode", "PIPELINE");
        root.putObject("pipelineSnapshot").put("testDependencyBoundary", true);
        var envelope = root.putObject("productionInputs").put("schemaVersion", ProductionInputSnapshotService.SCHEMA)
                .put("hashAlgorithm", "selector-decimal-sha256-v1")
                .put("contentHash", SelectorAssetService.hash(body.toString()));
        envelope.set("conversations", body);
        snapshot = root.toString();
        jdbc.update("""
                INSERT INTO iqc_inspection_task(id,name,task_type,status,current_execution_id,attempt_count,
                conversation_ids_json,rule_snapshot_json,total_messages,processed_messages,failed_messages)
                VALUES ('retry-task','isolated retry','PIPELINE','PARTIAL_FAILED','failed-eid',1,?, ?,2,2,1)
                """, "[\"conversation\"]", snapshot);
        jdbc.update("""
                INSERT INTO iqc_task_execution(id,task_id,attempt_no,status,processed_messages,failed_messages)
                VALUES ('failed-eid','retry-task',1,'PARTIAL_FAILED',2,1)
                """);
        jdbc.update("""
                INSERT INTO iqc_task_item(id,task_id,execution_id,message_id,conversation_id,sequence_no,status,attempt_count,result_id,error_message)
                VALUES ('done','retry-task','failed-eid','message-done','conversation',1,'SUCCEEDED',1,'original-result',NULL),
                       ('failed','retry-task','failed-eid','message-failed','conversation',2,'FAILED',2,NULL,'old failure')
                """);
        clearInvocations(context.getBean(ConversationMessageMapper.class));
    }

    @Test
    void retryCopiesCompleteFrozenManifestAndOriginalSuccessWithoutReadingLiveMessages() {
        var task = queue.queueRetrySystem("retry-task", "failed-eid", 1);
        assertThat(task.getStatus()).isEqualTo("QUEUED");
        assertThat(task.getAttemptCount()).isEqualTo(2);
        assertThat(task.getRuleSnapshotJson()).isEqualTo(snapshot);
        assertThat(task.getProcessedMessages()).isEqualTo(1);
        assertThat(task.getFailedMessages()).isZero();
        assertManifest(task.getCurrentExecutionId());
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_task_execution WHERE id='failed-eid'", String.class))
                .isEqualTo("PARTIAL_FAILED");
        verifyNoInteractions(context.getBean(ConversationMessageMapper.class));
    }

    @Test
    void repeatedRetryPreservesEarlierSuccessAndConsumesNextAttempt() {
        var second = queue.queueRetrySystem("retry-task", "failed-eid", 1);
        jdbc.update("UPDATE iqc_inspection_task SET status='PARTIAL_FAILED' WHERE id='retry-task'");
        jdbc.update("UPDATE iqc_task_execution SET status='PARTIAL_FAILED' WHERE id=?", second.getCurrentExecutionId());
        jdbc.update("UPDATE iqc_task_item SET status='FAILED' WHERE execution_id=? AND message_id='message-failed'",
                second.getCurrentExecutionId());
        assertThat(queue.queueRetrySystem("retry-task", "failed-eid", 1)).isNull();
        var third = queue.queueRetrySystem("retry-task", second.getCurrentExecutionId(), 2);
        assertThat(third.getAttemptCount()).isEqualTo(3);
        assertThat(third.getProcessedMessages()).isEqualTo(1);
        assertThat(third.getRuleSnapshotJson()).isEqualTo(snapshot);
        assertManifest(third.getCurrentExecutionId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_task_execution", Integer.class)).isEqualTo(3);
    }

    @Test
    void concurrentObserversCreateExactlyOneNewGeneration() throws Exception {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> retry = () -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("retry barrier timed out");
                return queue.queueRetrySystem("retry-task", "failed-eid", 1) != null;
            };
            var first = workers.submit(retry);
            var second = workers.submit(retry);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally { go.countDown(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_task_execution", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM iqc_inspection_task", Integer.class)).isEqualTo(2);
    }

    @Test
    void staleAttemptOrExecutionCreatesNoGeneration() {
        assertThat(queue.queueRetrySystem("retry-task", "failed-eid", 2)).isNull();
        assertThat(queue.queueRetrySystem("retry-task", "other-eid", 1)).isNull();
        assertOriginalGeneration();
    }

    @Test
    void missingCheckpointRejectsReplayBeforeAnyGenerationWrite() {
        jdbc.update("DELETE FROM iqc_task_item WHERE id='failed'");
        assertThatThrownBy(() -> queue.queueRetrySystem("retry-task", "failed-eid", 1))
                .hasMessageContaining("生产检查点未完整覆盖冻结消息");
        assertOriginalGeneration();
    }

    @Test
    void successWithoutResultReferenceCannotBeInherited() {
        jdbc.update("UPDATE iqc_task_item SET result_id=NULL WHERE id='done'");
        assertThatThrownBy(() -> queue.queueRetrySystem("retry-task", "failed-eid", 1))
                .hasMessageContaining("生产检查点不在冻结会话中或重复");
        assertOriginalGeneration();
    }

    @Test
    void mutatedFrozenTextWithoutNewDigestRejectsReplay() throws Exception {
        var changed = json.readTree(snapshot);
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("productionInputs").path("conversations")
                .get(0).path("messages").get(1)).put("content", "偷偷替换文本");
        jdbc.update("UPDATE iqc_inspection_task SET rule_snapshot_json=? WHERE id='retry-task'", changed.toString());
        assertThatThrownBy(() -> queue.queueRetrySystem("retry-task", "failed-eid", 1))
                .hasMessageContaining("生产冻结输入协议或摘要无效");
        assertOriginalGeneration();
    }

    @Test
    void realItemInsertFailureRollsBackClaimExecutionAndPreviouslyCopiedSuccess() {
        jdbc.execute("""
                CREATE TRIGGER reject_retry_item_write BEFORE INSERT ON iqc_task_item FOR EACH ROW
                BEGIN
                  IF NEW.execution_id <> 'failed-eid' AND NEW.message_id = 'message-failed' THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'isolated retry item rejection';
                  END IF;
                END
                """);
        try {
            assertThatThrownBy(() -> queue.queueRetrySystem("retry-task", "failed-eid", 1))
                    .hasStackTraceContaining("isolated retry item rejection");
            assertOriginalGeneration();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_task_item", Integer.class)).isEqualTo(2);
        } finally { jdbc.execute("DROP TRIGGER IF EXISTS reject_retry_item_write"); }
        assertManifest(queue.queueRetrySystem("retry-task", "failed-eid", 1).getCurrentExecutionId());
    }

    private void assertOriginalGeneration() {
        assertThat(jdbc.queryForObject("SELECT current_execution_id FROM iqc_inspection_task", String.class)).isEqualTo("failed-eid");
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_inspection_task", String.class)).isEqualTo("PARTIAL_FAILED");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM iqc_inspection_task", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_task_execution", Integer.class)).isEqualTo(1);
    }

    /** Prepare real frozen policy and failed-generation evidence without any remote identity lookup. */
    private AutomaticRetryService.Frame automaticFrame() throws Exception {
        var bound = new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1", "user-alice", "alice", false, "group");
        var policy = new AutomaticRetryPolicy("iqc-automatic-retry-v1", 3, 1, 2, 10, null);
        var env = new AutomaticRetryService.Envelope("iqc-automatic-retry-envelope-v1", "retry-task", policy, bound,
                java.util.Set.of(AutomaticRetryService.CONFIGURE, "iqc:pipeline:task:create", "iqc:task:execute"));
        String body = json.writeValueAsString(env);
        jdbc.update("UPDATE iqc_inspection_task SET created_by='alice',automatic_retry_enabled=TRUE,retry_policy_json=?,retry_policy_hash=? WHERE id='retry-task'",
                body, SelectorAssetService.hash(body));
        jdbc.update("UPDATE iqc_task_execution SET updated_time=UTC_TIMESTAMP()-INTERVAL 60 SECOND WHERE id='failed-eid'");
        return context.getBean(AutomaticRetryService.class).peek("retry-task", java.time.Instant.now());
    }

    private ScheduledActorAccessService.Resolved actor() {
        return new ScheduledActorAccessService.Resolved("user-alice", "alice", false, "group", java.util.Set.of(),
                java.util.Set.of(AutomaticRetryService.CONFIGURE, "iqc:pipeline:task:create", "iqc:task:execute"), java.time.Instant.now());
    }

    private TriggerAttemptService.Handle begin(AutomaticRetryService.Frame frame) {
        assertThat(frame).isNotNull();
        return context.getBean(TriggerAttemptService.class).begin(new TriggerAttemptService.Context(TriggerAttemptService.Kind.TASK,
                frame.taskId(), frame.executionId(), frame.policyHash(), frame.retryAt(), frame.envelope().ownerBound()));
    }

    @Test
    void automaticFireCommitsQueueAndPositiveAttemptTogether() throws Exception {
        var frame = automaticFrame(); var handle = begin(frame);
        assertThat(context.getBean(AutomaticRetryService.class).fire(frame, actor(), handle)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_inspection_task", String.class)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_task_execution", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT outcome FROM iqc_trigger_attempt_event WHERE phase='FINISHED'", String.class))
                .isEqualTo("DOMAIN_COMMITTED");
        assertThat(jdbc.queryForObject("SELECT processed_units FROM iqc_trigger_attempt_event WHERE phase='FINISHED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void automaticQueueFailureRetainsStartAndAllowsIndependentFailureFact() throws Exception {
        var frame = automaticFrame(); var handle = begin(frame);
        jdbc.execute("""
                CREATE TRIGGER reject_retry_item_write BEFORE INSERT ON iqc_task_item FOR EACH ROW
                BEGIN IF NEW.execution_id <> 'failed-eid' THEN
                  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'isolated automatic queue rejection';
                END IF; END
                """);
        try {
            assertThatThrownBy(() -> context.getBean(AutomaticRetryService.class).fire(frame, actor(), handle))
                    .hasStackTraceContaining("isolated automatic queue rejection");
            assertOriginalGeneration();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_trigger_attempt_event", Integer.class)).isEqualTo(1);
            context.getBean(TriggerAttemptService.class).failed(handle, TriggerAttemptService.FailureStage.MATERIALIZATION,
                    new IllegalStateException("secret message must not persist"));
            assertThat(jdbc.queryForObject("SELECT outcome FROM iqc_trigger_attempt_event WHERE phase='FINISHED'", String.class)).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT error_type FROM iqc_trigger_attempt_event WHERE phase='FINISHED'", String.class)).isEqualTo("IllegalStateException");
            assertThat(jdbc.queryForObject("SELECT failure_stage FROM iqc_trigger_attempt_event WHERE phase='FINISHED'", String.class)).isEqualTo("MATERIALIZATION");
        } finally { jdbc.execute("DROP TRIGGER IF EXISTS reject_retry_item_write"); }
    }

    @Test
    void attemptFinishInsertFailureRollsBackEntireSuccessfullyPreparedQueue() throws Exception {
        var frame = automaticFrame(); var handle = begin(frame);
        jdbc.execute("""
                CREATE TRIGGER reject_retry_finish_write BEFORE INSERT ON iqc_trigger_attempt_event FOR EACH ROW
                BEGIN IF NEW.phase = 'FINISHED' THEN
                  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'isolated attempt finish rejection';
                END IF; END
                """);
        try {
            assertThatThrownBy(() -> context.getBean(AutomaticRetryService.class).fire(frame, actor(), handle))
                    .hasStackTraceContaining("isolated attempt finish rejection");
            assertOriginalGeneration();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_task_item", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_trigger_attempt_event", Integer.class)).isEqualTo(1);
        } finally { jdbc.execute("DROP TRIGGER IF EXISTS reject_retry_finish_write"); }
    }

    @Test
    void obsoleteAutomaticObservationAppendsNoChangeWithoutNewExecution() throws Exception {
        var frame = automaticFrame(); var handle = begin(frame);
        jdbc.update("UPDATE iqc_inspection_task SET automatic_retry_enabled=FALSE WHERE id='retry-task'");
        assertThat(context.getBean(AutomaticRetryService.class).fire(frame, actor(), handle)).isFalse();
        assertOriginalGeneration();
        assertThat(jdbc.queryForObject("SELECT outcome FROM iqc_trigger_attempt_event WHERE phase='FINISHED'", String.class)).isEqualTo("NO_CHANGE");
    }

    @Test
    void lateFailureFactCannotOverwriteCommittedPositiveAttempt() throws Exception {
        var frame = automaticFrame(); var handle = begin(frame);
        assertThat(context.getBean(AutomaticRetryService.class).fire(frame, actor(), handle)).isTrue();
        context.getBean(TriggerAttemptService.class).failed(handle, TriggerAttemptService.FailureStage.MATERIALIZATION, new IllegalStateException());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_trigger_attempt_event", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT outcome FROM iqc_trigger_attempt_event WHERE phase='FINISHED'", String.class)).isEqualTo("DOMAIN_COMMITTED");
    }

    private void assertManifest(String executionId) {
        var rows = jdbc.queryForList("SELECT message_id,status,attempt_count,result_id,error_message FROM iqc_task_item WHERE execution_id=? ORDER BY sequence_no", executionId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("message_id", "message-done").containsEntry("status", "SUCCEEDED")
                .containsEntry("result_id", "original-result").containsEntry("attempt_count", 1);
        assertThat(rows.get(1)).containsEntry("message_id", "message-failed").containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 2).containsEntry("result_id", null).containsEntry("error_message", null);
    }

    /** Keep real mappers/transactions while excluding external model and published-asset clients. */
    @Configuration(proxyBeanMethods = false)
    @Import(IsolatedMysqlTaskExecutionStateIT.DatabaseConfiguration.class)
    static class RetryConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); }
        @Bean ConversationMessageMapper messages() { return mock(ConversationMessageMapper.class); }
        @Bean ProductionInputSnapshotService productionInputSnapshots(ObjectMapper json) {
            return new ProductionInputSnapshotService(null, null, null, json);
        }
        @Bean IqcDataScope retryScope() {
            var scope = mock(IqcDataScope.class);
            when(scope.owner()).thenReturn("alice"); when(scope.groupId()).thenReturn("group");
            when(scope.canView("alice", "group")).thenReturn(true);
            return scope;
        }
        @Bean io.github.opensabre.iqc.conversation.dao.ConversationMapper retryConversations() {
            var mapper = mock(io.github.opensabre.iqc.conversation.dao.ConversationMapper.class);
            var row = new io.github.opensabre.iqc.conversation.model.Conversation();
            row.setCreatedBy("alice"); row.setOwnerGroupId("group");
            when(mapper.selectById("conversation")).thenReturn(row);
            return mapper;
        }
        @Bean TriggerAttemptService triggerAttempts(SqlSessionFactory factory, ObjectMapper json) {
            return new TriggerAttemptService(new SqlSessionTemplate(factory).getMapper(io.github.opensabre.iqc.schedule.dao.TriggerAttemptEventMapper.class),
                    json, null, null, null, null, null);
        }
        @Bean AutomaticRetryService automaticRetries(SqlSessionFactory factory, ObjectMapper json, IqcDataScope scope,
                io.github.opensabre.iqc.conversation.dao.ConversationMapper conversations,
                io.github.opensabre.iqc.result.llm.gateway.GatewayCallService calls,
                org.springframework.beans.factory.ObjectProvider<io.github.opensabre.iqc.task.InspectionTaskService> tasks,
                org.springframework.beans.factory.ObjectProvider<InspectionExecutionService> queue,
                org.springframework.beans.factory.ObjectProvider<TriggerAttemptService> attempts) {
            var session = new SqlSessionTemplate(factory);
            return new AutomaticRetryService(session.getMapper(InspectionTaskMapper.class), session.getMapper(TaskExecutionMapper.class),
                    conversations, null, scope, json, calls, tasks, queue, attempts);
        }
        @Bean InspectionExecutionService retryQueue(SqlSessionFactory factory, ObjectMapper json,
                ConversationMessageMapper messages, ProductionInputSnapshotService inputs) {
            var session = new SqlSessionTemplate(factory);
            var service = new InspectionExecutionService(session.getMapper(InspectionTaskMapper.class), null, messages,
                    null, json, session.getMapper(TaskExecutionMapper.class), session.getMapper(TaskItemMapper.class),
                    null, null, null, null, null, mock(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.class),
                    mock(io.github.opensabre.iqc.pipelineasset.PipelineAssetService.class),
                    mock(io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator.class));
            ReflectionTestUtils.setField(service, "productionInputSnapshots", inputs);
            return service;
        }
    }
}
