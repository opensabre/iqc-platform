package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.action.*;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IsolatedMysqlActionSettlementIT {
    private static AnnotationConfigApplicationContext context;
    private static ActionExecutionMapper rows;
    private static ActionSettlementService settlements;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static final String TOKEN="a".repeat(32);
    private ActionAdapter.Request request;
    @Configuration(proxyBeanMethods=false)
    @Import(IsolatedMysqlTaskExecutionStateIT.DatabaseConfiguration.class)
    static class SettlementConfiguration {
        @Bean ActionExecutionMapper actionRows(SqlSessionFactory factory) {return new SqlSessionTemplate(factory).getMapper(ActionExecutionMapper.class);}
        @Bean ObjectMapper json() {return new ObjectMapper();}
        @Bean ActionAdapterRegistry registry() {
            var adapter=mock(ActionAdapter.class);
            when(adapter.ready(any(),any())).thenReturn(true);
            when(adapter.definition()).thenReturn(new ActionAdapter.Definition(ActionExecutionContract.Type.SAVE_RESULT,1,true));
            return new ActionAdapterRegistry(List.of(adapter));
        }
        @Bean ActionSettlementService settlements(ActionExecutionMapper mapper,ActionAdapterRegistry registry,ObjectMapper json) {
            return new ActionSettlementService(mapper,registry,json);
        }
    }
    @BeforeAll static void start() throws Exception {
        IsolatedMysqlTaskExecutionStateIT.start();
        context=new AnnotationConfigApplicationContext(SettlementConfiguration.class);
        rows=context.getBean(ActionExecutionMapper.class);settlements=context.getBean(ActionSettlementService.class);
        jdbc=new JdbcTemplate(context.getBean(DataSource.class));tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(settlements));
    }
    @AfterAll static void close() {
        if(jdbc!=null)jdbc.execute("DROP TRIGGER IF EXISTS reject_action_settlement");
        if(context!=null)context.close();IsolatedMysqlTaskExecutionStateIT.close();
    }
    @BeforeEach void fixture() throws Exception {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_action_settlement");jdbc.update("DELETE FROM iqc_action_execution");
        var json=new ObjectMapper();
        var action=new ActionPolicy.Action("save",ActionExecutionContract.Type.SAVE_RESULT,
                new ActionPolicy.Condition(ActionPolicy.Kind.ALWAYS,null,null,List.of()),ActionPolicy.Idempotency.PRODUCTION_SOURCE_V1,
                null,ActionPolicy.FailureHandling.RECORD_ONLY,json.createObjectNode());
        String body=json.writeValueAsString(new ActionPolicy(ActionPolicy.SCHEMA,List.of(action)));
        var source=new ActionExecutionContract.Source("task","execution","conversation","result","result",SelectorAssetService.hash(body),"save","alice");
        var definition=new ActionAdapter.Definition(ActionExecutionContract.Type.SAVE_RESULT,1,true);
        String key=source.idempotencyKey(definition.type());
        request=new ActionAdapter.Request(key,key,source,definition,1,"{}",SelectorAssetService.hash("{}"),body);
        var row=new ActionExecution();row.setId(key);row.setIdempotencyKey(key);row.setTaskId("task");row.setExecutionId("execution");
        row.setConversationId("conversation");row.setSourceResultId("result");row.setDecisionReference("result");row.setActionId("save");
        row.setActionPolicyHash(source.actionPolicyHash());row.setActionType("SAVE_RESULT");row.setOwner("alice");
        row.setInputSnapshotJson("{}");row.setInputSnapshotHash(request.inputSnapshotHash());row.setPolicySnapshotJson(body);
        row.setStatus("RUNNING");row.setAttemptCount(1);row.setVersion(1L);row.setClaimToken(TOKEN);row.setClaimedAtUtc(LocalDateTime.ofInstant(java.time.Instant.now().minusSeconds(1),java.time.ZoneOffset.UTC));
        assertEquals(1,rows.insert(row));
    }
    private ActionAdapter.Receipt receipt(ActionExecutionContract.Outcome outcome) {
        return new ActionAdapter.Receipt(request.idempotencyKey(),outcome,outcome==ActionExecutionContract.Outcome.UNKNOWN?null:"receipt",
                outcome==ActionExecutionContract.Outcome.CONFIRMED_SUCCESS?"result":null,
                outcome==ActionExecutionContract.Outcome.CONFIRMED_SUCCESS?null:"CONFIRMATION_UNKNOWN");
    }
    private ActionExecution stored() {return rows.selectById(request.executionId());}
    @Test void receiptCommitSurvivesOuterTransactionRollback() {
        tx.executeWithoutResult(status->{assertTrue(settlements.settle(request,1,TOKEN,receipt(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS)));status.setRollbackOnly();});
        assertEquals("COMPLETE",stored().getStatus());assertEquals("result",stored().getResultReference());assertEquals(2L,stored().getVersion());
    }
    @Test void rejectedSqlRollsBackAndKeepsClaimForRecovery() {
        jdbc.execute("CREATE TRIGGER reject_action_settlement BEFORE UPDATE ON iqc_action_execution FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated settlement rejection'");
        assertThrows(RuntimeException.class,()->settlements.settle(request,1,TOKEN,receipt(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS)));
        assertEquals("RUNNING",stored().getStatus());assertEquals(1L,stored().getVersion());assertEquals(TOKEN,stored().getClaimToken());assertNull(stored().getOutcome());
        jdbc.execute("DROP TRIGGER reject_action_settlement");
        assertTrue(settlements.settle(request,1,TOKEN,receipt(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS)));
    }
    @Test void unknownCannotBecomeRetryableAndLateSuccessCannotOverwriteIt() {
        assertTrue(settlements.settle(request,1,TOKEN,receipt(ActionExecutionContract.Outcome.UNKNOWN)));
        assertEquals("REQUIRES_RECONCILIATION",stored().getStatus());assertNull(stored().getNextAttemptAtUtc());
        assertFalse(settlements.settle(request,1,TOKEN,receipt(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS)));
        assertEquals("UNKNOWN",stored().getOutcome());
    }
    @Test void wrongTokenOrVersionCannotWriteReceipt() {
        assertFalse(settlements.settle(request,1,"b".repeat(32),receipt(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS)));
        assertFalse(settlements.settle(request,2,TOKEN,receipt(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS)));
        assertEquals("RUNNING",stored().getStatus());assertNull(stored().getEvidenceReference());
    }
    @Test void frozenRequestMismatchCannotSettle() {
        jdbc.update("UPDATE iqc_action_execution SET input_snapshot_json='changed' WHERE id=?",request.executionId());
        assertThrows(RuntimeException.class,()->settlements.settle(request,1,TOKEN,receipt(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS)));
        assertEquals("RUNNING",stored().getStatus());assertEquals(1L,stored().getVersion());
    }
    @Test void confirmedFailureWithoutRetryPolicyIsFinal() {
        assertTrue(settlements.settle(request,1,TOKEN,receipt(ActionExecutionContract.Outcome.CONFIRMED_RETRYABLE_FAILURE)));
        assertEquals("FAILED",stored().getStatus());assertNull(stored().getNextAttemptAtUtc());assertEquals("receipt",stored().getEvidenceReference());
    }
}
