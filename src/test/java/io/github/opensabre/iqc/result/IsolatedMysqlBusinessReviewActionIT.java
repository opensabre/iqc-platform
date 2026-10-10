package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.action.*;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.quality.BusinessItemReviewService;
import io.github.opensabre.iqc.quality.dao.ResultReviewMapper;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.*;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real owning review service, source validation, scope and InnoDB; model and published release stay fixture seams. */
class IsolatedMysqlBusinessReviewActionIT {
    private static AnnotationConfigApplicationContext context;
    private static final IsolatedMysqlActionSourceIT fixture=new IsolatedMysqlActionSourceIT();
    private static JdbcTemplate jdbc;
    private static BusinessReviewActionAdapter adapter;
    private static IqcDataScope scope;
    private static TransactionTemplate tx;
    private ActionAdapter.Request request;
    @Configuration(proxyBeanMethods=false)
    @Import(IsolatedMysqlTaskExecutionStateIT.DatabaseConfiguration.class)
    static class Config {
        @Bean ObjectMapper json(){return new ObjectMapper().findAndRegisterModules();}
        @Bean IqcDataScope scope(){return new IqcDataScope(mock(IqcOrganizationClient.class));}
        @Bean SqlSessionTemplate domainSession(SqlSessionFactory factory){
            for(var type:java.util.List.of(ResultReviewMapper.class,ConversationInspectionResultMapper.class,ConversationMessageMapper.class))
                if(!factory.getConfiguration().hasMapper(type))factory.getConfiguration().addMapper(type);
            return new SqlSessionTemplate(factory);
        }
        @Bean ActionProductionSourceValidator sources(SqlSessionTemplate session,ObjectMapper json){
            var assets=mock(io.github.opensabre.iqc.pipelineasset.PipelineAssetService.class);
            when(assets.publishedTaskRelease(any())).thenAnswer(call->{
                com.fasterxml.jackson.databind.JsonNode snapshot=call.getArgument(0);
                return json.treeToValue(snapshot.path("pipelineSnapshot").path("release"),
                        io.github.opensabre.iqc.pipelineasset.PipelineRelease.class);
            });
            return new ActionProductionSourceValidator(session.getMapper(InspectionTaskMapper.class),
                    session.getMapper(io.github.opensabre.iqc.task.dao.TaskExecutionMapper.class),session.getMapper(ConversationInspectionResultMapper.class),
                    assets,new io.github.opensabre.iqc.conversation.ProductionInputSnapshotService(null,null,null,json),json);
        }
        @Bean BusinessItemReviewService reviews(SqlSessionTemplate session,IqcDataScope scope,ObjectMapper json){
            return new BusinessItemReviewService(session.getMapper(ResultReviewMapper.class),session.getMapper(ConversationInspectionResultMapper.class),
                    session.getMapper(InspectionTaskMapper.class),session.getMapper(ConversationMessageMapper.class),scope,json);
        }
        @Bean BusinessReviewActionService actionService(ActionProductionSourceValidator sources,BusinessItemReviewService reviews,IqcDataScope scope,ObjectMapper json){
            return new BusinessReviewActionService(sources,reviews,scope,json);
        }
        @Bean BusinessReviewActionAdapter adapter(BusinessReviewActionService service,ObjectMapper json){return new BusinessReviewActionAdapter(service,json);}
    }
    @BeforeAll static void start() throws Exception {
        IsolatedMysqlActionSourceIT.start();context=new AnnotationConfigApplicationContext(Config.class);
        jdbc=new JdbcTemplate(context.getBean(DataSource.class));adapter=context.getBean(BusinessReviewActionAdapter.class);scope=context.getBean(IqcDataScope.class);
        tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(BusinessReviewActionService.class)));
    }
    @AfterAll static void close(){
        if(jdbc!=null)jdbc.execute("DROP TRIGGER IF EXISTS reject_review_action_write");
        if(context!=null)context.close();IsolatedMysqlActionSourceIT.close();
    }
    @BeforeEach void prepare() throws Exception {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_review_action_write");jdbc.update("DELETE FROM iqc_result_review");jdbc.update("DELETE FROM iqc_conversation_message");
        fixture.fixture("CREATE_REVIEW");
        jdbc.update("UPDATE iqc_inspection_task SET status='SUCCEEDED' WHERE id='task'");jdbc.update("UPDATE iqc_task_execution SET status='SUCCEEDED' WHERE id='execution'");
        jdbc.update("INSERT INTO iqc_conversation_message(id,conversation_id,sequence_no,speaker_role,content,relative_time,raw_line,line_number) VALUES ('message','conversation',1,'agent','frozen text','00:00:00','frozen text',1)");
        var json=new ObjectMapper();var reference=new ActionProductionSourceValidator.Reference("task","execution","conversation","result","alice");
        var validated=tx.execute(status->context.getBean(ActionProductionSourceValidator.class).validate(reference));
        String policy=json.writeValueAsString(validated.policy());
        var source=new ActionExecutionContract.Source("task","execution","conversation","result","result",io.github.opensabre.iqc.selector.SelectorAssetService.hash(policy),"save","alice");
        var definition=adapter.definition();String key=source.idempotencyKey(definition.type());
        request=new ActionAdapter.Request(key,key,source,definition,1,validated.inputSnapshotJson(),validated.inputSnapshotHash(),policy);
    }
    private ActionAdapter.Receipt execute(Set<String> grants){
        var actor=new ScheduledActorAccessService.Resolved("user","alice",false,"group",Set.of(),grants,Instant.now());
        return scope.asScheduledActor(actor,()->adapter.execute(request));
    }
    private ActionAdapter.Receipt execute(){return execute(Set.of("iqc:review:create"));}
    private int count(){return jdbc.queryForObject("SELECT COUNT(*) FROM iqc_result_review",Integer.class);}
    @Test void createsActualPendingReviewAndReplayKeepsOneRound(){
        var receipt=execute();assertEquals(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,receipt.outcome());
        assertEquals(receipt.resultReference(),execute().resultReference());assertEquals(1,count());
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM iqc_result_review",String.class));
        assertEquals("BUSINESS",jdbc.queryForObject("SELECT target_type FROM iqc_result_review",String.class));
        assertEquals("alice",jdbc.queryForObject("SELECT created_by FROM iqc_result_review",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT review_revision FROM iqc_result_review",Integer.class));
        assertEquals("QUALIFIED",jdbc.queryForObject("SELECT result_status FROM iqc_inspection_conversation_result WHERE id='result'",String.class));
    }
    @Test void actionCommitSurvivesOuterRollback(){
        tx.executeWithoutResult(status->{assertEquals(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,execute().outcome());status.setRollbackOnly();});
        assertEquals(1,count());
    }
    @Test void missingOperationGrantCreatesNoReviewAndNoSuccess(){
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN,execute(Set.of()).outcome());assertEquals(0,count());
    }
    @Test void runningTaskCannotOpenReview(){
        jdbc.update("UPDATE iqc_inspection_task SET status='RUNNING' WHERE id='task'");
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN,execute().outcome());assertEquals(0,count());
    }
    @Test void failedInsertReturnsUnknownAndLeavesNoPartialReview(){
        jdbc.execute("CREATE TRIGGER reject_review_action_write BEFORE INSERT ON iqc_result_review FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated review rejection'");
        var receipt=execute();assertEquals(ActionExecutionContract.Outcome.UNKNOWN,receipt.outcome());assertNull(receipt.resultReference());assertEquals(0,count());
        jdbc.execute("DROP TRIGGER reject_review_action_write");assertEquals(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,execute().outcome());assertEquals(1,count());
    }
    @Test void recoveryReadsExistingReviewWithoutSendGrantOrCurrentEid() {
        var receipt=execute();assertEquals(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,receipt.outcome());
        var before=jdbc.queryForMap("SELECT * FROM iqc_result_review");
        jdbc.update("UPDATE iqc_inspection_task SET current_execution_id='later-execution' WHERE id='task'");
        var actor=new ScheduledActorAccessService.Resolved("user","alice",false,"group",Set.of(),Set.of(),Instant.now());
        var confirmed=scope.asScheduledActor(actor,()->adapter.reconcile(request));
        assertEquals(receipt.resultReference(),confirmed.resultReference());assertEquals(before,jdbc.queryForMap("SELECT * FROM iqc_result_review"));
    }
    @Test void recoveryWithoutArtifactNeverCreatesReview() {
        var actor=new ScheduledActorAccessService.Resolved("user","alice",false,"group",Set.of(),Set.of(),Instant.now());
        assertNull(scope.asScheduledActor(actor,()->adapter.reconcile(request)));assertEquals(0,count());
    }

}
