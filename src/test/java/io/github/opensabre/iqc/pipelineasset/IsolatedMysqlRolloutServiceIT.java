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
        reset(context.getBean(io.github.opensabre.iqc.result.InspectionExecutionService.class));
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
    private io.github.opensabre.iqc.selector.SelectorAssetService.Prepared selector(String selectedId) throws Exception {
        when(context.getBean(IqcDataScope.class).canViewAll()).thenReturn(true);
        var json=context.getBean(ObjectMapper.class);
        var definition=new io.github.opensabre.iqc.selector.SelectorDefinition("iqc-selector-v1",null,null,List.of(selectedId),List.of(),null,null,10);
        String selectorId="s"+id,body=json.writeValueAsString(definition);
        jdbc.update("INSERT INTO iqc_selector_asset(id,code,name,draft_config_json,status) VALUES(?,?,?,?,'ACTIVE')",selectorId,selectorId,selectorId,body);
        jdbc.update("INSERT INTO iqc_selector_asset_version(id,selector_id,version_no,source_draft_revision,snapshot_json,content_hash) VALUES(?,?,1,1,?,?)","v"+selectorId,selectorId,body,io.github.opensabre.iqc.selector.SelectorAssetService.hash(body));
        return context.getBean(io.github.opensabre.iqc.selector.SelectorAssetService.class).publishedForTask(selectorId,1);
    }
    private io.github.opensabre.iqc.task.InspectionTaskService.PeriodicIdentity instance(java.time.Instant planned) {
        return new io.github.opensabre.iqc.task.InspectionTaskService.PeriodicIdentity("schedule"+id,"revision"+id,planned,context.getBean(ObjectMapper.class).createObjectNode().put("fixture","periodic materialization"));
    }
    private io.github.opensabre.iqc.task.model.InspectionTask periodic(io.github.opensabre.iqc.selector.SelectorAssetService.Prepared selector,java.time.Instant planned){
        return new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).execute(status->
                context.getBean(PipelineRolloutTaskService.class).createPeriodicWithSelector(id,"periodic rollout",selector,instance(planned),1));
    }
    @Test void realSelectorPeriodicReplayKeepsFrozenInputWhileNewInstanceUsesExpandedRevision() throws Exception {
        var selector=selector(conversationId);var planned=java.time.Instant.parse("2026-10-23T01:00:00Z");
        service.append(id,0,policy(0));var first=periodic(selector,planned);
        var json=context.getBean(ObjectMapper.class);var root=json.readTree(first.getRuleSnapshotJson());
        assertThat(root.at("/pipelineSnapshot/versionNo").asInt()).isEqualTo(1);
        assertThat(json.treeToValue(json.readTree(first.getSelectionFilterJson()).at("/selection/anchor"),java.time.Instant.class)).isEqualTo(planned);
        assertThat(first.getPlannedAtUtc()).isEqualTo(java.time.LocalDateTime.ofInstant(planned,java.time.ZoneOffset.UTC));
        assertThat(first.getPeriodicScheduleId()).isEqualTo(instance(planned).scheduleId());
        service.append(id,1,policy(10000));var second=periodic(selector,planned.plusSeconds(60));
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(json.readTree(second.getRuleSnapshotJson()).at("/pipelineSnapshot/versionNo").asInt()).isEqualTo(2);
        assertThat(json.readTree(second.getRuleSnapshotJson()).at("/rolloutSnapshot/evidence/revision/revisionNo").asInt()).isEqualTo(2);
        service.append(id,2,policy(0));var reverted=periodic(selector,planned.plusSeconds(120));
        assertThat(json.readTree(reverted.getRuleSnapshotJson()).at("/pipelineSnapshot/versionNo").asInt()).isEqualTo(1);
        assertThat(json.readTree(reverted.getRuleSnapshotJson()).at("/rolloutSnapshot/evidence/revision/revisionNo").asInt()).isEqualTo(3);
        jdbc.update("UPDATE iqc_selector_asset SET status='DISABLED' WHERE id=?",selector.selectorId());
        jdbc.update("UPDATE iqc_conversation_message SET content='changed after instance' WHERE conversation_id=?",conversationId);
        var replay=periodic(selector,planned);
        assertThat(replay.getId()).isEqualTo(first.getId());assertThat(replay.getRuleSnapshotJson()).isEqualTo(first.getRuleSnapshotJson());
        assertThat(replay.getSelectionFilterJson()).isEqualTo(first.getSelectionFilterJson());
        assertThat(json.readTree(replay.getRuleSnapshotJson()).at("/productionInputs/conversations/0/messages/0/content").asText()).isEqualTo("original processed conversation");
        assertThatThrownBy(()->periodic(selector,planned.plusSeconds(180))).hasMessageContaining("停用");
    }
    @Test void periodicInsertFailureRollsBackAndSameInstanceCanRetry() throws Exception {
        var selector=selector(conversationId);var planned=java.time.Instant.parse("2026-10-23T02:00:00Z");service.append(id,0,policy(5000));
        jdbc.execute("CREATE TRIGGER reject_periodic_rollout_update BEFORE UPDATE ON iqc_inspection_task FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated periodic rejection'");
        try {assertThatThrownBy(()->periodic(selector,planned)).hasStackTraceContaining("isolated periodic rejection");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE periodic_schedule_id=?",Integer.class,instance(planned).scheduleId())).isZero();
        } finally {jdbc.execute("DROP TRIGGER reject_periodic_rollout_update");}
        var task=periodic(selector,planned);assertThat(task.getStatus()).isEqualTo("CREATED");
        assertThat(periodic(selector,planned).getId()).isEqualTo(task.getId());
    }
    @Test void emptyPeriodicSelectorPersistsNoDataWithFrozenRevisionAndAnchor() throws Exception {
        var selector=selector("absent"+id);var planned=java.time.Instant.parse("2026-10-23T03:00:00Z");service.append(id,0,policy(0));
        var task=periodic(selector,planned);assertThat(task.getStatus()).isEqualTo("NO_DATA");assertThat(task.getTotalMessages()).isZero();
        assertThat(context.getBean(ObjectMapper.class).readTree(task.getSelectionFilterJson()).at("/selection/selectedConversationIds")).isEmpty();
        service.append(id,1,policy(10000));assertThat(periodic(selector,planned).getRuleSnapshotJson()).isEqualTo(task.getRuleSnapshotJson());
    }
    private io.github.opensabre.iqc.schedule.PeriodicScheduleService.Frame scheduleFrame(
            io.github.opensabre.iqc.selector.SelectorAssetService.Prepared selector,java.time.Instant due,
            io.github.opensabre.iqc.schedule.PeriodicDefinition.MisfirePolicy misfire,java.time.Instant observed) throws Exception {
        var scope=context.getBean(IqcDataScope.class);when(scope.owner()).thenReturn("actor-1");when(scope.canViewAll()).thenReturn(true);
        var definition=new io.github.opensabre.iqc.schedule.PeriodicDefinition("iqc-periodic-rollout-definition-v1","0 * * * * *","UTC",due,null,misfire,10,1,null,
                new io.github.opensabre.iqc.selector.SelectorAssetService.Reference(selector.selectorId(),1),null,new io.github.opensabre.iqc.schedule.PeriodicDefinition.RolloutReference(id));
        var bound=new io.github.opensabre.iqc.schedule.ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1","actor-1","actor-1",true,null);
        var release=new io.github.opensabre.iqc.schedule.PeriodicScheduleService.Published("iqc-periodic-rollout-release-v1",definition,null,selector,bound);
        String schedule="cron"+misfire.name()+id,revision="rev"+schedule;
        String body=context.getBean(ObjectMapper.class).writeValueAsString(release);
        jdbc.update("INSERT INTO iqc_periodic_schedule(id,code,name,draft_config_json,active_revision_id,status,next_planned_at_utc,created_by) VALUES(?,?,?,'{}',?,'ACTIVE',?,'actor-1')",schedule,schedule,"scheduled rollout",revision,io.github.opensabre.iqc.schedule.PeriodicScheduleService.utc(due));
        jdbc.update("INSERT INTO iqc_periodic_schedule_revision(id,schedule_id,version_no,source_draft_revision,snapshot_json,content_hash) VALUES(?,?,1,1,?,?)",revision,schedule,body,io.github.opensabre.iqc.selector.SelectorAssetService.hash(body));
        return context.getBean(io.github.opensabre.iqc.schedule.PeriodicScheduleService.class).peek(schedule,observed);
    }
    private io.github.opensabre.iqc.schedule.ScheduledActorAccessService.Resolved scheduleActor(io.github.opensabre.iqc.schedule.PeriodicScheduleService.Frame frame){
        return new io.github.opensabre.iqc.schedule.ScheduledActorAccessService.Resolved("actor-1","actor-1",true,null,Set.of(),io.github.opensabre.iqc.schedule.PeriodicScheduleService.grants(frame.snapshot().definition()),java.time.Instant.now());
    }
    @Test void realFireLatestUsesPlannedTickAndStaleFrameCannotMaterializeTwice() throws Exception {
        var selector=selector(conversationId);var due=java.time.Instant.parse("2026-10-23T04:00:00Z");var observed=due.plusSeconds(615);
        service.append(id,0,policy(0));var frame=scheduleFrame(selector,due,io.github.opensabre.iqc.schedule.PeriodicDefinition.MisfirePolicy.FIRE_LATEST,observed);
        service.append(id,1,policy(10000));var schedules=context.getBean(io.github.opensabre.iqc.schedule.PeriodicScheduleService.class);
        assertThat(schedules.fire(frame,observed,scheduleActor(frame))).isTrue();
        assertThat(schedules.fire(frame,observed,scheduleActor(frame))).isFalse();
        var task=jdbc.queryForMap("SELECT id,planned_at_utc,rule_snapshot_json,selection_filter_json FROM iqc_inspection_task WHERE periodic_schedule_id=?",frame.scheduleId());
        assertThat(task.get("planned_at_utc")).isEqualTo(io.github.opensabre.iqc.schedule.PeriodicScheduleService.utc(due.plusSeconds(600)));
        var json=context.getBean(ObjectMapper.class);
        assertThat(json.treeToValue(json.readTree((String)task.get("selection_filter_json")).at("/selection/anchor"),java.time.Instant.class)).isEqualTo(due.plusSeconds(600));
        assertThat(json.readTree((String)task.get("rule_snapshot_json")).at("/pipelineSnapshot/versionNo").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT next_planned_at_utc FROM iqc_periodic_schedule WHERE id=?",java.time.LocalDateTime.class,frame.scheduleId())).isEqualTo(io.github.opensabre.iqc.schedule.PeriodicScheduleService.utc(due.plusSeconds(660)));
        verify(context.getBean(io.github.opensabre.iqc.result.InspectionExecutionService.class)).queueSystem((String)task.get("id"));
    }
    @Test void enqueueFailureRollsBackBothInstanceAndCursorThenOriginalFrameRetries() throws Exception {
        var selector=selector(conversationId);var due=java.time.Instant.parse("2026-10-23T05:00:00Z");service.append(id,0,policy(0));
        var frame=scheduleFrame(selector,due,io.github.opensabre.iqc.schedule.PeriodicDefinition.MisfirePolicy.CATCH_UP,due);
        var schedules=context.getBean(io.github.opensabre.iqc.schedule.PeriodicScheduleService.class);var executions=context.getBean(io.github.opensabre.iqc.result.InspectionExecutionService.class);
        doAnswer(call->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();throw new IllegalStateException("controlled enqueue failure");}).when(executions).queueSystem(anyString());
        assertThatThrownBy(()->schedules.fire(frame,due,scheduleActor(frame))).hasMessageContaining("controlled enqueue failure");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE periodic_schedule_id=?",Integer.class,frame.scheduleId())).isZero();
        assertThat(jdbc.queryForObject("SELECT next_planned_at_utc FROM iqc_periodic_schedule WHERE id=?",java.time.LocalDateTime.class,frame.scheduleId())).isEqualTo(io.github.opensabre.iqc.schedule.PeriodicScheduleService.utc(due));
        reset(executions);assertThat(schedules.fire(frame,due,scheduleActor(frame))).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE periodic_schedule_id=?",Integer.class,frame.scheduleId())).isOne();
    }
    @Test void skipAndNoDataAdvanceCursorWithoutInvokingQueue() throws Exception {
        var selector=selector("absent"+id);var due=java.time.Instant.parse("2026-10-23T06:00:00Z");service.append(id,0,policy(0));
        var schedules=context.getBean(io.github.opensabre.iqc.schedule.PeriodicScheduleService.class);
        for(var mode:List.of(io.github.opensabre.iqc.schedule.PeriodicDefinition.MisfirePolicy.SKIP,io.github.opensabre.iqc.schedule.PeriodicDefinition.MisfirePolicy.FIRE_LATEST)){
            var now=due.plusSeconds(615);var frame=scheduleFrame(selector,due,mode,now);assertThat(schedules.fire(frame,now,scheduleActor(frame))).isTrue();
            assertThat(jdbc.queryForObject("SELECT status FROM iqc_inspection_task WHERE periodic_schedule_id=?",String.class,frame.scheduleId())).isEqualTo(mode==io.github.opensabre.iqc.schedule.PeriodicDefinition.MisfirePolicy.SKIP?"SKIPPED_MISFIRE":"NO_DATA");
            assertThat(jdbc.queryForObject("SELECT next_planned_at_utc FROM iqc_periodic_schedule WHERE id=?",java.time.LocalDateTime.class,frame.scheduleId())).isEqualTo(io.github.opensabre.iqc.schedule.PeriodicScheduleService.utc(due.plusSeconds(660)));
        }
        verifyNoInteractions(context.getBean(io.github.opensabre.iqc.result.InspectionExecutionService.class));
    }
    @Test void competingFireCallsAdvanceOneCursorAndMaterializeOneInstance() throws Exception {
        var selector=selector(conversationId);var due=java.time.Instant.parse("2026-10-23T07:00:00Z");service.append(id,0,policy(5000));
        var frame=scheduleFrame(selector,due,io.github.opensabre.iqc.schedule.PeriodicDefinition.MisfirePolicy.CATCH_UP,due);
        var schedules=context.getBean(io.github.opensabre.iqc.schedule.PeriodicScheduleService.class);
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)){
            Callable<Boolean> fire=()->{ready.countDown();if(!go.await(10,TimeUnit.SECONDS))throw new IllegalStateException("fire barrier");return schedules.fire(frame,due,scheduleActor(frame));};
            var first=workers.submit(fire);var second=workers.submit(fire);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();
            assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        } finally {go.countDown();}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE periodic_schedule_id=?",Integer.class,frame.scheduleId())).isOne();
        assertThat(jdbc.queryForObject("SELECT next_planned_at_utc FROM iqc_periodic_schedule WHERE id=?",java.time.LocalDateTime.class,frame.scheduleId())).isEqualTo(io.github.opensabre.iqc.schedule.PeriodicScheduleService.utc(due.plusSeconds(60)));
        verify(context.getBean(io.github.opensabre.iqc.result.InspectionExecutionService.class),times(1)).queueSystem(anyString());
    }
    @Test void missingRolloutGrantOrExpiredAuthorizationCannotAdvanceCursor() throws Exception {
        var selector=selector(conversationId);var due=java.time.Instant.parse("2026-10-23T08:00:00Z");service.append(id,0,policy(0));
        var frame=scheduleFrame(selector,due,io.github.opensabre.iqc.schedule.PeriodicDefinition.MisfirePolicy.CATCH_UP,due);
        var schedules=context.getBean(io.github.opensabre.iqc.schedule.PeriodicScheduleService.class);var valid=scheduleActor(frame);
        var grants=new HashSet<>(valid.resourceCodes());grants.remove("iqc:pipeline:rollout:task:create");
        var missing=new io.github.opensabre.iqc.schedule.ScheduledActorAccessService.Resolved(valid.userId(),valid.owner(),valid.all(),valid.groupId(),valid.roles(),Set.copyOf(grants),valid.checkedAt());
        var expired=new io.github.opensabre.iqc.schedule.ScheduledActorAccessService.Resolved(valid.userId(),valid.owner(),valid.all(),valid.groupId(),valid.roles(),valid.resourceCodes(),java.time.Instant.now().minusSeconds(31));
        for(var denied:List.of(missing,expired))assertThatThrownBy(()->schedules.fire(frame,due,denied)).hasMessageContaining("授权");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE periodic_schedule_id=?",Integer.class,frame.scheduleId())).isZero();
        assertThat(jdbc.queryForObject("SELECT next_planned_at_utc FROM iqc_periodic_schedule WHERE id=?",java.time.LocalDateTime.class,frame.scheduleId())).isEqualTo(io.github.opensabre.iqc.schedule.PeriodicScheduleService.utc(due));
        verifyNoInteractions(context.getBean(io.github.opensabre.iqc.result.InspectionExecutionService.class));
    }
    /** Real queue SQL and original periodic guard; non-periodic dependency adapters remain controlled. */
    private io.github.opensabre.iqc.result.InspectionExecutionService realQueue() {
        var session=new SqlSessionTemplate(context.getBean(SqlSessionFactory.class));var assets=mock(PipelineAssetService.class);
        doAnswer(call->{context.getBean(PipelineRolloutExecutionGuard.class).validate(call.getArgument(0),call.getArgument(1));return null;})
                .when(assets).validateTask(any(com.fasterxml.jackson.databind.JsonNode.class),anyString());
        var runner=new io.github.opensabre.iqc.result.InspectionExecutionService(session.getMapper(io.github.opensabre.iqc.task.dao.InspectionTaskMapper.class),
                session.getMapper(io.github.opensabre.iqc.conversation.dao.ConversationMapper.class),session.getMapper(io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper.class),
                null,context.getBean(ObjectMapper.class),session.getMapper(io.github.opensabre.iqc.task.dao.TaskExecutionMapper.class),session.getMapper(io.github.opensabre.iqc.task.dao.TaskItemMapper.class),
                context.getBean(IqcDataScope.class),mock(io.github.opensabre.iqc.result.llm.LlmQualityProvider.class),null,null,null,
                mock(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.class),assets,mock(io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator.class));
        org.springframework.test.util.ReflectionTestUtils.setField(runner,"productionInputSnapshots",context.getBean(io.github.opensabre.iqc.conversation.ProductionInputSnapshotService.class));return runner;
    }
    private boolean fireWithOwner(io.github.opensabre.iqc.schedule.PeriodicScheduleService.Frame frame,java.time.Instant due){
        var holder=io.github.opensabre.common.core.util.UserContextHolder.getInstance();holder.setContext(Map.of("user_name","actor-1","user_id","actor-1"));
        try{return context.getBean(io.github.opensabre.iqc.schedule.PeriodicScheduleService.class).fire(frame,due,scheduleActor(frame));}
        finally{holder.clear();}
    }
    @Test void fireCreatesRealQueuedExecutionAndFrozenMessageCheckpoint() throws Exception {
        var selector=selector(conversationId);var due=java.time.Instant.parse("2026-10-23T09:00:00Z");storePublishedSources();service.append(id,0,policy(0));
        var frame=scheduleFrame(selector,due,io.github.opensabre.iqc.schedule.PeriodicDefinition.MisfirePolicy.CATCH_UP,due);
        var runner=realQueue();doAnswer(call->runner.queueSystem(call.getArgument(0))).when(context.getBean(io.github.opensabre.iqc.result.InspectionExecutionService.class)).queueSystem(anyString());
        assertThat(fireWithOwner(frame,due)).isTrue();
        var row=jdbc.queryForMap("SELECT id,status,current_execution_id,attempt_count,created_by FROM iqc_inspection_task WHERE periodic_schedule_id=?",frame.scheduleId());
        assertThat(row.get("status")).isEqualTo("QUEUED");assertThat(row.get("attempt_count")).isEqualTo(1);assertThat(row.get("created_by")).isEqualTo("actor-1");
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_task_execution WHERE id=?",String.class,row.get("current_execution_id"))).isEqualTo("QUEUED");
        var item=jdbc.queryForMap("SELECT message_id,status FROM iqc_task_item WHERE execution_id=?",row.get("current_execution_id"));
        assertThat(item.get("message_id")).isEqualTo("m"+conversationId);assertThat(item.get("status")).isEqualTo("PENDING");
        assertThat(fireWithOwner(frame,due)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_task_execution WHERE task_id=?",Integer.class,row.get("id"))).isOne();
        var tasks=new SqlSessionTemplate(context.getBean(SqlSessionFactory.class)).getMapper(io.github.opensabre.iqc.task.dao.InspectionTaskMapper.class);
        var task=tasks.selectById((String)row.get("id"));validateStoredTask(task);
        jdbc.update("UPDATE iqc_inspection_task SET periodic_revision_id='wrong-source' WHERE id=?",task.getId());
        assertThatThrownBy(()->validateStoredTask(task)).hasMessageContaining("原修订、发布来源或比较证据不一致");
        runRejectedTaskThroughRealWorker(task);
    }
    @Test void realCheckpointWriteFailureRollsBackTaskExecutionAndCursor() throws Exception {
        var selector=selector(conversationId);var due=java.time.Instant.parse("2026-10-23T10:00:00Z");storePublishedSources();service.append(id,0,policy(0));
        var frame=scheduleFrame(selector,due,io.github.opensabre.iqc.schedule.PeriodicDefinition.MisfirePolicy.CATCH_UP,due);
        var runner=realQueue();doAnswer(call->runner.queueSystem(call.getArgument(0))).when(context.getBean(io.github.opensabre.iqc.result.InspectionExecutionService.class)).queueSystem(anyString());
        long executions=jdbc.queryForObject("SELECT COUNT(*) FROM iqc_task_execution",Long.class);
        jdbc.execute("CREATE TRIGGER reject_periodic_checkpoint BEFORE INSERT ON iqc_task_item FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated checkpoint rejection'");
        try{assertThatThrownBy(()->fireWithOwner(frame,due)).hasStackTraceContaining("isolated checkpoint rejection");}
        finally{jdbc.execute("DROP TRIGGER reject_periodic_checkpoint");}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE periodic_schedule_id=?",Integer.class,frame.scheduleId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_task_execution",Long.class)).isEqualTo(executions);
        assertThat(jdbc.queryForObject("SELECT next_planned_at_utc FROM iqc_periodic_schedule WHERE id=?",java.time.LocalDateTime.class,frame.scheduleId())).isEqualTo(io.github.opensabre.iqc.schedule.PeriodicScheduleService.utc(due));
        assertThat(fireWithOwner(frame,due)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_inspection_task WHERE periodic_schedule_id=?",String.class,frame.scheduleId())).isEqualTo("QUEUED");
    }
    /** Real task/revision provenance; result reads are empty controlled adapters, not worker outcomes. */
    private io.github.opensabre.iqc.quality.BusinessQualityReportService rolloutReport(
            io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper results) {
        var session=new SqlSessionTemplate(context.getBean(SqlSessionFactory.class));var json=context.getBean(ObjectMapper.class);
        var reviews=new io.github.opensabre.iqc.quality.BusinessItemReviewService(null,null,null,null,null,json);
        return new io.github.opensabre.iqc.quality.BusinessQualityReportService(
                session.getMapper(io.github.opensabre.iqc.task.dao.InspectionTaskMapper.class),results,reviews,
                context.getBean(IqcDataScope.class),json,session.getMapper(io.github.opensabre.iqc.task.dao.TaskExecutionMapper.class),
                context.getBean(PipelineRolloutExecutionGuard.class));
    }
    @Test void businessReportKeepsHistoricalRevisionsAndMissingResultDenominatorsAfterRollback() throws Exception {
        storePublishedSources();service.append(id,0,policy(0));
        var first=createTask("report-first");var duplicateStandard=createTask("report-second");
        service.append(id,1,policy(10000));var candidate=createTask("report-candidate");
        service.append(id,2,policy(0));var rollback=createTask("report-rollback");
        var results=mock(io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper.class);
        var reports=rolloutReport(results);var ids=List.of(first.getId(),duplicateStandard.getId(),candidate.getId(),rollback.getId());
        for(var runPolicy:io.github.opensabre.iqc.quality.BusinessQualityReportService.RunPolicy.values()){
            var report=reports.report(ids,null,runPolicy.name());
            assertThat(report.groups()).hasSize(3);
            assertThat(report.conversationCount()).isEqualTo(runPolicy==io.github.opensabre.iqc.quality.BusinessQualityReportService.RunPolicy.SELECTED_RUNS?4:3);
            assertThat(report.groups()).allSatisfy(group->{
                assertThat(group.missingResultCount()).isEqualTo(group.conversationCount());
                assertThat(group.machine().resultCount()).isZero();assertThat(group.machine().averageScore()).isNull();
                assertThat(group.reviewed().resultCount()).isZero();assertThat(group.rollout()).isNotNull();
                assertThat(group.rollout().selectedVersionNo()).isEqualTo(group.rollout().revisionNo()==2?2:1);
                assertThat(group.rollout().side()).isEqualTo(group.rollout().revisionNo()==2?"CANDIDATE":"BASELINE");
            });
            assertThat(report.groups().stream().map(group->group.rollout().revisionNo()).toList()).containsExactlyInAnyOrder(1,2,3);
            assertThat(context.getBean(ObjectMapper.class).writeValueAsString(report))
                    .doesNotContain("salt_0001","cohortHash","requestFingerprint","requestKey","actor-1");
        }
    }
    @Test void businessReportRejectsMissingRolloutOrCorruptOriginalSourceBeforeResultReads() throws Exception {
        storePublishedSources();service.append(id,0,policy(0));var task=createTask("report-corrupt");
        var results=mock(io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper.class);var reports=rolloutReport(results);
        var json=context.getBean(ObjectMapper.class);var broken=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(task.getRuleSnapshotJson());
        broken.remove("rolloutSnapshot");jdbc.update("UPDATE iqc_inspection_task SET rule_snapshot_json=? WHERE id=?",broken.toString(),task.getId());
        assertThatThrownBy(()->reports.report(List.of(task.getId()))).hasMessageContaining("原修订、发布来源或比较证据不一致");
        jdbc.update("UPDATE iqc_inspection_task SET rule_snapshot_json=? WHERE id=?",task.getRuleSnapshotJson(),task.getId());
        jdbc.update("UPDATE iqc_pipeline_asset_version SET snapshot_json='{}' WHERE pipeline_id=? AND version_no=2",id);
        assertThatThrownBy(()->reports.report(List.of(task.getId()))).hasMessageContaining("原修订、发布来源或比较证据不一致");
        verifyNoInteractions(results);
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
        int priorAttempts=jdbc.queryForObject("SELECT COALESCE(MAX(attempt_no),0) FROM iqc_task_execution WHERE task_id=?",Integer.class,task.getId());
        jdbc.update("INSERT INTO iqc_task_execution(id,task_id,attempt_no,status) VALUES(?,?,?,'SUCCEEDED'),(?,?,?,'QUEUED')",
                old,task.getId(),priorAttempts+1,eid,task.getId(),priorAttempts+2);
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
            // Register audit filling before mapper parsing captures insert statements and entity metadata.
            var global=com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.defaults().setMetaObjectHandler(new io.github.opensabre.persistence.handler.PoMetaObjectHandler());
            com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config,global);
            config.addMapper(io.github.opensabre.iqc.selector.dao.SelectorAssetMapper.class);config.addMapper(io.github.opensabre.iqc.selector.dao.SelectorAssetVersionMapper.class);
            config.addMapper(io.github.opensabre.iqc.schedule.dao.PeriodicScheduleMapper.class);config.addMapper(io.github.opensabre.iqc.schedule.dao.PeriodicScheduleRevisionMapper.class);
            config.addMapper(PipelineAssetMapper.class);config.addMapper(PipelineAssetVersionMapper.class);config.addMapper(PipelineRolloutRevisionMapper.class);
            config.addMapper(io.github.opensabre.iqc.task.dao.InspectionTaskMapper.class);
            config.addMapper(io.github.opensabre.iqc.task.dao.TaskExecutionMapper.class);config.addMapper(io.github.opensabre.iqc.task.dao.TaskItemMapper.class);
            config.addMapper(io.github.opensabre.iqc.conversation.dao.ConversationMapper.class);
            config.addMapper(io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper.class);
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);factory.setGlobalConfig(global);return factory.getObject();
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
            return new PipelineRolloutExecutionGuard(session.getMapper(PipelineRolloutRevisionMapper.class),session.getMapper(PipelineAssetVersionMapper.class),json,session.getMapper(io.github.opensabre.iqc.task.dao.InspectionTaskMapper.class),session.getMapper(io.github.opensabre.iqc.schedule.dao.PeriodicScheduleRevisionMapper.class));
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
        @Bean io.github.opensabre.iqc.selector.ConversationSelectionService selections(SqlSessionFactory factory,IqcDataScope scope,ObjectMapper json){
            return new io.github.opensabre.iqc.selector.ConversationSelectionService(new SqlSessionTemplate(factory).getMapper(io.github.opensabre.iqc.conversation.dao.ConversationMapper.class),null,null,scope,json);
        }
        @Bean io.github.opensabre.iqc.selector.SelectorAssetService selectors(SqlSessionFactory factory,io.github.opensabre.iqc.selector.ConversationSelectionService selections,IqcDataScope scope,ObjectMapper json){
            var session=new SqlSessionTemplate(factory);
            return new io.github.opensabre.iqc.selector.SelectorAssetService(session.getMapper(io.github.opensabre.iqc.selector.dao.SelectorAssetMapper.class),session.getMapper(io.github.opensabre.iqc.selector.dao.SelectorAssetVersionMapper.class),selections,json,scope);
        }
        @Bean PipelineRolloutTaskService rolloutTasks(PipelineAssetService pipelines,PipelineRolloutService rollouts,
                io.github.opensabre.iqc.task.InspectionTaskService tasks,ObjectMapper json,IqcDataScope scope,io.github.opensabre.iqc.selector.SelectorAssetService selectors,io.github.opensabre.iqc.selector.ConversationSelectionService selections) {
            return new PipelineRolloutTaskService(pipelines,rollouts,tasks,json,scope,selectors,selections);
        }
        @Bean io.github.opensabre.iqc.result.InspectionExecutionService scheduledExecutions(){return mock(io.github.opensabre.iqc.result.InspectionExecutionService.class);}
        @Bean io.github.opensabre.iqc.schedule.PeriodicScheduleService scheduledService(SqlSessionFactory factory,PipelineAssetService pipelines,
                io.github.opensabre.iqc.selector.SelectorAssetService selectors,io.github.opensabre.iqc.selector.ConversationSelectionService selections,
                io.github.opensabre.iqc.task.InspectionTaskService tasks,io.github.opensabre.iqc.result.InspectionExecutionService executions,
                IqcDataScope scope,ObjectMapper json,PipelineRolloutTaskService rollouts){
            var session=new SqlSessionTemplate(factory);
            return new io.github.opensabre.iqc.schedule.PeriodicScheduleService(session.getMapper(io.github.opensabre.iqc.schedule.dao.PeriodicScheduleMapper.class),
                    session.getMapper(io.github.opensabre.iqc.schedule.dao.PeriodicScheduleRevisionMapper.class),pipelines,selectors,null,selections,tasks,
                    session.getMapper(io.github.opensabre.iqc.task.dao.InspectionTaskMapper.class),executions,scope,json,null,rollouts);
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
