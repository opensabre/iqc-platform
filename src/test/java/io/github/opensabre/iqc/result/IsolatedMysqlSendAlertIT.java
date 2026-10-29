package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.common.core.entity.vo.Result;
import io.github.opensabre.iqc.action.*;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real action Mapper/Flyway/InnoDB/Spring proxies; source facts and remote notification are substitutes. */
class IsolatedMysqlSendAlertIT {
    private static final String SERVER="jdbc:mysql://127.0.0.1:34307/";
    private static final String OPTIONS="?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
    private static final String SCHEMA="iqc_alert_it_"+UUID.randomUUID().toString().replace("-", "");
    private static final String URL=SERVER+SCHEMA+OPTIONS;
    private static AnnotationConfigApplicationContext context;
    private static boolean created;
    private ActionExecutionMapper rows;
    private IqcNotificationClient notifications;
    private IqcDataScope scope;
    private ActionProductionSourceValidator sources;
    private ActionAdapter.Request request;
    private ScheduledActorAccessService.Resolved actor;
    private final AtomicInteger sourceCommits=new AtomicInteger();

    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement
    static class AlertConfiguration {
        @Bean DataSource dataSource(){return new DriverManagerDataSource(URL,"root","");}
        @Bean DataSourceTransactionManager transactionManager(DataSource dataSource){return new DataSourceTransactionManager(dataSource);}
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.addMapper(ActionExecutionMapper.class);
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);return factory.getObject();
        }
        @Bean ActionExecutionMapper rows(SqlSessionFactory factory){return new SqlSessionTemplate(factory).getMapper(ActionExecutionMapper.class);}
        @Bean ObjectMapper json(){return new ObjectMapper();}
        @Bean ActionProductionSourceValidator sources(){return mock(ActionProductionSourceValidator.class);}
        @Bean IqcDataScope scope(){return mock(IqcDataScope.class);}
        @Bean IqcNotificationClient notifications(){return mock(IqcNotificationClient.class);}
        @Bean SendAlertPreparationService preparation(ActionProductionSourceValidator sources,ActionExecutionMapper rows,IqcDataScope scope,ObjectMapper json){return new SendAlertPreparationService(sources,rows,scope,json);}
        @Bean SendAlertActionAdapter adapter(SendAlertPreparationService preparation,IqcNotificationClient client,IqcDataScope scope){return new SendAlertActionAdapter(preparation,client,scope);}
        @Bean ActionAdapterRegistry registry(ActionAdapter adapter){return new ActionAdapterRegistry(List.of(adapter));}
        @Bean ActionClaimService claims(ActionExecutionMapper rows,ActionProductionSourceValidator sources,ActionAdapterRegistry registry,IqcDataScope scope,ObjectMapper json){return new ActionClaimService(rows,sources,registry,scope,json);}
        @Bean ActionSettlementService settlements(ActionExecutionMapper rows,ActionAdapterRegistry registry,ObjectMapper json){return new ActionSettlementService(rows,registry,json);}
        @Bean ActionExecutionService runner(ActionClaimService claims,ActionSettlementService settlements,ActionAdapterRegistry registry,ActionExecutionMapper rows,IqcDataScope scope,ObjectMapper json){return new ActionExecutionService(claims,settlements,registry,rows,scope,json);}
    }

    @BeforeAll static void start() throws Exception {
        IsolatedMysqlMigrationIT.verifyIsolatedServer();
        try(var connection=DriverManager.getConnection(SERVER+OPTIONS,"root","");var statement=connection.createStatement()){
            statement.execute("CREATE DATABASE `"+SCHEMA+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");created=true;
        }
        Flyway.configure().dataSource(URL,"root","").locations("classpath:db/migration/mysql").cleanDisabled(true).load().migrate();
        context=new AnnotationConfigApplicationContext(AlertConfiguration.class);
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(SendAlertPreparationService.class)));
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(ActionAdapter.class)));
    }
    @AfterAll static void close() throws Exception {
        if(context!=null)context.close();
        if(created){IsolatedMysqlMigrationIT.verifyIsolatedServer();try(var connection=DriverManager.getConnection(SERVER+OPTIONS,"root","");var statement=connection.createStatement()){statement.execute("DROP DATABASE `"+SCHEMA+"`");}}
    }
    @BeforeEach void fixture() throws Exception {
        var jdbc=new JdbcTemplate(context.getBean(DataSource.class));jdbc.update("DELETE FROM iqc_action_execution_event");jdbc.update("DELETE FROM iqc_action_execution");
        rows=context.getBean(ActionExecutionMapper.class);notifications=context.getBean(IqcNotificationClient.class);scope=context.getBean(IqcDataScope.class);
        sources=(ActionProductionSourceValidator)((org.springframework.aop.framework.Advised)context.getBean(ActionProductionSourceValidator.class)).getTargetSource().getTarget();
        reset(sources,notifications,scope);sourceCommits.set(0);
        var json=new ObjectMapper();
        var action=new ActionPolicy.Action("alert",ActionExecutionContract.Type.SEND_ALERT,new ActionPolicy.Condition(ActionPolicy.Kind.ALWAYS,null,null,List.of()),
                ActionPolicy.Idempotency.PRODUCTION_SOURCE_V1,null,ActionPolicy.FailureHandling.RECORD_ONLY,
                json.readTree("{\"sceneCode\":\"IQC_ALERT\",\"channel\":\"EMAIL\",\"target\":\"test@example.invalid\"}"));
        var policy=new ActionPolicy(ActionPolicy.SCHEMA,List.of(action));String body=json.writeValueAsString(policy);
        var source=new ActionExecutionContract.Source("task","execution","conversation","result","result",SelectorAssetService.hash(body),"alert","alice");
        String key=source.idempotencyKey(ActionExecutionContract.Type.SEND_ALERT);
        request=new ActionAdapter.Request(key,key,source,SendAlertPreparationService.DEFINITION,1,"{}",SelectorAssetService.hash("{}"),body);
        var reference=new ActionProductionSourceValidator.Reference("task","execution","conversation","result","alice");
        var bound=new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1","user","alice",false,"group");
        var facts=new ActionPolicy.Context(Map.of(),Map.of(),InspectionScoring.ScoreStatus.FINAL,null,"UNKNOWN",null);
        var validated=new ActionProductionSourceValidator.Validated(reference,"group","result","{}",request.inputSnapshotHash(),policy,facts,bound,"SUCCEEDED");
        when(sources.validate(reference)).thenAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){sourceCommits.incrementAndGet();}});
            return validated;
        });
        when(sources.validateHistorical(reference)).thenReturn(validated);
        when(scope.owner()).thenReturn("alice");when(scope.groupId()).thenReturn("group");
        var grants=new HashSet<>(ActionEnqueueService.REQUIRED_GRANTS);grants.addAll(SendAlertPreparationService.RESOURCES);
        actor=new ScheduledActorAccessService.Resolved("user","alice",false,"group",Set.of(),grants,Instant.now());
        var row=new ActionExecution();row.setId(key);row.setIdempotencyKey(key);row.setTaskId("task");row.setExecutionId("execution");row.setConversationId("conversation");
        row.setSourceResultId("result");row.setDecisionReference("result");row.setActionId("alert");row.setActionPolicyHash(source.actionPolicyHash());row.setActionType("SEND_ALERT");
        row.setOwner("alice");row.setOwnerGroupId("group");row.setInputSnapshotJson("{}");row.setInputSnapshotHash(request.inputSnapshotHash());row.setPolicySnapshotJson(body);
        row.setStatus("PENDING");row.setAttemptCount(0);row.setVersion(0L);assertEquals(1,rows.insert(row));
    }
    private Result<IqcNotificationClient.Status> success(){
        var declaration=new SendAlertConfiguration("IQC_ALERT","EMAIL","test@example.invalid",Map.of());
        return Result.success(new IqcNotificationClient.Status("iqc-notification-status-v1",request.idempotencyKey(),"b".repeat(32),"c".repeat(64),declaration.declaredHash(),"SUCCESS","receipt"));
    }
    @Test void actualClaimAndPreparationCommitBeforeSendThenDurableSettlement() {
        when(notifications.send(any(),any())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(2,sourceCommits.get());
            assertEquals("RUNNING",rows.selectById(request.executionId()).getStatus());
            // A different connection can lock the claimed row now: preparation retained no source/action lock.
            try(var connection=DriverManager.getConnection(URL,"root","");var statement=connection.createStatement()){
                connection.setAutoCommit(false);statement.execute("SET innodb_lock_wait_timeout=1");
                statement.executeQuery("SELECT id FROM iqc_action_execution WHERE id='"+request.executionId()+"' FOR UPDATE").close();connection.rollback();
            }
            return success();
        });
        assertEquals(ActionExecutionService.Dispatch.SETTLED,context.getBean(ActionExecutionService.class).execute(request.executionId(),0,actor));
        var row=rows.selectById(request.executionId());assertEquals("COMPLETE",row.getStatus());assertEquals(2L,row.getVersion());assertEquals(1,row.getAttemptCount());
        assertEquals("b".repeat(32),row.getEvidenceReference());verify(notifications,times(1)).send(any(),any());
        assertEquals(ActionExecutionService.Dispatch.NOT_CLAIMED,context.getBean(ActionExecutionService.class).execute(request.executionId(),2,actor));
        verifyNoMoreInteractions(notifications);
    }
    @Test void realTransactionFenceRejectsAdapterBeforeAnyRemoteCall() {
        var tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->tx.execute(status->context.getBean(ActionAdapter.class).execute(request)));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->tx.execute(status->context.getBean(ActionAdapter.class).reconcile(request)));
        verifyNoInteractions(notifications);
    }
    @Test void lostSendSettlesUnknownAndHistoricalLookupNeverSendsAgain() {
        when(notifications.send(any(),any())).thenThrow(new IllegalStateException("response lost"));
        assertEquals(ActionExecutionService.Dispatch.SETTLED,context.getBean(ActionExecutionService.class).execute(request.executionId(),0,actor));
        var row=rows.selectById(request.executionId());assertEquals("REQUIRES_RECONCILIATION",row.getStatus());assertEquals("UNKNOWN",row.getOutcome());assertNull(row.getNextAttemptAtUtc());
        clearInvocations(scope,sources);
        when(notifications.lookup(request.idempotencyKey())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return success();});
        assertEquals(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,context.getBean(ActionAdapter.class).reconcile(request).outcome());
        verify(notifications,times(1)).send(any(),any());verify(notifications,times(1)).lookup(request.idempotencyKey());
        verify(scope,never()).requireScheduledResources(any());verify(sources,never()).validate(any());
        assertEquals("REQUIRES_RECONCILIATION",rows.selectById(request.executionId()).getStatus()); // Receipt alone is not ledger settlement.
    }
}
