package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.action.*;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.quality.*;
import io.github.opensabre.iqc.quality.dao.*;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL producer verification using a frozen v2 release before runtime evaluation. */
class IsolatedMysqlMinedCandidateActionIT {
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate jdbc;
    static IqcDataScope scope;
    static ObjectMapper json;
    static MinedEvaluationCandidateActionAdapter adapter;
    ActionAdapter.Request request;
    @Configuration(proxyBeanMethods=false)
    @Import(IsolatedMysqlEvaluationCandidateActionIT.Config.class)
    static class Config {
        @Bean MinedEvaluationCandidateService mined(ActionProductionSourceValidator sources, BusinessReviewSampleService initialized,
                SqlSessionTemplate session,IqcDataScope scope,ObjectMapper json){
            return new MinedEvaluationCandidateService(sources,session.getMapper(QualitySampleMapper.class),
                    session.getMapper(InspectionTaskMapper.class),scope,json);
        }
        @Bean MinedEvaluationCandidateActionAdapter minedAdapter(MinedEvaluationCandidateService service,ObjectMapper json){
            return new MinedEvaluationCandidateActionAdapter(service,json);
        }
    }
    @BeforeAll static void start() throws Exception {
        IsolatedMysqlActionSourceIT.start();context=new AnnotationConfigApplicationContext(Config.class);
        jdbc=new JdbcTemplate(context.getBean(DataSource.class));scope=context.getBean(IqcDataScope.class);
        json=context.getBean(ObjectMapper.class);adapter=context.getBean(MinedEvaluationCandidateActionAdapter.class);
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(MinedEvaluationCandidateService.class)));
    }
    @AfterAll static void close(){
        if(jdbc!=null)jdbc.execute("DROP TRIGGER IF EXISTS reject_mined_candidate_insert");
        if(context!=null)context.close();IsolatedMysqlActionSourceIT.close();
    }
    @BeforeEach void prepare() throws Exception {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_mined_candidate_insert");
        for(String table:List.of("iqc_quality_sample_version","iqc_quality_sample","iqc_result_review",
                "iqc_conversation_message","iqc_conversation","iqc_inspection_rule_result"))jdbc.update("DELETE FROM "+table);
        new IsolatedMysqlActionSourceIT().fixture("ADD_TO_EVALUATION_CANDIDATE",java.time.LocalTime.MIDNIGHT,
                io.github.opensabre.iqc.pipelineasset.PipelineRelease.EXACT_SCHEMA,true);
        var tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        var validated=tx.execute(status->context.getBean(ActionProductionSourceValidator.class).validate(
                new ActionProductionSourceValidator.Reference("task","execution","conversation","result","alice")));
        assertTrue(adapter.ready(validated.policy().actions().getFirst(),validated));
        String policy=json.writeValueAsString(validated.policy());
        var source=new ActionExecutionContract.Source("task","execution","conversation","result","result",
                io.github.opensabre.iqc.selector.SelectorAssetService.hash(policy),"save","alice");
        String key=source.idempotencyKey(adapter.definition().type());
        request=new ActionAdapter.Request(key,key,source,adapter.definition(),1,validated.inputSnapshotJson(),validated.inputSnapshotHash(),policy);
    }
    private ActionAdapter.Receipt execute(Set<String> grants){
        return scope.asScheduledActor(new ScheduledActorAccessService.Resolved("user","alice",false,"group",Set.of(),grants,Instant.now()),()->adapter.execute(request));
    }
    private ActionAdapter.Receipt reconcile(){
        return scope.asScheduledActor(new ScheduledActorAccessService.Resolved("user","alice",false,"group",Set.of(),Set.of(),Instant.now()),()->adapter.reconcile(request));
    }
    private int count(){return jdbc.queryForObject("SELECT COUNT(*) FROM iqc_quality_sample",Integer.class);}
    @Test void committedRunningConversationCreatesFrozenUnlabelledCandidateOnce() {
        var first=execute(Set.of("iqc:sample:manage"));
        assertEquals(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,first.outcome());
        assertEquals(first.resultReference(),execute(Set.of("iqc:sample:manage")).resultReference());
        assertEquals(1,count());
        var row=jdbc.queryForMap("SELECT * FROM iqc_quality_sample");
        assertEquals("UNCLASSIFIED",row.get("sample_type"));assertEquals("CANDIDATE",row.get("status"));
        assertEquals("UNLABELLED",row.get("gold_status"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM iqc_quality_sample_version",Integer.class));
        assertTrue(row.toString().contains("frozen text"));assertTrue(row.toString().contains("UNMEASURED_CONFIDENCE"));
        assertEquals(first.resultReference(),reconcile().resultReference());assertEquals(1,count());
    }
    @Test void missingCurrentGrantDoesNotCreateAndReconcileDoesNotCapture(){
        assertNull(reconcile());assertEquals(0,count());
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN,execute(Set.of()).outcome());assertEquals(0,count());
    }
    @Test void failedInsertRollsBackAndRetryCreatesOneCandidate(){
        jdbc.execute("CREATE TRIGGER reject_mined_candidate_insert BEFORE INSERT ON iqc_quality_sample FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated rollback probe'");
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN,execute(Set.of("iqc:sample:manage")).outcome());assertEquals(0,count());
        jdbc.execute("DROP TRIGGER reject_mined_candidate_insert");
        assertEquals(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,execute(Set.of("iqc:sample:manage")).outcome());assertEquals(1,count());
    }
    @Test void cancelledSourceCannotCreate(){
        jdbc.update("UPDATE iqc_inspection_task SET status='CANCELLED' WHERE id='task'");
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN,execute(Set.of("iqc:sample:manage")).outcome());assertEquals(0,count());
    }
}
