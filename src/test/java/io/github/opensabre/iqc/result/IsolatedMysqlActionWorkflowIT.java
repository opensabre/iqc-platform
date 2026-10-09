package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.action.*;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IsolatedMysqlActionWorkflowIT {
    private static AnnotationConfigApplicationContext context;
    private static ActionEnqueueService enqueue;
    private static ActionClaimService claims;
    private static ActionExecutionService execute;
    private static ActionExecutionMapper rows;
    private static ActionAdapter adapter;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private final ActionProductionSourceValidator.Reference reference=new ActionProductionSourceValidator.Reference("task","execution","conversation","result","alice");
    @Configuration(proxyBeanMethods=false)
    @Import(IsolatedMysqlActionSettlementIT.SettlementConfiguration.class)
    static class WorkflowConfiguration {
        @Bean ActionProductionSourceValidator sources() {return mock(ActionProductionSourceValidator.class);}
        @Bean IqcDataScope scope() {return mock(IqcDataScope.class);}
        @Bean ActionEnqueueService enqueue(ActionProductionSourceValidator source,ActionExecutionMapper rows,ActionAdapterRegistry registry,IqcDataScope scope,ObjectMapper json) {
            return new ActionEnqueueService(source,rows,registry,scope,json);
        }
        @Bean ActionClaimService claims(ActionExecutionMapper rows,ActionProductionSourceValidator source,ActionAdapterRegistry registry,IqcDataScope scope,ObjectMapper json) {
            return new ActionClaimService(rows,source,registry,scope,json);
        }
        @Bean ActionExecutionService execute(ActionClaimService claims,ActionSettlementService settlement,ActionAdapterRegistry registry,ActionExecutionMapper rows,IqcDataScope scope,ObjectMapper json) {
            return new ActionExecutionService(claims,settlement,registry,rows,scope,json);
        }
    }
    @BeforeAll static void start() throws Exception {
        IsolatedMysqlTaskExecutionStateIT.start();context=new AnnotationConfigApplicationContext(WorkflowConfiguration.class);
        enqueue=context.getBean(ActionEnqueueService.class);claims=context.getBean(ActionClaimService.class);execute=context.getBean(ActionExecutionService.class);
        rows=context.getBean(ActionExecutionMapper.class);jdbc=new JdbcTemplate(context.getBean(DataSource.class));
        tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(enqueue));assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(claims));
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(execute));
    }
    @AfterAll static void close() {if(context!=null)context.close();IsolatedMysqlTaskExecutionStateIT.close();}
    @BeforeEach void fixture() throws Exception {
        jdbc.update("DELETE FROM iqc_action_execution");
        var json=new ObjectMapper();
        var action=new ActionPolicy.Action("save",ActionExecutionContract.Type.SAVE_RESULT,
                new ActionPolicy.Condition(ActionPolicy.Kind.ALWAYS,null,null,List.of()),ActionPolicy.Idempotency.PRODUCTION_SOURCE_V1,
                null,ActionPolicy.FailureHandling.RECORD_ONLY,json.createObjectNode());
        var policy=new ActionPolicy(ActionPolicy.SCHEMA,List.of(action));
        var bound=new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1","user","alice",false,"group");
        var decision=new ActionPolicy.Context(Map.of(),Map.of(),InspectionScoring.ScoreStatus.FINAL,null,"UNKNOWN",null);
        var sources=(ActionProductionSourceValidator)((org.springframework.aop.framework.Advised)context.getBean(ActionProductionSourceValidator.class)).getTargetSource().getTarget();reset(sources);
        when(sources.validate(reference)).thenReturn(new ActionProductionSourceValidator.Validated(reference,"group","result","{}",SelectorAssetService.hash("{}"),policy,decision,bound));
        var scope=context.getBean(IqcDataScope.class);when(scope.owner()).thenReturn("alice");when(scope.groupId()).thenReturn("group");
        adapter=context.getBean(ActionAdapterRegistry.class).require(action).adapter();clearInvocations(adapter);
        doAnswer(call->{
            ActionAdapter.Request request=call.getArgument(0);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            // A separate JDBC connection observes the committed claim before any adapter effect.
            assertEquals("RUNNING",jdbc.queryForObject("SELECT status FROM iqc_action_execution WHERE id=?",String.class,request.executionId()));
            return new ActionAdapter.Receipt(request.idempotencyKey(),ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,"receipt","result",null);
        }).when(adapter).execute(any());
    }
    private ScheduledActorAccessService.Resolved actor() {return new ScheduledActorAccessService.Resolved("user","alice",false,"group",Set.of(),ActionEnqueueService.REQUIRED_GRANTS,Instant.now());}
    private String queue() {return enqueue.enqueue(reference,actor()).getFirst();}
    @Test void committedClaimPrecedesTransactionFreeAdapterAndDurableSettlement() {
        String id=queue();assertEquals(ActionExecutionService.Dispatch.SETTLED,execute.execute(id,0,actor()));
        assertEquals("COMPLETE",rows.selectById(id).getStatus());assertEquals(1,rows.selectById(id).getAttemptCount());assertEquals(2L,rows.selectById(id).getVersion());
        verify(adapter,times(1)).execute(any());
    }
    @Test void analysisTransactionCannotEnterSendWorkflow() {
        String id=queue();
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->tx.execute(status->execute.execute(id,0,actor())));
        assertEquals("PENDING",rows.selectById(id).getStatus());verify(adapter,never()).execute(any());
    }
    @Test void competingRealTransactionsGrantOnlyOneClaim() throws Exception {
        String id=queue();var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<ActionClaimService.Claim> claim=()->{ready.countDown();assertTrue(go.await(10,TimeUnit.SECONDS));return claims.claim(id,0,actor());};
            var first=pool.submit(claim);var second=pool.submit(claim);assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();
            var a=first.get(15,TimeUnit.SECONDS);var b=second.get(15,TimeUnit.SECONDS);assertTrue((a==null)!=(b==null));
            assertEquals(1,rows.selectById(id).getAttemptCount());assertEquals(1L,rows.selectById(id).getVersion());verify(adapter,never()).execute(any());
        } finally {go.countDown();}
    }
    @Test void repeatedQueueKeepsCompletedLedgerAndCannotResend() {
        String id=queue();assertEquals(ActionExecutionService.Dispatch.SETTLED,execute.execute(id,0,actor()));
        assertEquals(List.of(id),enqueue.enqueue(reference,actor()));
        assertEquals(ActionExecutionService.Dispatch.NOT_CLAIMED,execute.execute(id,2,actor()));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM iqc_action_execution",Long.class));verify(adapter,times(1)).execute(any());
    }
    @Test void independentEnqueueSurvivesOuterRollback() {
        var id=new java.util.concurrent.atomic.AtomicReference<String>();
        tx.executeWithoutResult(status->{id.set(queue());status.setRollbackOnly();});
        assertEquals("PENDING",rows.selectById(id.get()).getStatus());assertEquals(0,rows.selectById(id.get()).getAttemptCount());
    }
}
