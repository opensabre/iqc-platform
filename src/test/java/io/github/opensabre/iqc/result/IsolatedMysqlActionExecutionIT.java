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
        jdbc.update("DELETE FROM iqc_action_execution");
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
        assertThat(row.getClaimToken()).isNull(); assertThat(claim(TOKEN, 0, NOW)).isEqualTo(1);
    }
    @Test void databaseRejectsDuplicateStableIdempotencyKey() {
        var row = actions.selectById("action"); row.setId("duplicate");
        assertThatThrownBy(() -> actions.insert(row)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_action_execution", Integer.class)).isEqualTo(1);
    }
}
