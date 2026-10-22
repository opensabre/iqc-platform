package io.github.opensabre.iqc.pipelineasset;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.pipelineasset.dao.*;
import io.github.opensabre.iqc.result.IsolatedMysqlMigrationIT;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.quality.evaluation.BenchmarkVariableComparator;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real asset locking, immutable revision mapper and Spring transactions; publication resolution is isolated. */
class IsolatedMysqlRolloutServiceIT {
    private static final String SERVER="jdbc:mysql://127.0.0.1:34307/";
    private static final String OPTIONS="?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
    private static final String SCHEMA="iqc_rollout_service_"+Long.toHexString(System.nanoTime());
    private static AnnotationConfigApplicationContext context;
    private static boolean created;
    private JdbcTemplate jdbc;
    private PipelineRolloutService service;
    private String id;
    private String conversationId;
    @BeforeAll static void start() throws Exception {
        IsolatedMysqlMigrationIT.verifyIsolatedServer();
        try(var db=DriverManager.getConnection(SERVER+OPTIONS,"root","");var sql=db.createStatement()) {
            sql.execute("CREATE DATABASE "+SCHEMA+" CHARACTER SET utf8mb4");created=true;
        }
        var flyway=Flyway.configure().dataSource(SERVER+SCHEMA+OPTIONS,"root","")
                .locations("classpath:db/migration/mysql").cleanDisabled(true).load();
        flyway.migrate();flyway.validate();assertThat(flyway.migrate().migrationsExecuted).isZero();
        context=new AnnotationConfigApplicationContext(Config.class);
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(PipelineRolloutService.class))).isTrue();
    }
    @AfterAll static void close() throws Exception {
        if(context!=null)context.close();
        if(created) {
            IsolatedMysqlMigrationIT.verifyIsolatedServer();
            try(var db=DriverManager.getConnection(SERVER+OPTIONS,"root","");var sql=db.createStatement()) {sql.execute("DROP DATABASE "+SCHEMA);}
        }
    }
    @BeforeEach void fixture() {
        jdbc=new JdbcTemplate(context.getBean(DataSource.class));service=context.getBean(PipelineRolloutService.class);
        id="p"+UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO iqc_pipeline_asset(id,code,name,draft_config_json,status) VALUES(?,?,?,'{}','ACTIVE')",id,id,id);
        conversationId="c"+UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO iqc_conversation(id,source_file_name,source_fingerprint,message_count,status) VALUES(?,'processed.txt',?,1,'IMPORTED')",conversationId,conversationId);
        jdbc.update("INSERT INTO iqc_conversation_message(id,conversation_id,sequence_no,speaker_role,relative_time,content,raw_line,line_number) VALUES(?,?,1,'customer','00:00:01','original processed conversation','original processed conversation',1)","m"+conversationId,conversationId);
    }
    private PipelineRolloutPolicy policy(int ratio) {
        return new PipelineRolloutPolicy(PipelineRolloutPolicy.SCHEMA,id,1,2,ratio,"salt_0001",PipelineRolloutPolicy.State.ACTIVE,
                new BenchmarkVariableComparator.Selection(BenchmarkVariableComparator.Dimension.PIPELINE,null,null));
    }
    @Test void concurrentExpectedRevisionHasOneSuccessAndOneDomainCasRejection() throws Exception {
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            Callable<String> append=()->{ready.countDown();if(!go.await(10,TimeUnit.SECONDS))throw new IllegalStateException("barrier");
                try {service.append(id,0,policy(5000));return "created";}
                catch(io.github.opensabre.iqc.governance.IqcException e) {return e.getMessage();}};
            var first=workers.submit(append);var second=workers.submit(append);
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();
            assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("created","灰度策略修订已变化，请重新读取");
        } finally {go.countDown();}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_pipeline_rollout_revision WHERE pipeline_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(service.current(id).revisionNo()).isEqualTo(1);
    }
    @Test void lockingReadSeesCommittedRevisionDespiteOuterRepeatableReadSnapshot() throws Exception {
        var first=service.append(id,0,policy(1000));
        var tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.executeWithoutResult(status->{
            assertThat(service.current(id).revisionNo()).isEqualTo(1);
            try(var worker=Executors.newSingleThreadExecutor()) {
                try {assertThat(worker.submit(()->service.append(id,1,policy(2000))).get(20,TimeUnit.SECONDS).revisionNo()).isEqualTo(2);}
                catch(Exception e) {throw new IllegalStateException(e);}
            }
            // Consistent reads retain the old snapshot; append must use a locking/current read.
            assertThat(service.current(id).revisionNo()).isEqualTo(1);
            assertThatThrownBy(()->service.append(id,1,policy(3000))).hasMessageContaining("修订已变化");
            status.setRollbackOnly();
        });
        assertThat(service.current(id).revisionNo()).isEqualTo(2);
        assertThat(service.history(id,null,10).get(1).contentHash()).isEqualTo(first.contentHash());
    }
    @Test void outerRollbackRemovesRevisionAndAllowsSameExpectedRevisionAgain() {
        var tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        tx.executeWithoutResult(status->{assertThat(service.append(id,0,policy(1000)).revisionNo()).isEqualTo(1);status.setRollbackOnly();});
        assertThat(service.current(id).revisionNo()).isZero();
        assertThat(service.append(id,0,policy(2000)).revisionNo()).isEqualTo(1);
    }
    private io.github.opensabre.iqc.task.model.InspectionTask createTask(String key) {
        return context.getBean(PipelineRolloutTaskService.class).create(id,"rollout task",List.of(conversationId),null,key);
    }
    @Test void concurrentSameRequestMaterializesExactlyOneFrozenTask() throws Exception {
        service.append(id,0,policy(5000));
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            Callable<io.github.opensabre.iqc.task.model.InspectionTask> create=()->{
                ready.countDown();if(!go.await(10,TimeUnit.SECONDS))throw new IllegalStateException("barrier");return createTask("same-request");};
            var first=workers.submit(create);var second=workers.submit(create);
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();
            var a=first.get(30,TimeUnit.SECONDS);var b=second.get(30,TimeUnit.SECONDS);
            assertThat(a.getId()).isEqualTo(b.getId());assertThat(a.getRuleSnapshotJson()).isEqualTo(b.getRuleSnapshotJson());
            assertThat(a.getStatus()).isEqualTo("CREATED");assertThat(a.getTotalMessages()).isEqualTo(1);
            var json=context.getBean(ObjectMapper.class);
            assertThat(json.readTree(a.getRuleSnapshotJson()).at("/productionInputs/conversations/0/messages/0/content").asText())
                    .isEqualTo("original processed conversation");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE id=?",Integer.class,a.getId())).isEqualTo(1);
        } finally {go.countDown();}
    }
    @Test void replayRetainsOriginalRevisionAndInputAfterExpansionAndDisable() throws Exception {
        service.append(id,0,policy(1000));var original=createTask("replay");
        service.append(id,1,policy(9000));
        jdbc.update("UPDATE iqc_pipeline_asset SET status='DISABLED' WHERE id=?",id);
        jdbc.update("UPDATE iqc_conversation_message SET content='changed live content' WHERE conversation_id=?",conversationId);
        var replay=createTask("replay");assertThat(replay.getId()).isEqualTo(original.getId());
        assertThat(replay.getRuleSnapshotJson()).isEqualTo(original.getRuleSnapshotJson());
        assertThat(context.getBean(ObjectMapper.class).readTree(replay.getRuleSnapshotJson()).at("/rolloutSnapshot/evidence/revision/revisionNo").asInt()).isEqualTo(1);
        assertThatThrownBy(()->createTask("new-request")).hasMessageContaining("已停用");
        assertThatThrownBy(()->context.getBean(PipelineRolloutTaskService.class).create(id,"different name",List.of(conversationId),1,"replay"))
                .hasMessageContaining("原灰度任务请求内容不同");
    }
    @Test void taskWriteFailureRollsBackInsertAndSameRequestCanRetry() {
        service.append(id,0,policy(5000));
        String taskId=PipelineRolloutTaskEvidence.taskId("actor-1",id,"write-failure",context.getBean(ObjectMapper.class));
        jdbc.execute("CREATE TRIGGER reject_rollout_task_update BEFORE UPDATE ON iqc_inspection_task FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated rollout task write rejection'");
        try {
            assertThatThrownBy(()->createTask("write-failure")).hasStackTraceContaining("isolated rollout task write rejection");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE id=?",Integer.class,taskId)).isZero();
        } finally {jdbc.execute("DROP TRIGGER reject_rollout_task_update");}
        assertThat(createTask("write-failure").getId()).isEqualTo(taskId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE id=?",Integer.class,taskId)).isEqualTo(1);
    }
    @Test void materializationHoldsAssetLockUntilCommitBeforePolicyExpansion() throws Exception {
        service.append(id,0,policy(1000));
        var tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        var started=new CountDownLatch(1);
        try(var worker=Executors.newSingleThreadExecutor()) {
            var pending=new java.util.concurrent.atomic.AtomicReference<Future<PipelineRolloutService.Revision>>();
            var original=tx.execute(status->{
                var task=createTask("before-expansion");
                pending.set(worker.submit(()->{started.countDown();return service.append(id,1,policy(9000));}));
                try {
                    assertThat(started.await(10,TimeUnit.SECONDS)).isTrue();
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                    int waits=0;
                    while(System.nanoTime()<deadline) {
                        waits=jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID AND l.ENGINE=w.ENGINE WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='iqc_pipeline_asset'",Integer.class,SCHEMA);
                        if(waits>0)break;
                        Thread.sleep(20);
                    }
                    assertThat(waits).as("policy append must wait on the real asset row lock").isPositive();
                    assertThat(pending.get().isDone()).isFalse();
                } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return task;
            });
            assertThat(pending.get().get(30,TimeUnit.SECONDS).revisionNo()).isEqualTo(2);
            var next=createTask("after-expansion");var json=context.getBean(ObjectMapper.class);
            assertThat(json.readTree(original.getRuleSnapshotJson()).at("/rolloutSnapshot/evidence/revision/revisionNo").asInt()).isEqualTo(1);
            assertThat(json.readTree(next.getRuleSnapshotJson()).at("/rolloutSnapshot/evidence/revision/revisionNo").asInt()).isEqualTo(2);
            assertThat(createTask("before-expansion").getRuleSnapshotJson()).isEqualTo(original.getRuleSnapshotJson());
        }
    }
    @Test void taskMaterializationUsesLatestPolicyDespiteOuterRepeatableReadSnapshot() throws Exception {
        service.append(id,0,policy(1000));
        var tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var task=tx.execute(status->{
            assertThat(service.current(id).revisionNo()).isEqualTo(1);
            try(var worker=Executors.newSingleThreadExecutor()) {
                try {assertThat(worker.submit(()->service.append(id,1,policy(9000))).get(20,TimeUnit.SECONDS).revisionNo()).isEqualTo(2);}
                catch(Exception e) {throw new IllegalStateException(e);}
            }
            assertThat(service.current(id).revisionNo()).isEqualTo(1);
            return createTask("old-outer-snapshot");
        });
        var root=context.getBean(ObjectMapper.class).readTree(task.getRuleSnapshotJson());
        assertThat(root.at("/rolloutSnapshot/evidence/revision/revisionNo").asInt()).isEqualTo(2);
        assertThat(root.at("/rolloutSnapshot/evidence/revision/snapshot/policy/candidateBasisPoints").asInt()).isEqualTo(9000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE conversation_id=?",Integer.class,conversationId)).isEqualTo(1);
    }
    private void storePublishedSources() throws Exception {
        var json=context.getBean(ObjectMapper.class);
        for(int version=1;version<=2;version++) {
            String body=json.writeValueAsString(Config.release(json,version-1));
            jdbc.update("INSERT INTO iqc_pipeline_asset_version(id,pipeline_id,version_no,source_draft_revision,snapshot_json,content_hash) VALUES(?,?,?,?,?,?)",
                    "v"+version+id,id,version,version,body,PipelineAssetService.hash(body));
        }
    }
    private void validateStoredTask(io.github.opensabre.iqc.task.model.InspectionTask task) throws Exception {
        context.getBean(PipelineRolloutExecutionGuard.class).validate(context.getBean(ObjectMapper.class).readTree(task.getRuleSnapshotJson()),task.getId());
    }
    @Test void originalDatabaseEvidenceRemainsValidAfterExpansionDisableAndLiveInputChange() throws Exception {
        storePublishedSources();service.append(id,0,policy(0));var task=createTask("guard-original");
        service.append(id,1,policy(10000));
        jdbc.update("UPDATE iqc_pipeline_asset SET status='DISABLED' WHERE id=?",id);
        jdbc.update("UPDATE iqc_conversation_message SET content='changed archive' WHERE conversation_id=?",conversationId);
        String original=jdbc.queryForObject("SELECT rule_snapshot_json FROM iqc_inspection_task WHERE id=?",String.class,task.getId());
        var revisions=jdbc.queryForList("SELECT id,revision_no,snapshot_json,content_hash FROM iqc_pipeline_rollout_revision WHERE pipeline_id=? ORDER BY revision_no",id);
        var versions=jdbc.queryForList("SELECT id,snapshot_json,content_hash FROM iqc_pipeline_asset_version WHERE pipeline_id=? ORDER BY version_no",id);
        validateStoredTask(task);
        assertThat(jdbc.queryForObject("SELECT rule_snapshot_json FROM iqc_inspection_task WHERE id=?",String.class,task.getId())).isEqualTo(original);
        assertThat(jdbc.queryForList("SELECT id,revision_no,snapshot_json,content_hash FROM iqc_pipeline_rollout_revision WHERE pipeline_id=? ORDER BY revision_no",id)).isEqualTo(revisions);
        assertThat(jdbc.queryForList("SELECT id,snapshot_json,content_hash FROM iqc_pipeline_asset_version WHERE pipeline_id=? ORDER BY version_no",id)).isEqualTo(versions);
        assertThat(context.getBean(ObjectMapper.class).readTree(original).at("/rolloutSnapshot/evidence/revision/revisionNo").asInt()).isEqualTo(1);
    }
    @Test void unselectedDatabaseVersionMissingOrCorruptFailsClosedAndRestorationPasses() throws Exception {
        storePublishedSources();service.append(id,0,policy(0));var task=createTask("guard-unselected");validateStoredTask(task);
        String body=jdbc.queryForObject("SELECT snapshot_json FROM iqc_pipeline_asset_version WHERE pipeline_id=? AND version_no=2",String.class,id);
        jdbc.update("UPDATE iqc_pipeline_asset_version SET snapshot_json='{}' WHERE pipeline_id=? AND version_no=2",id);
        assertThatThrownBy(()->validateStoredTask(task)).hasMessageContaining("原修订、发布来源或比较证据不一致");
        jdbc.update("UPDATE iqc_pipeline_asset_version SET snapshot_json=? WHERE pipeline_id=? AND version_no=2",body,id);
        validateStoredTask(task);
        jdbc.update("DELETE FROM iqc_pipeline_asset_version WHERE pipeline_id=? AND version_no=2",id);
        assertThatThrownBy(()->validateStoredTask(task)).hasMessageContaining("原修订、发布来源或比较证据不一致");
        jdbc.update("INSERT INTO iqc_pipeline_asset_version(id,pipeline_id,version_no,source_draft_revision,snapshot_json,content_hash) VALUES(?,?,2,2,?,?)","v2"+id,id,body,PipelineAssetService.hash(body));
        validateStoredTask(task);
    }
    @Test void storedPublicationDecisionCannotBeInventedForAnExistingFrozenTask() throws Exception {
        storePublishedSources();service.append(id,0,policy(0));var task=createTask("guard-admission");validateStoredTask(task);
        String invented="{\"schemaVersion\":\"other\"}";
        jdbc.update("UPDATE iqc_pipeline_asset_version SET publication_decision_json=?,publication_decision_hash=? WHERE pipeline_id=? AND version_no=2",invented,PipelineAssetService.hash(invented),id);
        assertThatThrownBy(()->validateStoredTask(task)).hasMessageContaining("原修订、发布来源或比较证据不一致");
        jdbc.update("UPDATE iqc_pipeline_asset_version SET publication_decision_json=NULL,publication_decision_hash=NULL WHERE pipeline_id=? AND version_no=2",id);
        validateStoredTask(task);
    }
    private void runRejectedTaskThroughRealWorker(io.github.opensabre.iqc.task.model.InspectionTask task) {
        var session=new SqlSessionTemplate(context.getBean(SqlSessionFactory.class));
        var tasks=session.getMapper(io.github.opensabre.iqc.task.dao.InspectionTaskMapper.class);
        var executions=session.getMapper(io.github.opensabre.iqc.task.dao.TaskExecutionMapper.class);
        String eid="e"+UUID.randomUUID().toString().replace("-","");String old="o"+eid;
        jdbc.update("UPDATE iqc_inspection_task SET status='QUEUED',current_execution_id=? WHERE id=?",eid,task.getId());
        jdbc.update("INSERT INTO iqc_task_execution(id,task_id,attempt_no,status) VALUES(?,?,1,'SUCCEEDED'),(?,?,2,'QUEUED')",old,task.getId(),eid,task.getId());
        String original=jdbc.queryForObject("SELECT rule_snapshot_json FROM iqc_inspection_task WHERE id=?",String.class,task.getId());
        var oldExecution=jdbc.queryForMap("SELECT * FROM iqc_task_execution WHERE id=?",old);
        var items=mock(io.github.opensabre.iqc.task.dao.TaskItemMapper.class);
        var messages=mock(io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper.class);
        var models=mock(io.github.opensabre.iqc.result.llm.LlmQualityProvider.class);
        var evaluator=mock(io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator.class);
        var assets=new PipelineAssetService(session.getMapper(PipelineAssetMapper.class),session.getMapper(PipelineAssetVersionMapper.class),
                null,null,null,null,null,null,context.getBean(ObjectMapper.class),context.getBean(IqcDataScope.class),null,tasks,null,null,null,
                context.getBeanProvider(PipelineRolloutExecutionGuard.class));
        var runner=new io.github.opensabre.iqc.result.InspectionExecutionService(tasks,
                mock(io.github.opensabre.iqc.conversation.dao.ConversationMapper.class),messages,
                mock(io.github.opensabre.iqc.result.dao.InspectionResultMapper.class),context.getBean(ObjectMapper.class),executions,items,
                context.getBean(IqcDataScope.class),models,mock(io.github.opensabre.governance.usage.UsageCounterRecorder.class),
                mock(io.github.opensabre.iqc.result.HierarchicalResultService.class),mock(io.github.opensabre.iqc.label.LabelCandidateService.class),
                mock(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.class),assets,evaluator);
        var states=context.getBean(io.github.opensabre.iqc.task.TaskExecutionStateService.class);
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(states)).isTrue();
        org.springframework.test.util.ReflectionTestUtils.setField(runner,"executionStates",states);
        runner.executePersisted(task.getId(),eid);
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_inspection_task WHERE id=?",String.class,task.getId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failed_messages FROM iqc_inspection_task WHERE id=?",Integer.class,task.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_task_execution WHERE id=?",String.class,eid)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_message FROM iqc_task_execution WHERE id=?",String.class,eid)).contains("原修订、发布来源或比较证据不一致");
        assertThat(jdbc.queryForMap("SELECT * FROM iqc_task_execution WHERE id=?",old)).isEqualTo(oldExecution);
        assertThat(jdbc.queryForObject("SELECT rule_snapshot_json FROM iqc_inspection_task WHERE id=?",String.class,task.getId())).isEqualTo(original);
        verifyNoInteractions(items,messages,models,evaluator);
    }
    @Test void workerPersistsFailureForMissingOrNullEvidenceWithoutTouchingOldAttempt() throws Exception {
        storePublishedSources();service.append(id,0,policy(0));var json=context.getBean(ObjectMapper.class);
        for(boolean absent:List.of(true,false)) {
            var task=createTask("worker-envelope-"+absent);
            var root=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(task.getRuleSnapshotJson());
            if(absent)root.remove("rolloutSnapshot");else root.putNull("rolloutSnapshot");
            jdbc.update("UPDATE iqc_inspection_task SET rule_snapshot_json=? WHERE id=?",root.toString(),task.getId());
            runRejectedTaskThroughRealWorker(task);
        }
    }
    @Test void workerPersistsFailureForCorruptUnselectedPublicationBeforeAnyConversationWork() throws Exception {
        storePublishedSources();service.append(id,0,policy(0));var task=createTask("worker-corrupt-source");
        validateStoredTask(task);
        jdbc.update("UPDATE iqc_pipeline_asset_version SET snapshot_json='{}' WHERE pipeline_id=? AND version_no=2",id);
        runRejectedTaskThroughRealWorker(task);
    }
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource dataSource(){return new DriverManagerDataSource(SERVER+SCHEMA+OPTIONS,"root","");}
        @Bean DataSourceTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean ObjectMapper json(){return new ObjectMapper().findAndRegisterModules();}
        @Bean IqcDataScope scope(){var scope=mock(IqcDataScope.class);when(scope.actorUserId()).thenReturn("actor-1");when(scope.canView(any(),any())).thenReturn(true);return scope;}
        @Bean SqlSessionFactory sessions(DataSource ds) throws Exception {
            var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
            config.addMapper(PipelineAssetMapper.class);config.addMapper(PipelineAssetVersionMapper.class);config.addMapper(PipelineRolloutRevisionMapper.class);
            config.addMapper(io.github.opensabre.iqc.task.dao.InspectionTaskMapper.class);
            config.addMapper(io.github.opensabre.iqc.task.dao.TaskExecutionMapper.class);
            config.addMapper(io.github.opensabre.iqc.conversation.dao.ConversationMapper.class);
            config.addMapper(io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper.class);
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);return factory.getObject();
        }
        @Bean PipelineAssetService pipelines(SqlSessionFactory factory,ObjectMapper json,IqcDataScope scope) {
            var assets=new SqlSessionTemplate(factory).getMapper(PipelineAssetMapper.class);
            var pipelines=spy(new PipelineAssetService(assets,null,null,null,null,null,null,null,json,scope,null,null,null,null));
            doAnswer(call->{String id=call.getArgument(0);var left=release(json,0);var right=release(json,1);
                return new PipelineAssetService.RolloutSources(
                    new PipelineAssetService.Prepared(id,1,null,left,PipelineAssetService.hash(json.valueToTree(left).toString())),
                    new PipelineAssetService.Prepared(id,2,null,right,PipelineAssetService.hash(json.valueToTree(right).toString())));
            }).when(pipelines).rolloutSourcesForTask(anyString(),any());return pipelines;
        }
        @Bean io.github.opensabre.iqc.task.TaskExecutionStateService executionStates(SqlSessionFactory factory) {
            var session=new SqlSessionTemplate(factory);
            return new io.github.opensabre.iqc.task.TaskExecutionStateService(session.getMapper(io.github.opensabre.iqc.task.dao.InspectionTaskMapper.class),
                    session.getMapper(io.github.opensabre.iqc.task.dao.TaskExecutionMapper.class));
        }
        @Bean PipelineRolloutExecutionGuard guard(SqlSessionFactory factory,ObjectMapper json) {
            var session=new SqlSessionTemplate(factory);
            return new PipelineRolloutExecutionGuard(session.getMapper(PipelineRolloutRevisionMapper.class),session.getMapper(PipelineAssetVersionMapper.class),json);
        }
        @Bean PipelineRolloutService rollouts(PipelineAssetService pipelines,SqlSessionFactory factory,ObjectMapper json,IqcDataScope scope) {
            return new PipelineRolloutService(pipelines,new SqlSessionTemplate(factory).getMapper(PipelineRolloutRevisionMapper.class),json,scope);
        }
        @Bean io.github.opensabre.iqc.conversation.ProductionInputSnapshotService inputs(SqlSessionFactory factory,IqcDataScope scope,ObjectMapper json) {
            var session=new SqlSessionTemplate(factory);
            return new io.github.opensabre.iqc.conversation.ProductionInputSnapshotService(
                session.getMapper(io.github.opensabre.iqc.conversation.dao.ConversationMapper.class),
                session.getMapper(io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper.class),scope,json);
        }
        @Bean io.github.opensabre.iqc.task.InspectionTaskService tasks(SqlSessionFactory factory,IqcDataScope scope,ObjectMapper json,
                io.github.opensabre.iqc.conversation.ProductionInputSnapshotService inputs,
                org.springframework.beans.factory.ObjectProvider<PipelineAssetService> pipelines) {
            var session=new SqlSessionTemplate(factory);
            return new io.github.opensabre.iqc.task.InspectionTaskService(
                session.getMapper(io.github.opensabre.iqc.task.dao.InspectionTaskMapper.class),
                session.getMapper(io.github.opensabre.iqc.conversation.dao.ConversationMapper.class),null,null,null,json,
                session.getMapper(io.github.opensabre.iqc.task.dao.TaskExecutionMapper.class),scope,null,null,inputs,pipelines);
        }
        @Bean PipelineRolloutTaskService rolloutTasks(PipelineAssetService pipelines,PipelineRolloutService rollouts,
                io.github.opensabre.iqc.task.InspectionTaskService tasks,ObjectMapper json,IqcDataScope scope) {
            return new PipelineRolloutTaskService(pipelines,rollouts,tasks,json,scope);
        }
        private static PipelineRelease release(ObjectMapper json,int retries) {
            var node=new io.github.opensabre.iqc.pipeline.PipelineDefinition.Node("rule","RULE","Rule","rule-v1",List.of(),Map.of(),json.createObjectNode(),null,3000,retries,
                io.github.opensabre.iqc.pipeline.PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
            return new PipelineRelease(PipelineRelease.SCHEMA,new io.github.opensabre.iqc.pipeline.PipelineDefinition(io.github.opensabre.iqc.pipeline.PipelineDefinition.SCHEMA,List.of(node)),
                new io.github.opensabre.iqc.fusion.FusionPolicy("fusion",1,io.github.opensabre.iqc.fusion.FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),
                new io.github.opensabre.iqc.scoring.InspectionScoring.Policy(io.github.opensabre.iqc.scoring.InspectionScoring.POLICY_VERSION,io.github.opensabre.iqc.scoring.InspectionScoring.Mode.DEDUCTION,100,60,List.of()),List.of());
        }
    }
}
