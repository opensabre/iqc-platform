package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.action.*;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real recovery proxy/ledger/reason transactions; production-source and adapter I/O are isolated doubles. */
class IsolatedMysqlActionRecoveryIT {
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static ActionExecutionMapper rows;
    private static ActionStateEventMapper realEvents,events;
    private static ActionRecoveryService recovery;
    private static TransactionTemplate tx;
    private static final ObjectMapper json=new ObjectMapper();
    private static final ActionProductionSourceValidator sources=mock(ActionProductionSourceValidator.class);
    private static final ActionAdapterRegistry adapters=mock(ActionAdapterRegistry.class);
    private static final IqcDataScope scope=mock(IqcDataScope.class);
    private static final ActionAdapter adapter=mock(ActionAdapter.class);
    private static final ActionProductionSourceValidator.Reference reference=new ActionProductionSourceValidator.Reference("task","execution","conversation","result","alice");
    private String id;
    private ScheduledActorAccessService.Resolved actor(){
        var grants=new java.util.HashSet<>(ActionEnqueueService.REQUIRED_GRANTS);grants.add(ActionRecoveryService.RESOURCE);
        return new ScheduledActorAccessService.Resolved("user","alice",false,"group",Set.of(),grants,Instant.now());
    }
    @BeforeAll static void start() throws Exception {
        IsolatedMysqlTaskExecutionStateIT.start();
        context=new AnnotationConfigApplicationContext();context.register(IsolatedMysqlTaskExecutionStateIT.DatabaseConfiguration.class);
        context.registerBean(ActionRecoveryService.class,()->{
            var session=new SqlSessionTemplate(context.getBean(SqlSessionFactory.class));
            rows=session.getMapper(ActionExecutionMapper.class);realEvents=session.getMapper(ActionStateEventMapper.class);
            events=mock(ActionStateEventMapper.class,org.mockito.AdditionalAnswers.delegatesTo(realEvents));
            return new ActionRecoveryService(rows,sources,adapters,events,scope,json);
        });
        context.refresh();recovery=context.getBean(ActionRecoveryService.class);
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(recovery)).isTrue();
        jdbc=new JdbcTemplate(context.getBean(DataSource.class));tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
    }
    @AfterAll static void close(){if(context!=null)context.close();IsolatedMysqlTaskExecutionStateIT.close();}
    @BeforeEach void fixture(){
        reset(sources,adapters,scope,adapter,events);
        when(events.recordRecoveryReason(anyString(),anyLong(),anyLong(),anyString(),anyString(),anyString()))
            .thenAnswer(call->realEvents.recordRecoveryReason(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4),call.getArgument(5)));
        jdbc.update("DELETE FROM iqc_action_reconciliation_attempt");jdbc.update("DELETE FROM iqc_action_execution_event");jdbc.update("DELETE FROM iqc_action_execution");
        when(scope.owner()).thenReturn("alice");when(scope.groupId()).thenReturn("group");
        var action=new ActionPolicy.Action("save",ActionExecutionContract.Type.SAVE_RESULT,
            new ActionPolicy.Condition(ActionPolicy.Kind.ALWAYS,null,null,List.of()),ActionPolicy.Idempotency.PRODUCTION_SOURCE_V1,null,
            ActionPolicy.FailureHandling.RECORD_ONLY,json.createObjectNode());
        var policy=new ActionPolicy(ActionPolicy.SCHEMA,List.of(action));
        var bound=new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1","user","alice",false,"group");
        var value=new ActionPolicy.Context(Map.of(),Map.of(),InspectionScoring.ScoreStatus.FINAL,null,"UNKNOWN",null);
        when(sources.validate(reference)).thenReturn(new ActionProductionSourceValidator.Validated(reference,"group","result","{\"frozen\":true}",SelectorAssetService.hash("{\"frozen\":true}"),policy,value,bound));
        when(adapter.requiredResources()).thenReturn(Set.of());when(adapter.ready(any(),any())).thenReturn(true);
        when(adapters.require(any())).thenReturn(new ActionAdapterRegistry.Binding(new ActionAdapter.Definition(ActionExecutionContract.Type.SAVE_RESULT,1,true),adapter));
        var enqueue=new ActionEnqueueService(sources,rows,mock(ActionDiscoveryMapper.class),adapters,scope,json);
        id=tx.execute(status->enqueue.enqueue(reference,actor()).getFirst());
        assertThat(tx.<Integer>execute(status->rows.blockReady(id,0,"BLOCKED_ADAPTER","ADAPTER_CONFIGURATION_UNAVAILABLE",java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)))).isEqualTo(1);
    }
    private boolean recover(){return recovery.recover("result",id,1,actor(),"  恢复原因  ");}
    private void unchanged(){
        var row=rows.selectById(id);assertThat(row.getStatus()).isEqualTo("BLOCKED_ADAPTER");assertThat(row.getVersion()).isEqualTo(1);
        assertThat(row.getClaimToken()).isNull();assertThat(row.getOutcome()).isNull();assertThat(row.getAttemptCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_action_execution_event WHERE action_execution_id=? AND version=2",Integer.class,id)).isZero();
    }
    @Test void missingReasonUpdateRollsBackTheActualStateAndEvent(){
        when(events.recordRecoveryReason(anyString(),anyLong(),anyLong(),anyString(),anyString(),anyString())).thenReturn(0);
        assertThatThrownBy(this::recover).isInstanceOf(RuntimeException.class);unchanged();verify(adapter,never()).execute(any());
    }
    @Test void failureAfterActualReasonSqlRollsBackStateEventAndReason(){
        when(events.recordRecoveryReason(anyString(),anyLong(),anyLong(),anyString(),anyString(),anyString())).thenAnswer(call->{
            int changed=realEvents.recordRecoveryReason(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4),call.getArgument(5));
            assertThat(changed).isEqualTo(1);throw new IllegalStateException("after real reason write");
        });
        assertThatThrownBy(this::recover).hasMessage("after real reason write");unchanged();
    }
    @Test void recoveryCommitsIndependentlyOfAnOuterRollbackAndCannotRepeat(){
        tx.execute(status->{assertThat(recover()).isTrue();status.setRollbackOnly();return null;});
        var row=rows.selectById(id);assertThat(row.getStatus()).isEqualTo("PENDING");assertThat(row.getVersion()).isEqualTo(2);
        assertThat(realEvents.page(id,"result",null).getFirst().getRecoveryReason()).isEqualTo("恢复原因");
        assertThat(realEvents.page(id,"result",null).getFirst().getActor()).isEqualTo("alice");
        assertThat(recover()).isFalse();verify(adapter,never()).execute(any());
    }
}
