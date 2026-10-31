package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.quality.*;
import io.github.opensabre.iqc.quality.dao.ResultReviewMapper;
import io.github.opensabre.iqc.quality.model.ResultReview;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import static io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.*;
import static org.assertj.core.api.Assertions.*;

/** Actual review transactions and stored projections; organization and model results are controlled seams. */
class IsolatedMysqlClaimSourceSupportIT {
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static BusinessItemReviewService reviews;
    private static ResultReviewMapper rows;
    private static IqcDataScope scope;
    private static final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final IsolatedMysqlActionSourceIT fixture=new IsolatedMysqlActionSourceIT();

    @BeforeAll static void start() throws Exception {
        IsolatedMysqlActionSourceIT.start();
        context=new AnnotationConfigApplicationContext(IsolatedMysqlBusinessReviewActionIT.Config.class);
        jdbc=new JdbcTemplate(context.getBean(DataSource.class));
        reviews=context.getBean(BusinessItemReviewService.class);scope=context.getBean(IqcDataScope.class);
        rows=new SqlSessionTemplate(context.getBean(SqlSessionFactory.class)).getMapper(ResultReviewMapper.class);
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(reviews)).isTrue();
    }
    @AfterAll static void close() {
        if(jdbc!=null)jdbc.execute("DROP TRIGGER IF EXISTS reject_claim_review_update");
        if(context!=null)context.close();IsolatedMysqlActionSourceIT.close();
    }
    @BeforeEach void prepare() throws Exception {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_claim_review_update");
        jdbc.update("DELETE FROM iqc_result_review");jdbc.update("DELETE FROM iqc_conversation_message");
        fixture.fixture("CREATE_REVIEW");
        jdbc.update("UPDATE iqc_inspection_task SET status='SUCCEEDED' WHERE id='task'");
        jdbc.update("UPDATE iqc_task_execution SET status='SUCCEEDED' WHERE id='execution'");
        jdbc.update("INSERT INTO iqc_conversation_message(id,conversation_id,sequence_no,speaker_role,content,relative_time,raw_line,line_number) VALUES ('message','conversation',1,'agent','frozen text','00:00:00','frozen text',1)");
    }
    private <T> T actor(String owner,String group,Supplier<T> work) {
        var actor=new ScheduledActorAccessService.Resolved("user-"+owner,owner,false,group,Set.of(),
                Set.of("iqc:review:create","iqc:review:decide","iqc:review:view"),Instant.now());
        return scope.asScheduledActor(actor,work);
    }
    private <T> T owner(Supplier<T> work){return actor("alice","group",work);}
    private ResultReview request(){return owner(()->reviews.request("result",0,"claim-review-0001","inspect sources"));}
    private Decision decision(SourceSupport verdict,String reason) {
        var entry=owner(()->reviews.findingContext("result")).findings().getFirst();
        var claim=new ClaimSourceSupport("fact",verdict,verdict==SourceSupport.INSUFFICIENT_SOURCE?List.of():List.of("evidence"),List.of(),reason);
        return new Decision("result","RISK",entry.expectedFindingHash(),Finding.State.KNOWN,
                json.getNodeFactory().booleanNode(true),Finding.Severity.HIGH,"human check",List.of("evidence"),List.of(),null,List.of(claim));
    }
    private ResultReview decide(ResultReview round,Decision decision) {
        return owner(()->reviews.decide(round.getId(),1,false,List.of(),List.of(decision),"review completed"));
    }
    private Map<String,Object> persisted(ResultReview round){return jdbc.queryForMap("SELECT * FROM iqc_result_review WHERE id=?",round.getId());}

    @Test void allThreeVerdictsPersistReadBackAndReplayIdempotentlyWithoutChangingMachineRows() throws Exception {
        var machine=jdbc.queryForMap("SELECT * FROM iqc_inspection_conversation_result WHERE id='result'");
        var task=jdbc.queryForMap("SELECT * FROM iqc_inspection_task WHERE id='task'");
        for(var verdict:SourceSupport.values()) {
            jdbc.update("DELETE FROM iqc_result_review");
            var round=request();var decision=decision(verdict,"verified "+verdict);
            assertThat(decide(round,decision).getStatus()).isEqualTo("COMPLETED");
            // Force a fresh mapper read rather than inspecting the returned in-memory entity.
            var stored=rows.selectById(round.getId());
            var projection=json.readValue(stored.getReviewedResultJson(),BusinessItemReviewEvaluator.Projection.class);
            assertThat(projection.findingReview().findings().decisions().getFirst().sourceSupport())
                    .containsExactlyElementsOf(decision.sourceSupport());
            var readContext=owner(()->reviews.findingContext("result"));
            assertThat(readContext.findings().getFirst().sourceSupport()).containsExactlyElementsOf(decision.sourceSupport());
            assertThat(readContext.findings().getFirst().modelClaims()).hasSize(1);
            var before=persisted(round);
            assertThat(decide(round,decision).getId()).isEqualTo(round.getId());
            assertThat(persisted(round)).isEqualTo(before);
            var changed=decision(verdict,"different assessment");
            assertThatThrownBy(()->decide(round,changed)).hasMessageContaining("不能覆盖");
            assertThat(persisted(round)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_result_review",Integer.class)).isEqualTo(1);
        }
        assertThat(jdbc.queryForMap("SELECT * FROM iqc_inspection_conversation_result WHERE id='result'")).isEqualTo(machine);
        assertThat(jdbc.queryForMap("SELECT * FROM iqc_inspection_task WHERE id='task'")).isEqualTo(task);
    }

    @Test void narrowerScopeRejectsReadAndWriteThenOwnerCanCompleteUnchangedPendingRound() {
        var round=request();var decision=decision(SourceSupport.SUPPORTED,"source verified");var before=persisted(round);
        assertThatThrownBy(()->actor("mallory","other",()->reviews.findingContext("result"))).hasMessageContaining("无权");
        assertThatThrownBy(()->actor("mallory","other",()->reviews.decide(round.getId(),1,false,List.of(),List.of(decision),"review completed")))
                .hasMessageContaining("无权");
        assertThat(persisted(round)).isEqualTo(before);
        assertThat(decide(round,decision).getStatus()).isEqualTo("COMPLETED");
    }

    @Test void databaseWriteFailureRollsBackAndSameSubmissionCanThenComplete() {
        var round=request();var decision=decision(SourceSupport.CONTRADICTED,"source contradicts claim");var before=persisted(round);
        jdbc.execute("CREATE TRIGGER reject_claim_review_update BEFORE UPDATE ON iqc_result_review FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated review failure'");
        try {assertThatThrownBy(()->decide(round,decision)).isInstanceOf(RuntimeException.class);}
        finally {jdbc.execute("DROP TRIGGER reject_claim_review_update");}
        assertThat(persisted(round)).isEqualTo(before);
        assertThat(decide(round,decision).getStatus()).isEqualTo("COMPLETED");
    }
}
