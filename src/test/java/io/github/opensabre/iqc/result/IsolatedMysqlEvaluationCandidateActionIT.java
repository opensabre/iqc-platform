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

/** Real review decisions, sample capture and candidate adapter; no simulated owning-operation success. */
class IsolatedMysqlEvaluationCandidateActionIT {
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static IqcDataScope scope;
    private static ObjectMapper json;
    private static EvaluationCandidateActionAdapter adapter;
    private final IsolatedMysqlActionSourceIT fixture=new IsolatedMysqlActionSourceIT();
    private ActionAdapter.Request request;
    private String reviewId;
    @Configuration(proxyBeanMethods=false)
    @Import(IsolatedMysqlBusinessReviewActionIT.Config.class)
    static class Config {
        @Bean BusinessReviewSampleService samples(SqlSessionTemplate session,BusinessItemReviewService reviews,IqcDataScope scope,ObjectMapper json){
            var factory=session.getSqlSessionFactory();
            for(var type:List.of(QualitySampleMapper.class,QualitySampleVersionMapper.class,ConversationMapper.class))
                if(!factory.getConfiguration().hasMapper(type))factory.getConfiguration().addMapper(type);
            return new BusinessReviewSampleService(session.getMapper(QualitySampleMapper.class),session.getMapper(ResultReviewMapper.class),
                    session.getMapper(ConversationInspectionResultMapper.class),session.getMapper(InspectionTaskMapper.class),session.getMapper(ConversationMapper.class),
                    session.getMapper(ConversationMessageMapper.class),reviews,scope,json);
        }
        @Bean io.github.opensabre.iqc.quality.evaluation.GoldSampleService gold(SqlSessionTemplate session,IqcDataScope scope,ObjectMapper json){
            return new io.github.opensabre.iqc.quality.evaluation.GoldSampleService(session.getMapper(QualitySampleMapper.class),
                    session.getMapper(QualitySampleVersionMapper.class),session.getMapper(ConversationMapper.class),
                    session.getMapper(ConversationMessageMapper.class),scope,json);
        }
        @Bean EvaluationCandidateActionService candidateService(ActionProductionSourceValidator sources,BusinessReviewSampleService samples,
                SqlSessionTemplate session,IqcDataScope scope,ObjectMapper json){
            return new EvaluationCandidateActionService(sources,samples,session.getMapper(ResultReviewMapper.class),scope,json);
        }
        @Bean EvaluationCandidateActionAdapter candidateAdapter(EvaluationCandidateActionService service,SqlSessionTemplate session,ObjectMapper json){
            return new EvaluationCandidateActionAdapter(service,session.getMapper(ResultReviewMapper.class),json);
        }
    }
    @BeforeAll static void start() throws Exception {
        IsolatedMysqlActionSourceIT.start();context=new AnnotationConfigApplicationContext(Config.class);
        jdbc=new JdbcTemplate(context.getBean(DataSource.class));tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        scope=context.getBean(IqcDataScope.class);json=context.getBean(ObjectMapper.class);adapter=context.getBean(EvaluationCandidateActionAdapter.class);
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(EvaluationCandidateActionService.class)));
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(BusinessReviewSampleService.class)));
    }
    @AfterAll static void close(){
        if(jdbc!=null){jdbc.execute("DROP TRIGGER IF EXISTS reject_candidate_action_write");jdbc.execute("DROP TRIGGER IF EXISTS reject_mined_gold_write");}
        if(context!=null)context.close();IsolatedMysqlActionSourceIT.close();
    }
    private <T> T as(String owner,Set<String> grants,Supplier<T> callback){
        return scope.asScheduledActor(new ScheduledActorAccessService.Resolved(owner.equals("alice")?"user":"inspector-user",
                owner,false,"group",Set.of(),grants,Instant.now()),callback);
    }
    @BeforeEach void prepare() throws Exception {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_candidate_action_write");
        jdbc.execute("DROP TRIGGER IF EXISTS reject_mined_gold_write");
        jdbc.update("DELETE FROM iqc_quality_sample_version");jdbc.update("DELETE FROM iqc_quality_sample");jdbc.update("DELETE FROM iqc_result_review");
        jdbc.update("DELETE FROM iqc_conversation_message");jdbc.update("DELETE FROM iqc_conversation");
        jdbc.update("DELETE FROM iqc_inspection_rule_result");
        fixture.fixture("ADD_TO_EVALUATION_CANDIDATE",java.time.LocalTime.MIDNIGHT);
        jdbc.update("UPDATE iqc_inspection_task SET status='SUCCEEDED' WHERE id='task'");jdbc.update("UPDATE iqc_task_execution SET status='SUCCEEDED' WHERE id='execution'");
        jdbc.update("INSERT INTO iqc_conversation_message(id,conversation_id,sequence_no,speaker_role,content,relative_time,raw_line,line_number,created_by) VALUES ('message','conversation',1,'agent','frozen text','00:00:00','archive raw text',1,'importer')");
        jdbc.update("INSERT INTO iqc_conversation(id,source_file_name,source_fingerprint,status,message_count,channel,business_type,created_by,owner_group_id) VALUES ('conversation','isolated.txt','isolated-candidate-fingerprint','IMPORTED',1,'TEXT','current metadata','alice','group')");
        var reference=new ActionProductionSourceValidator.Reference("task","execution","conversation","result","alice");
        var validated=tx.execute(status->context.getBean(ActionProductionSourceValidator.class).validate(reference));
        String policy=json.writeValueAsString(validated.policy());
        var source=new ActionExecutionContract.Source("task","execution","conversation","result","result",io.github.opensabre.iqc.selector.SelectorAssetService.hash(policy),"save","alice");
        String key=source.idempotencyKey(adapter.definition().type());
        request=new ActionAdapter.Request(key,key,source,adapter.definition(),1,validated.inputSnapshotJson(),validated.inputSnapshotHash(),policy);
        reviewId=as("alice",Set.of("iqc:review:create"),()->context.getBean(BusinessItemReviewService.class)
                .request("result",0,"candidate-review-0001","candidate review").getId());
    }
    private void completeReview(){
        as("inspector",Set.of("iqc:review:decide"),()->context.getBean(BusinessItemReviewService.class).decide(reviewId,1,false,
                List.of(new BusinessItemReviewEvaluator.Decision("result","risk",InspectionScoring.ItemStatus.FAIL,
                        InspectionScoring.ItemStatus.PASS,"human correction",List.of("message"))),"human checked"));
    }
    private boolean ready(){
        return tx.execute(status->{
            var validated=context.getBean(ActionProductionSourceValidator.class).validate(
                    new ActionProductionSourceValidator.Reference("task","execution","conversation","result","alice"));
            return adapter.ready(validated.policy().actions().getFirst(),validated);
        });
    }
    private ActionAdapter.Receipt execute(Set<String> grants){return as("alice",grants,()->adapter.execute(request));}
    private ActionAdapter.Receipt execute(){return execute(Set.of("iqc:sample:manage"));}
    private int count(){return jdbc.queryForObject("SELECT COUNT(*) FROM iqc_quality_sample",Integer.class);}
    @Test void actualHumanDecisionProducesOneUnlabelledCandidateWithFrozenContext() throws Exception {
        assertFalse(ready());completeReview();assertTrue(ready());var receipt=execute();assertEquals(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,receipt.outcome());
        assertEquals(receipt.resultReference(),execute().resultReference());assertEquals(1,count());
        assertEquals("CANDIDATE",jdbc.queryForObject("SELECT status FROM iqc_quality_sample",String.class));
        assertEquals("UNLABELLED",jdbc.queryForObject("SELECT gold_status FROM iqc_quality_sample",String.class));
        var input=json.readTree(jdbc.queryForObject("SELECT content_snapshot FROM iqc_quality_sample",String.class));
        assertEquals("frozen",input.path("businessContext").path("businessType").asText());
        assertEquals("frozen text",input.path("segments").get(0).path("content").asText());
        var lineage=json.readTree(jdbc.queryForObject("SELECT expected_json FROM iqc_quality_sample",String.class));
        assertEquals(reviewId,lineage.path("reviewId").asText());assertEquals("inspector",lineage.path("reviewerId").asText());
        assertEquals(100,lineage.path("expected").path("scoring").path("finalScore").intValue());
        assertEquals(80,jdbc.queryForObject("SELECT final_score FROM iqc_inspection_conversation_result WHERE id='result'",Integer.class));
    }
    @Test void candidateCommitSurvivesOuterRollback(){
        completeReview();tx.executeWithoutResult(status->{assertEquals(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,execute().outcome());status.setRollbackOnly();});assertEquals(1,count());
    }
    @Test void pendingReviewCannotCreateCandidate(){assertFalse(ready());assertEquals(ActionExecutionContract.Outcome.UNKNOWN,execute().outcome());assertEquals(0,count());}
    @Test void missingSampleGrantCannotCreateCandidate(){completeReview();assertEquals(ActionExecutionContract.Outcome.UNKNOWN,execute(Set.of()).outcome());assertEquals(0,count());}
    @Test void editedTranscriptCannotBeCapturedAsOriginalInput(){
        completeReview();jdbc.update("UPDATE iqc_conversation_message SET content='edited' WHERE id='message'");
        assertEquals(ActionExecutionContract.Outcome.UNKNOWN,execute().outcome());assertEquals(0,count());
    }
    @Test void failedInsertHasNoSuccessReceiptOrPartialCandidate(){
        completeReview();jdbc.execute("CREATE TRIGGER reject_candidate_action_write BEFORE INSERT ON iqc_quality_sample FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated candidate rejection'");
        var receipt=execute();assertEquals(ActionExecutionContract.Outcome.UNKNOWN,receipt.outcome());assertNull(receipt.resultReference());assertEquals(0,count());
    }
    @Test void recoveryReadsFirstCandidateAfterTranscriptAndEidChangeWithoutSendGrant() {
        completeReview();var receipt=execute();assertEquals(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,receipt.outcome());
        var before=jdbc.queryForMap("SELECT * FROM iqc_quality_sample");
        jdbc.update("UPDATE iqc_conversation_message SET content='later text' WHERE id='message'");
        jdbc.update("UPDATE iqc_inspection_task SET current_execution_id='later-execution' WHERE id='task'");
        var confirmed=as("alice",Set.of(),()->adapter.reconcile(request));
        assertEquals(receipt.resultReference(),confirmed.resultReference());assertEquals(before,jdbc.queryForMap("SELECT * FROM iqc_quality_sample"));
    }
    @Test void recoveryWithoutArtifactNeverCreatesCandidate() {
        completeReview();assertNull(as("alice",Set.of(),()->adapter.reconcile(request)));assertEquals(0,count());
    }

    /** Synthetic trusted storage fixture, deliberately not a test of the unfinished automatic producer. */
    private String storedMinedCandidate() throws Exception {
        completeReview();var receipt=execute();assertEquals(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,receipt.outcome());
        String id=receipt.resultReference();
        var input=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(jdbc.queryForObject(
                "SELECT content_snapshot FROM iqc_quality_sample WHERE id=?",String.class,id));
        input.put("schemaVersion",BusinessReviewSampleService.EXACT_INPUT_SCHEMA);
        var previous=json.readTree(jdbc.queryForObject("SELECT expected_json FROM iqc_quality_sample WHERE id=?",String.class,id));
        var lineage=new io.github.opensabre.iqc.quality.evaluation.MinedCandidateLineage(
                io.github.opensabre.iqc.quality.evaluation.MinedCandidateLineage.SCHEMA,"result","task","execution","conversation",
                "alice","group",previous.path("sourceHash").asText(),BusinessReviewSampleService.candidateInputHash(input),
                previous.path("executionInputHash").asText(),"c".repeat(64),"LOW_CONFIDENCE",1,List.of("LOW_CONFIDENCE"),"d".repeat(64));
        jdbc.update("UPDATE iqc_quality_sample SET sample_type='UNCLASSIFIED',content_snapshot=?,expected_json=? WHERE id=?",
                input.toString(),json.writeValueAsString(lineage),id);
        return id;
    }
    private void annotateMined(String id) {
        var gold=context.getBean(io.github.opensabre.iqc.quality.evaluation.GoldSampleService.class);
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(gold));
        as("alice",Set.of(),()->gold.annotate(id,new io.github.opensabre.iqc.quality.evaluation.GoldSampleService.Annotation(0,
                List.of(new io.github.opensabre.iqc.quality.evaluation.GoldFinding("FACT",io.github.opensabre.iqc.finding.Finding.ValueType.BOOLEAN,
                        io.github.opensabre.iqc.finding.Finding.State.KNOWN,json.valueToTree(true),List.of("message"))),
                null,List.of(),io.github.opensabre.iqc.quality.evaluation.GoldConversation.Difficulty.BASIC,null)));
    }
    @Test void storedMinedInputSurvivesLiveArchiveChangesAndRequiresIndependentSqlApproval() throws Exception {
        String id=storedMinedCandidate();
        String original=jdbc.queryForObject("SELECT content_snapshot FROM iqc_quality_sample WHERE id=?",String.class,id);
        jdbc.update("UPDATE iqc_conversation_message SET content='later archive' WHERE id='message'");
        annotateMined(id);
        assertEquals("PENDING_REVIEW",jdbc.queryForObject("SELECT gold_status FROM iqc_quality_sample WHERE id=?",String.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM iqc_quality_sample_version",Integer.class));
        var gold=context.getBean(io.github.opensabre.iqc.quality.evaluation.GoldSampleService.class);
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->as("alice",Set.of(),()->gold.review(id,1,true,"self review")));
        var version=as("inspector",Set.of(),()->gold.review(id,1,true,"independently checked"));
        assertEquals("APPROVED",jdbc.queryForObject("SELECT gold_status FROM iqc_quality_sample WHERE id=?",String.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM iqc_quality_sample_version",Integer.class));
        var reloaded=as("inspector",Set.of(),()->gold.version(id,1));
        assertEquals(version.getContentHash(),reloaded.getContentHash());
        var frozen=gold.read(reloaded.getSnapshotJson(),io.github.opensabre.iqc.quality.evaluation.GoldConversation.class);
        assertEquals("frozen text",frozen.messages().getFirst().content());
        assertEquals("LOW_CONFIDENCE",frozen.businessContext().at("/candidateLineage/miningStrategy").asText());
        assertEquals(original,jdbc.queryForObject("SELECT content_snapshot FROM iqc_quality_sample WHERE id=?",String.class,id));
        assertEquals(version.getId(),as("inspector",Set.of(),()->gold.review(id,1,true,"independently checked")).getId());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM iqc_quality_sample_version",Integer.class));
    }
    @Test void failedGoldStatusWriteRollsBackInsertedReviewVersion() throws Exception {
        String id=storedMinedCandidate();annotateMined(id);
        jdbc.execute("CREATE TRIGGER reject_mined_gold_write BEFORE UPDATE ON iqc_quality_sample FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated gold rejection'");
        var gold=context.getBean(io.github.opensabre.iqc.quality.evaluation.GoldSampleService.class);
        assertThrows(RuntimeException.class,()->as("inspector",Set.of(),()->gold.review(id,1,true,"independently checked")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM iqc_quality_sample_version",Integer.class));
        assertEquals("PENDING_REVIEW",jdbc.queryForObject("SELECT gold_status FROM iqc_quality_sample WHERE id=?",String.class,id));
        jdbc.execute("DROP TRIGGER reject_mined_gold_write");
        assertEquals("APPROVED",as("inspector",Set.of(),()->gold.review(id,1,true,"independently checked")).getDecision());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM iqc_quality_sample_version",Integer.class));
    }

}
