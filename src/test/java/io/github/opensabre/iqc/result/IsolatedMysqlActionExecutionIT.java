package io.github.opensabre.iqc.result;

import io.github.opensabre.iqc.action.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.apache.ibatis.session.SqlSessionFactory;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static io.github.opensabre.iqc.action.ActionExecutionContract.*;

/** Actual action SQL and transaction boundaries; source references are fixtures, no external action is sent. */
class IsolatedMysqlActionExecutionIT {
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static ActionExecutionMapper actions;
    private static TransactionTemplate tx;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 0, 0);
    private static final String TOKEN = "a".repeat(32);

    @BeforeAll static void start() throws Exception {
        IsolatedMysqlTaskExecutionStateIT.start();
        context = new AnnotationConfigApplicationContext(IsolatedMysqlTaskExecutionStateIT.DatabaseConfiguration.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        actions = new SqlSessionTemplate(context.getBean(SqlSessionFactory.class)).getMapper(ActionExecutionMapper.class);
        tx = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
    }
    @AfterAll static void close() {
        if (context != null) context.close();
        IsolatedMysqlTaskExecutionStateIT.close();
    }
    @BeforeEach void fixture() {
        jdbc.update("DELETE FROM iqc_action_reconciliation_attempt");jdbc.update("DELETE FROM iqc_action_execution_event");jdbc.update("DELETE FROM iqc_action_execution");
        jdbc.update("""
            INSERT INTO iqc_action_execution(id,idempotency_key,task_id,execution_id,conversation_id,
            source_result_id,decision_reference,action_policy_hash,action_id,action_type,owner,
            input_snapshot_json,input_snapshot_hash,policy_snapshot_json,status)
            VALUES ('action',?,'task','eid','conversation','result','decision',?,'alert','SEND_ALERT','alice','{}',?,'{}','PENDING')
            """, "1".repeat(64), "2".repeat(64), "3".repeat(64));
    }
    private int claim(String token, long version, LocalDateTime now) {
        return tx.execute(status -> actions.claim("action", version, token, now));
    }
    private int settle(long version, String token, Next next, Outcome outcome, LocalDateTime notBefore) {
        return tx.execute(status -> actions.settle("action", version, token, next, outcome, notBefore, NOW,
                "adapter-evidence", "external-result", null));
    }
    @Test void twoTransactionsOnlyGrantOneClaim() throws Exception {
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> first = () -> {
                ready.countDown(); if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("claim barrier timeout");
                return claim(TOKEN, 0, NOW);
            };
            java.util.concurrent.Callable<Integer> second = () -> {
                ready.countDown(); if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("claim barrier timeout");
                return claim("b".repeat(32), 0, NOW);
            };
            var a = workers.submit(first); var b = workers.submit(second);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); go.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(1, 0);
        } finally { go.countDown(); }
        var row = actions.selectById("action");
        assertThat(row.getStatus()).isEqualTo("RUNNING");
        assertThat(row.getVersion()).isEqualTo(1L); assertThat(row.getAttemptCount()).isEqualTo(1);
        assertThat(eventCount()).isEqualTo(2); // Initial observation plus the one winning claim.
    }
    @Test void settlementRequiresCurrentVersionAndTokenAndCannotRepeat() {
        assertThat(claim(TOKEN, 0, NOW)).isEqualTo(1);
        assertThat(settle(0, TOKEN, Next.COMPLETE, Outcome.CONFIRMED_SUCCESS, null)).isZero();
        assertThat(settle(1, "b".repeat(32), Next.COMPLETE, Outcome.CONFIRMED_SUCCESS, null)).isZero();
        assertThat(settle(1, TOKEN, Next.COMPLETE, Outcome.CONFIRMED_SUCCESS, null)).isEqualTo(1);
        assertThat(settle(1, TOKEN, Next.COMPLETE, Outcome.CONFIRMED_SUCCESS, null)).isZero();
        assertThat(actions.findDue(NOW.plusDays(1))).isEmpty();
        assertThat(actions.selectById("action").getResultReference()).isEqualTo("external-result");
    }
    @Test void retryCannotClaimEarlyAndNewClaimRejectsPreviousWorker() {
        assertThat(claim(TOKEN, 0, NOW)).isEqualTo(1);
        assertThat(settle(1, TOKEN, Next.WAITING_RETRY, Outcome.CONFIRMED_RETRYABLE_FAILURE, NOW.plusSeconds(10))).isEqualTo(1);
        assertThat(actions.findDue(NOW)).isEmpty();
        assertThat(claim("b".repeat(32), 2, NOW)).isZero();
        assertThat(actions.findDue(NOW.plusSeconds(10))).extracting(ActionExecution::getId).containsExactly("action");
        assertThat(claim("b".repeat(32), 2, NOW.plusSeconds(10))).isEqualTo(1);
        assertThat(settle(3, TOKEN, Next.COMPLETE, Outcome.CONFIRMED_SUCCESS, null)).isZero();
        var row = actions.selectById("action");
        assertThat(row.getAttemptCount()).isEqualTo(2); assertThat(row.getVersion()).isEqualTo(3L);
        assertThat(row.getOutcome()).isNull(); assertThat(row.getEvidenceReference()).isNull();
        assertThat(eventCount()).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.claim_token')) FROM iqc_action_execution_event WHERE action_execution_id='action' AND version IN (1,3) ORDER BY version", String.class))
                .containsExactly(TOKEN,"b".repeat(32));
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.outcome')) FROM iqc_action_execution_event WHERE action_execution_id='action' AND version=2", String.class))
                .isEqualTo("CONFIRMED_RETRYABLE_FAILURE");
    }
    @Test void unknownAndLostClaimsNeverReturnToAutomaticQueue() {
        assertThat(claim(TOKEN, 0, NOW)).isEqualTo(1);
        assertThat(tx.<Integer>execute(status -> actions.quarantineLostClaim("action", 1, TOKEN, NOW.minusSeconds(1), NOW))).isZero();
        assertThat(tx.<Integer>execute(status -> actions.quarantineLostClaim("action", 1, TOKEN, NOW, NOW))).isEqualTo(1);
        var row = actions.selectById("action");
        assertThat(row.getStatus()).isEqualTo("REQUIRES_RECONCILIATION"); assertThat(row.getOutcome()).isEqualTo("UNKNOWN");
        assertThat(row.getClaimToken()).isEqualTo(TOKEN);
        assertThat(row.getLastErrorCode()).isEqualTo("WORKER_CONFIRMATION_LOST");
        assertThat(actions.findDue(NOW.plusYears(1))).isEmpty();
        assertThat(claim("b".repeat(32), 2, NOW.plusDays(1))).isZero();
        assertThat(settle(1, TOKEN, Next.COMPLETE, Outcome.CONFIRMED_SUCCESS, null)).isZero();
    }
    @Test void invalidOutcomeAndTimeCombinationsDoNotSettle() {
        assertThat(claim(TOKEN, 0, NOW)).isEqualTo(1);
        assertThat(settle(1, TOKEN, Next.WAITING_RETRY, Outcome.UNKNOWN, NOW.plusSeconds(1))).isZero();
        assertThat(settle(1, TOKEN, Next.COMPLETE, Outcome.CONFIRMED_FINAL_FAILURE, null)).isZero();
        assertThat(settle(1, TOKEN, Next.WAITING_RETRY, Outcome.CONFIRMED_RETRYABLE_FAILURE, NOW)).isZero();
        assertThat(settle(1, TOKEN, Next.COMPLETE, Outcome.CONFIRMED_SUCCESS, NOW.plusSeconds(1))).isZero();
        assertThat(actions.selectById("action").getStatus()).isEqualTo("RUNNING");
        assertThat(settle(1, TOKEN, Next.REQUIRES_RECONCILIATION, Outcome.UNKNOWN, null)).isEqualTo(1);
    }
    @Test void transactionRollbackDoesNotConsumeClaimOrAttempt() {
        assertThatThrownBy(() -> tx.execute(status -> {
            assertThat(actions.claim("action", 0, TOKEN, NOW)).isEqualTo(1);
            throw new IllegalStateException("isolated transaction abort");
        })).hasMessage("isolated transaction abort");
        var row = actions.selectById("action"); assertThat(row.getStatus()).isEqualTo("PENDING");
        assertThat(row.getVersion()).isZero(); assertThat(row.getAttemptCount()).isZero();
        assertThat(row.getClaimToken()).isNull(); assertThat(eventCount()).isEqualTo(1);
        assertThat(claim(TOKEN, 0, NOW)).isEqualTo(1);
    }
    @Test void databaseRejectsDuplicateStableIdempotencyKey() {
        var row = actions.selectById("action"); row.setId("duplicate");
        assertThatThrownBy(() -> actions.insert(row)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_action_execution", Integer.class)).isEqualTo(1);
    }
    private int eventCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM iqc_action_execution_event WHERE action_execution_id='action'", Integer.class);
    }
    @Test void unknownResolutionKeepsOriginalClaimAndOperatorInOrderedHistory() {
        assertThat(claim(TOKEN,0,NOW)).isEqualTo(1);
        assertThat(tx.<Integer>execute(status -> actions.quarantineLostClaim("action",1,TOKEN,NOW,NOW))).isEqualTo(1);
        assertThat(tx.<Integer>execute(status -> actions.reconcileSuccess("action",2,TOKEN,NOW,"durable-proof","original-artifact","alice"))).isEqualTo(1);
        assertThat(tx.<Integer>execute(status -> actions.reconcileSuccess("action",2,TOKEN,NOW,"durable-proof","original-artifact","alice"))).isZero();
        assertThat(eventCount()).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.status')) FROM iqc_action_execution_event WHERE action_execution_id='action' ORDER BY version", String.class))
                .containsExactly("PENDING","RUNNING","REQUIRES_RECONCILIATION","COMPLETE");
        assertThat(jdbc.queryForObject("SELECT actor FROM iqc_action_execution_event WHERE action_execution_id='action' AND version=3",String.class)).isEqualTo("alice");
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(previous_state_json,'$.outcome')) FROM iqc_action_execution_event WHERE action_execution_id='action' AND version=3",String.class)).isEqualTo("UNKNOWN");
    }
    @Test void journalFailureRollsBackTheClaimBeforeDispatchCanReceiveIt() {
        jdbc.execute("CREATE TRIGGER reject_action_journal BEFORE INSERT ON iqc_action_execution_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='journal write rejected'");
        try {
            assertThatThrownBy(() -> claim(TOKEN,0,NOW)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(actions.selectById("action").getStatus()).isEqualTo("PENDING");
            assertThat(actions.selectById("action").getAttemptCount()).isZero();
            assertThat(eventCount()).isEqualTo(1);
        } finally { jdbc.execute("DROP TRIGGER reject_action_journal"); }
    }
    @Test void changingStateWithoutNextVersionIsRejectedButSnapshotTamperingDoesNotInventAnAttempt() {
        assertThatThrownBy(() -> jdbc.update("UPDATE iqc_action_execution SET status='COMPLETE' WHERE id='action'"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        jdbc.update("UPDATE iqc_action_execution SET input_snapshot_json='changed' WHERE id='action'");
        assertThat(actions.selectById("action").getStatus()).isEqualTo("PENDING");
        assertThat(eventCount()).isEqualTo(1);
    }

    @Test void prerequisiteDeferralAndAuthorizationBlockAreJournaledWithoutInventingAnAttempt() {
        assertThat(tx.<Integer>execute(status -> actions.deferPrerequisite("action",0,NOW,NOW.plusMinutes(1)))).isEqualTo(1);
        assertThat(tx.<Integer>execute(status -> actions.blockReady("action",1,"BLOCKED_AUTHORIZATION","AUTHORIZATION_MISSING",NOW))).isEqualTo(1);
        assertThat(eventCount()).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT JSON_EXTRACT(state_json,'$.attempt_count') FROM iqc_action_execution_event WHERE action_execution_id='action' ORDER BY version",String.class))
                .containsExactly("0","0","0");
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.status')) FROM iqc_action_execution_event WHERE action_execution_id='action' AND version=1",String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.last_error_code')) FROM iqc_action_execution_event WHERE action_execution_id='action' AND version=2",String.class)).isEqualTo("AUTHORIZATION_MISSING");
    }

    @Test void journalReaderSelectsOnlyWhitelistFieldsAndPaginatesByExactVersion() throws Exception {
        var reader=new SqlSessionTemplate(context.getBean(SqlSessionFactory.class)).getMapper(ActionStateEventMapper.class);
        // Future or malformed extra payload keys must not silently become public response fields.
        for(long version=10;version<70;version++)jdbc.update("""
            INSERT INTO iqc_action_execution_event(action_execution_id,version,previous_version,source_result_id,state_json,recorded_at_utc)
            VALUES ('action',?,?,'result',JSON_OBJECT('status','COMPLETE','attempt_count',2,'claim_token',?,
            'input_snapshot_json','private input','unexpected_field','private future data','outcome',NULL,
            'evidence_reference','null'),UTC_TIMESTAMP(3))
            """,version,version-1,TOKEN);
        var first=reader.page("action","result",null);
        assertThat(first).hasSize(51);assertThat(first.getFirst().getVersion()).isEqualTo(69L);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        for(var event:first) {
            assertThat(event.getStateJson()).doesNotContain(TOKEN,"claim_token","private input","unexpected_field");
            var state=json.readTree(event.getStateJson());assertThat(state.get("outcome").isNull()).isTrue();
            assertThat(state.get("evidenceReference").asText()).isEqualTo("null");
        }
        var second=reader.page("action","result",20L);
        assertThat(second).extracting(ActionStateEventProjection::getVersion).containsExactly(19L,18L,17L,16L,15L,14L,13L,12L,11L,10L,0L);
        assertThat(reader.page("action","foreign-result",null)).isEmpty();
        assertThat(reader.page("foreign-action","result",null)).isEmpty();
    }

    private ActionAdapter.Request reasonRequest() {
        String hash=io.github.opensabre.iqc.selector.SelectorAssetService.hash("{}");
        var source=new ActionExecutionContract.Source("task","eid","conversation","result","decision",hash,"alert","alice");
        var request=new ActionAdapter.Request("action",source.idempotencyKey(Type.SEND_ALERT),source,
                new ActionAdapter.Definition(Type.SEND_ALERT,1,false),1,"{}",hash,"{}");
        jdbc.update("UPDATE iqc_action_execution SET idempotency_key=?,action_policy_hash=?,input_snapshot_hash=? WHERE id='action'",
                request.idempotencyKey(),hash,hash);
        return request;
    }
    private ActionReconciliationAttemptService.Attempt reasonAttempt(String reason) {
        return context.getBean(ActionReconciliationAttemptService.class).start("action","result",2,"alice","user-1",reason);
    }
    private ActionAdapter.Receipt reasonReceipt(ActionAdapter.Request request) {
        return new ActionAdapter.Receipt(request.idempotencyKey(),Outcome.CONFIRMED_SUCCESS,"proof","artifact",null);
    }
    @Test void reconciliationReasonAndSuccessCommitTogetherAndCannotBeRewritten() {
        var state=context.getBean(ActionReconciliationStateService.class);var request=reasonRequest();var receipt=reasonReceipt(request);
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(state)).isTrue();
        assertThat(claim(TOKEN,0,NOW)).isEqualTo(1);
        assertThat(tx.<Integer>execute(status->actions.quarantineLostClaim("action",1,TOKEN,NOW,NOW))).isEqualTo(1);
        assertThat(state.confirm(request,2,TOKEN,receipt,"alice","  原始产物已确认  ",reasonAttempt("  原始产物已确认  "))).isTrue();
        assertThat(actions.selectById("action").getStatus()).isEqualTo("COMPLETE");
        assertThat(jdbc.queryForObject("SELECT reconciliation_reason FROM iqc_action_execution_event WHERE action_execution_id='action' AND version=3",String.class)).isEqualTo("原始产物已确认");
        assertThat(state.confirm(request,2,TOKEN,receipt,"alice","改写原因",reasonAttempt("改写原因"))).isFalse();
        assertThat(jdbc.queryForObject("SELECT reconciliation_reason FROM iqc_action_execution_event WHERE action_execution_id='action' AND version=3",String.class)).isEqualTo("原始产物已确认");
    }
    @Test void rejectedReasonWriteRollsBackSuccessAndItsNewJournalEvent() {
        var state=context.getBean(ActionReconciliationStateService.class);var request=reasonRequest();var receipt=reasonReceipt(request);
        assertThat(claim(TOKEN,0,NOW)).isEqualTo(1);
        assertThat(tx.<Integer>execute(status->actions.quarantineLostClaim("action",1,TOKEN,NOW,NOW))).isEqualTo(1);
        jdbc.execute("CREATE TRIGGER reject_reconciliation_reason BEFORE UPDATE ON iqc_action_execution_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='reason rejected'");
        try {
            assertThatThrownBy(()->state.confirm(request,2,TOKEN,receipt,"alice","人工核对",reasonAttempt("人工核对")))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(actions.selectById("action").getStatus()).isEqualTo("REQUIRES_RECONCILIATION");
            assertThat(actions.selectById("action").getVersion()).isEqualTo(2L);
            assertThat(eventCount()).isEqualTo(3);
        } finally {jdbc.execute("DROP TRIGGER reject_reconciliation_reason");}
    }
    @Test void changedFrozenInputCannotConfirmAndIndependentConfirmationSurvivesOuterRollback() {
        var state=context.getBean(ActionReconciliationStateService.class);var request=reasonRequest();var receipt=reasonReceipt(request);
        assertThat(claim(TOKEN,0,NOW)).isEqualTo(1);
        assertThat(tx.<Integer>execute(status->actions.quarantineLostClaim("action",1,TOKEN,NOW,NOW))).isEqualTo(1);
        jdbc.update("UPDATE iqc_action_execution SET input_snapshot_json='{\"changed\":true}' WHERE id='action'");
        assertThat(state.confirm(request,2,TOKEN,receipt,"alice","人工核对",reasonAttempt("人工核对"))).isFalse();
        jdbc.update("UPDATE iqc_action_execution SET input_snapshot_json='{}' WHERE id='action'");
        assertThat(actions.selectById("action").getVersion()).isEqualTo(2L);assertThat(eventCount()).isEqualTo(3);
        assertThatThrownBy(()->tx.execute(status->{
            assertThat(state.confirm(request,2,TOKEN,receipt,"alice","独立核对",reasonAttempt("独立核对"))).isTrue();
            throw new IllegalStateException("outer abort");
        })).hasMessage("outer abort");
        assertThat(actions.selectById("action").getStatus()).isEqualTo("COMPLETE");
        var reader=new SqlSessionTemplate(context.getBean(SqlSessionFactory.class)).getMapper(ActionStateEventMapper.class);
        assertThat(reader.page("action","result",null).getFirst().getReconciliationReason()).isEqualTo("独立核对");
    }

}
