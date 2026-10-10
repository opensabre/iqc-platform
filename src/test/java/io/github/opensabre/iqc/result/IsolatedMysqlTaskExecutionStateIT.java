package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import io.github.opensabre.iqc.task.TaskExecutionStateService;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.dao.TaskItemMapper;
import io.github.opensabre.iqc.result.llm.gateway.GatewayAdmissionException;
import io.github.opensabre.iqc.result.llm.gateway.GatewayCallMapper;
import io.github.opensabre.iqc.result.llm.gateway.GatewayCapacityMapper;
import io.github.opensabre.iqc.result.llm.gateway.GatewayCallService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real InnoDB rows and Spring transactions; no worker, model, or shared application database. */
class IsolatedMysqlTaskExecutionStateIT {
    private static final String URL = "jdbc:mysql://127.0.0.1:34307/iqc_worker_state_validation"
            + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TaskExecutionStateService states;
    private static final String BUDGET_KEY = "a".repeat(64);
    private static final java.util.concurrent.atomic.AtomicReference<Runnable> BEFORE_ITEM_UPDATE =
            new java.util.concurrent.atomic.AtomicReference<>();

    @BeforeAll
    static void start() throws Exception {
        IsolatedMysqlMigrationIT.verifyIsolatedServer();
        try (var connection = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:34307/?useSSL=false&allowPublicKeyRetrieval=true", "root", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS iqc_worker_state_validation CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        Flyway.configure().dataSource(URL, "root", "").locations("classpath:db/migration/mysql")
                .baselineOnMigrate(false).cleanDisabled(true).load().migrate();
        context = new AnnotationConfigApplicationContext(DatabaseConfiguration.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        states = context.getBean(TaskExecutionStateService.class);
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(states)).isTrue();
    }

    @AfterAll
    static void close() {
        if (context != null) context.close();
    }

    @BeforeEach
    void fixture() {
        BEFORE_ITEM_UPDATE.set(null);
        jdbc.execute("DROP TRIGGER IF EXISTS reject_execution_state_write");
        jdbc.update("DELETE FROM iqc_model_gateway_call");
        jdbc.update("DELETE FROM iqc_task_item");
        jdbc.update("DELETE FROM iqc_task_execution");
        jdbc.update("DELETE FROM iqc_inspection_task");
        jdbc.update("""
                INSERT INTO iqc_inspection_task(id,name,status,current_execution_id,total_messages)
                VALUES ('worker-task','isolated worker','QUEUED','current-eid',3)
                """);
        jdbc.update("""
                INSERT INTO iqc_task_execution(id,task_id,attempt_no,status)
                VALUES ('current-eid','worker-task',2,'QUEUED'),('old-eid','worker-task',1,'QUEUED')
                """);
    }

    @Test
    void competingTransactionsGrantExactlyOneClaim() throws Exception {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> claim = () -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("claim barrier timed out");
                return states.claim("worker-task", "current-eid");
            };
            var first = workers.submit(claim);
            var second = workers.submit(claim);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            go.countDown();
        }
        assertStatus("RUNNING", "RUNNING");
        assertThat(executionStatus("old-eid")).isEqualTo("QUEUED");
    }

    @Test
    void staleExecutionCannotClaimFinishOrFailCurrentAttempt() {
        assertThat(states.claim("worker-task", "old-eid")).isFalse();
        assertThat(states.claim("worker-task", "current-eid")).isTrue();
        states.finish("worker-task", "old-eid", 3, 1);
        assertThat(states.fail("worker-task", "old-eid", true, "stale")).isFalse();
        assertStatus("RUNNING", "RUNNING");
        assertThat(executionStatus("old-eid")).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT processed_messages FROM iqc_inspection_task", Integer.class)).isZero();
    }

    @Test
    void executionWriteFailureRollsBackTaskClaim() {
        rejectExecutionWrite();
        assertThatThrownBy(() -> states.claim("worker-task", "current-eid"))
                .hasStackTraceContaining("isolated execution write rejection");
        assertStatus("QUEUED", "QUEUED");
        jdbc.execute("DROP TRIGGER reject_execution_state_write");
        assertThat(states.claim("worker-task", "current-eid")).isTrue();
        assertStatus("RUNNING", "RUNNING");
    }

    @Test
    void executionWriteFailureRollsBackFinishAndProgress() {
        assertThat(states.claim("worker-task", "current-eid")).isTrue();
        rejectExecutionWrite();
        assertThatThrownBy(() -> states.finish("worker-task", "current-eid", 3, 1))
                .hasStackTraceContaining("isolated execution write rejection");
        assertStatus("RUNNING", "RUNNING");
        assertThat(jdbc.queryForObject("SELECT processed_messages FROM iqc_inspection_task", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT failed_messages FROM iqc_inspection_task", Integer.class)).isZero();
        jdbc.execute("DROP TRIGGER reject_execution_state_write");
        states.finish("worker-task", "current-eid", 3, 1);
        assertStatus("PARTIAL_FAILED", "PARTIAL_FAILED");
    }

    @Test
    void cancellationWinsAndTerminalAttemptCannotBeOverwritten() {
        assertThat(states.claim("worker-task", "current-eid")).isTrue();
        jdbc.update("UPDATE iqc_inspection_task SET status='CANCEL_REQUESTED' WHERE id='worker-task'");
        states.finish("worker-task", "current-eid", 2, 0);
        assertStatus("CANCELLED", "CANCELLED");
        states.finish("worker-task", "current-eid", 3, 1);
        assertThat(states.fail("worker-task", "current-eid", true, "late failure")).isFalse();
        assertThat(states.claim("worker-task", "current-eid")).isFalse();
        assertStatus("CANCELLED", "CANCELLED");
        assertThat(jdbc.queryForObject("SELECT processed_messages FROM iqc_inspection_task", Integer.class)).isEqualTo(2);
    }

    @Test
    void independentJvmTransactionsGrantExactlyOneClaim() throws Exception {
        try (var first = new WorkerProcess("compete"); var second = new WorkerProcess("compete")) {
            assertThat(first.signal("READY:")).isEqualTo("READY:compete");
            assertThat(second.signal("READY:")).isEqualTo("READY:compete");
            first.release();
            second.release();
            assertThat(java.util.List.of(first.signal("CLAIMED:"), second.signal("CLAIMED:")))
                    .containsExactlyInAnyOrder("CLAIMED:true", "CLAIMED:false");
            first.requireCleanExit();
            second.requireCleanExit();
        }
        assertStatus("RUNNING", "RUNNING");
    }

    @Test
    void killedClaimingJvmLeavesDurableStateThatAnotherJvmRecovers() throws Exception {
        try (var crashed = new WorkerProcess("claim-hold")) {
            assertThat(crashed.signal("CLAIMED:")).isEqualTo("CLAIMED:true");
            assertStatus("RUNNING", "RUNNING");
            crashed.kill();
        }
        // Advance only this fixture's heartbeat past the timeout; no wall-clock sleeps or global clock changes.
        expireHeartbeat();
        try (var recovery = new WorkerProcess("recover")) {
            assertThat(recovery.signal("RECOVERED:")).isEqualTo("RECOVERED:1");
            recovery.requireCleanExit();
        }
        assertStatus("FAILED", "FAILED");
        assertThat(jdbc.queryForObject("SELECT error_message FROM iqc_task_execution WHERE id='current-eid'", String.class))
                .contains("超时");
        states.finish("worker-task", "current-eid", 3, 0);
        assertThat(states.fail("worker-task", "current-eid", true, "late callback")).isFalse();
        assertStatus("FAILED", "FAILED");
    }

    @Test
    void newJvmPreservesExpiredQueuedRowAndCanClaimIt() throws Exception {
        expireHeartbeat();
        try (var restarted = new WorkerProcess("recover-then-claim")) {
            assertThat(restarted.signal("RECOVERED:")).isEqualTo("RECOVERED:0");
            assertThat(restarted.signal("CLAIMED:")).isEqualTo("CLAIMED:true");
            restarted.requireCleanExit();
        }
        assertStatus("RUNNING", "RUNNING");
        assertThat(executionStatus("old-eid")).isEqualTo("QUEUED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"PAUSE_REQUESTED,PAUSED", "CANCEL_REQUESTED,CANCELLED"})
    void recoveryInAnotherJvmPreservesPendingControlRequest(String request, String expected) throws Exception {
        assertThat(states.claim("worker-task", "current-eid")).isTrue();
        jdbc.update("UPDATE iqc_inspection_task SET status=? WHERE id='worker-task'", request);
        expireHeartbeat();
        try (var recovery = new WorkerProcess("recover")) {
            assertThat(recovery.signal("RECOVERED:")).isEqualTo("RECOVERED:1");
            recovery.requireCleanExit();
        }
        assertStatus(expected, expected);
        assertThat(executionStatus("old-eid")).isEqualTo("QUEUED");
    }

    private static void expireHeartbeat() {
        jdbc.update("UPDATE iqc_inspection_task SET updated_time='2000-01-01 00:00:00' WHERE id='worker-task'");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "MODEL_TOKEN_BUDGET_EXHAUSTED", "MODEL_COST_BUDGET_EXHAUSTED", "MODEL_BUDGET_QUARANTINED",
            "MODEL_BUDGET_BOUND_UNVERIFIED", "MODEL_COST_BUDGET_PRICE_UNVERIFIED"})
    void validBudgetWaitPreservesCheckpointsAndCountsCarriedProgress(String reason) {
        budgetFixture();
        assertThat(states.waitForBudget("worker-task", "current-eid", new GatewayAdmissionException(reason, BUDGET_KEY)))
                .isTrue();
        assertStatus("PAUSED", "PAUSED");
        assertThat(itemStatus("working-item")).isEqualTo("PENDING");
        assertThat(itemStatus("waiting-item")).isEqualTo("PENDING");
        assertThat(itemStatus("done-item")).isEqualTo("SUCCEEDED");
        assertThat(itemStatus("failed-item")).isEqualTo("FAILED");
        assertThat(itemStatus("old-item")).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT result_id FROM iqc_task_item WHERE id='done-item'", String.class))
                .isEqualTo("frozen-result");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM iqc_task_item WHERE id='working-item'", Integer.class))
                .isEqualTo(1);
        assertProgress(4, 1); // Two terminal items plus two earlier checkpoints absent from this round's work list.
        assertThat(jdbc.queryForObject("SELECT model_budget_wait_code FROM iqc_inspection_task", String.class)).isEqualTo(reason);
        assertThat(jdbc.queryForObject("SELECT model_budget_wait_key FROM iqc_inspection_task", String.class)).isEqualTo(BUDGET_KEY);
        states.finish("worker-task", "current-eid", 6, 0);
        assertStatus("PAUSED", "PAUSED");
    }

    @Test
    void invalidOrUnboundAdmissionEvidenceCannotPauseExecution() {
        budgetFixture();
        for (GatewayAdmissionException reason : new GatewayAdmissionException[] {
                null, new GatewayAdmissionException("MODEL_TOKEN_BUDGET_EXHAUSTED"),
                new GatewayAdmissionException("MODEL_TOKEN_BUDGET_EXHAUSTED", "not-a-model-key"),
                new GatewayAdmissionException("MODEL_RATE_LIMITED", BUDGET_KEY),
                new GatewayAdmissionException("MODEL_CAPACITY_EXHAUSTED", BUDGET_KEY)}) {
            assertThat(states.waitForBudget("worker-task", "current-eid", reason)).isFalse();
        }
        assertStatus("RUNNING", "RUNNING");
        assertThat(itemStatus("working-item")).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT model_budget_wait_key FROM iqc_inspection_task", String.class)).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "PAUSE_REQUESTED,false,false", "CANCEL_REQUESTED,false,false", "RUNNING,true,false", "RUNNING,false,true"})
    void pendingControlRequestPreventsBudgetWait(String status, boolean pause, boolean cancel) {
        budgetFixture();
        jdbc.update("UPDATE iqc_inspection_task SET status=?,pause_requested=?,cancel_requested=? WHERE id='worker-task'",
                status, pause, cancel);
        assertThat(states.waitForBudget("worker-task", "current-eid", budgetReason())).isFalse();
        assertStatus(status, "RUNNING");
        assertThat(itemStatus("working-item")).isEqualTo("RUNNING");
    }

    @Test
    void oldExecutionCannotPauseCurrentRoundOrResetItsItems() {
        budgetFixture();
        assertThat(states.waitForBudget("worker-task", "old-eid", budgetReason())).isFalse();
        assertStatus("RUNNING", "RUNNING");
        assertThat(itemStatus("working-item")).isEqualTo("RUNNING");
        assertThat(itemStatus("old-item")).isEqualTo("RUNNING");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"current-eid", "old-eid"})
    void toolExposureInAnyRoundPreventsAutomaticBudgetWait(String executionId) {
        budgetFixture();
        toolCall("worker-task", executionId, true);
        assertThat(context.getBean(GatewayCallService.class).hasToolExposure("worker-task")).isTrue();
        assertThat(states.waitForBudget("worker-task", "current-eid", budgetReason())).isFalse();
        assertStatus("RUNNING", "RUNNING");
        assertThat(itemStatus("working-item")).isEqualTo("RUNNING");
    }

    @Test
    void unrelatedToolExposureAndUnstartedCurrentCallDoNotBlockBudgetWait() {
        budgetFixture();
        toolCall("another-task", "another-eid", true);
        toolCall("worker-task", "current-eid", false);
        assertThat(context.getBean(GatewayCallService.class).hasToolExposure("worker-task")).isFalse();
        assertThat(states.waitForBudget("worker-task", "current-eid", budgetReason())).isTrue();
        assertStatus("PAUSED", "PAUSED");
    }

    @Test
    void executionWriteFailureRollsBackBudgetPauseItemResetAndProgress() {
        budgetFixture();
        rejectExecutionWrite();
        assertThatThrownBy(() -> states.waitForBudget("worker-task", "current-eid", budgetReason()))
                .hasStackTraceContaining("isolated execution write rejection");
        assertStatus("RUNNING", "RUNNING");
        assertThat(itemStatus("working-item")).isEqualTo("RUNNING");
        assertProgress(0, 0);
        assertThat(jdbc.queryForObject("SELECT model_budget_wait_code FROM iqc_inspection_task", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT model_budget_wait_key FROM iqc_inspection_task", String.class)).isNull();
        jdbc.execute("DROP TRIGGER reject_execution_state_write");
        assertThat(states.waitForBudget("worker-task", "current-eid", budgetReason())).isTrue();
        assertStatus("PAUSED", "PAUSED");
        assertThat(itemStatus("working-item")).isEqualTo("PENDING");
        assertProgress(4, 1);
    }

    /** Two completed prior checkpoints are represented by total_messages minus this round's work list. */
    private static void budgetFixture() {
        assertThat(states.claim("worker-task", "current-eid")).isTrue();
        jdbc.update("UPDATE iqc_inspection_task SET total_messages=6 WHERE id='worker-task'");
        jdbc.update("""
                INSERT INTO iqc_task_item(id,task_id,execution_id,message_id,sequence_no,status,result_id,attempt_count)
                VALUES ('done-item','worker-task','current-eid','done-message',1,'SUCCEEDED','frozen-result',1),
                  ('failed-item','worker-task','current-eid','failed-message',2,'FAILED',NULL,1),
                  ('working-item','worker-task','current-eid','working-message',3,'RUNNING',NULL,1),
                  ('waiting-item','worker-task','current-eid','waiting-message',4,'PENDING',NULL,0),
                  ('old-item','worker-task','old-eid','old-message',1,'RUNNING',NULL,1)
                """);
    }

    private static GatewayAdmissionException budgetReason() {
        return new GatewayAdmissionException("MODEL_TOKEN_BUDGET_EXHAUSTED", BUDGET_KEY);
    }

    private static String itemStatus(String id) {
        return jdbc.queryForObject("SELECT status FROM iqc_task_item WHERE id=?", String.class, id);
    }

    private static void assertProgress(int processed, int failed) {
        for (String table : new String[]{"iqc_inspection_task", "iqc_task_execution"}) {
            String filter = table.equals("iqc_task_execution") ? " WHERE id='current-eid'" : " WHERE id='worker-task'";
            assertThat(jdbc.queryForObject("SELECT processed_messages FROM " + table + filter, Integer.class)).isEqualTo(processed);
            assertThat(jdbc.queryForObject("SELECT failed_messages FROM " + table + filter, Integer.class)).isEqualTo(failed);
        }
    }

    private static void toolCall(String taskId, String executionId, boolean started) {
        jdbc.update("""
                INSERT INTO iqc_model_gateway_call(call_id,model_key,task_id,execution_id,status,reserved_at_utc,tool_execution_started_at_utc)
                VALUES (?, ?, ?, ?, 'CONFIRMED', '2026-10-08 00:00:00', ?)
                """, "call-" + taskId, BUDGET_KEY, taskId, executionId,
                started ? java.sql.Timestamp.valueOf("2026-10-08 00:00:01") : null);
    }

    @Test
    void budgetPauseWinningTaskLockRejectsConcurrentToolStart() throws Exception {
        budgetFixture();
        var gateway = context.getBean(GatewayCallService.class);
        var source = toolSource();
        var checkedExposure = new CountDownLatch(1);
        var releasePause = new CountDownLatch(1);
        // This hook pauses before actual item SQL, after real exposure lookup while Task/Execution locks are held.
        BEFORE_ITEM_UPDATE.set(() -> {
            checkedExposure.countDown();
            await(releasePause);
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var pause = workers.submit(() -> states.waitForBudget("worker-task", "current-eid", budgetReason()));
            java.util.concurrent.Future<String> tools = null;
            try {
                assertThat(checkedExposure.await(10, TimeUnit.SECONDS)).isTrue();
                tools = workers.submit(() -> {
                    try {
                        gateway.markToolsStarted(new GatewayCallService.Handle("call-worker-task", BUDGET_KEY), source);
                        return "STARTED";
                    } catch (GatewayAdmissionException stale) { return stale.getMessage(); }
                });
                awaitToolCompletionOrTaskLockWait(tools);
            } finally {
                releasePause.countDown();
            }
            assertThat(pause.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(tools).isNotNull();
            assertThat(tools.get(10, TimeUnit.SECONDS)).isEqualTo("MODEL_EXECUTION_PROVENANCE_STALE");
        } finally {
            releasePause.countDown();
            BEFORE_ITEM_UPDATE.set(null);
        }
        assertStatus("PAUSED", "PAUSED");
        assertThat(jdbc.queryForObject("SELECT tool_execution_started_at_utc FROM iqc_model_gateway_call WHERE call_id='call-worker-task'",
                java.sql.Timestamp.class)).isNull();
    }

    @Test
    void actualToolStartWinningFirstPreventsBudgetPause() {
        budgetFixture();
        var source = toolSource();
        context.getBean(GatewayCallService.class).markToolsStarted(
                new GatewayCallService.Handle("call-worker-task", BUDGET_KEY), source);
        assertThat(states.waitForBudget("worker-task", "current-eid", budgetReason())).isFalse();
        assertStatus("RUNNING", "RUNNING");
        assertThat(itemStatus("working-item")).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT tool_execution_started_at_utc FROM iqc_model_gateway_call WHERE call_id='call-worker-task'",
                java.sql.Timestamp.class)).isNotNull();
    }

    private static GatewayCallService.Context toolSource() {
        jdbc.update("UPDATE iqc_inspection_task SET created_by='alice' WHERE id='worker-task'");
        toolCall("worker-task", "current-eid", false);
        return new GatewayCallService.Context(null, null, "alice",
                new io.github.opensabre.iqc.result.llm.ModelCallContext(
                        "worker-task", "current-eid", "tool-node", 0, "alice", null, "conversation-1"));
    }

    /** Observe real InnoDB contention instead of relying on a thread sleep to make the race deterministic. */
    private static void awaitToolCompletionOrTaskLockWait(java.util.concurrent.Future<String> tools) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!tools.isDone()) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                    WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='iqc_inspection_task'
                    """, Integer.class);
            if (waiting != null && waiting > 0) return;
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Tool transaction neither finished nor waited for Task lock");
            Thread.sleep(10);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Budget pause barrier timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** Bounded child-process protocol; every child has its own Spring context and database connections. */
    private static final class WorkerProcess implements AutoCloseable {
        private final Process process;
        private final BufferedReader output;

        WorkerProcess(String command) throws Exception {
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", classpath, ProcessMain.class.getName(), command).redirectErrorStream(true).start();
            output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        }

        String signal(String prefix) throws Exception {
            return CompletableFuture.supplyAsync(() -> {
                var messages = new StringBuilder();
                try {
                    for (String line; (line = output.readLine()) != null;) {
                        messages.append(line).append('\n');
                        if (line.startsWith(prefix)) return line;
                    }
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                throw new IllegalStateException("Child exited without " + prefix + ":\n" + messages);
            }).get(30, TimeUnit.SECONDS);
        }

        void release() throws Exception {
            process.getOutputStream().write(1);
            process.getOutputStream().flush();
        }

        void requireCleanExit() throws Exception {
            assertThat(process.onExit().get(10, TimeUnit.SECONDS).exitValue()).isZero();
        }

        void kill() throws Exception {
            process.destroyForcibly();
            process.onExit().get(10, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            if (process.isAlive()) kill();
            output.close();
            process.getOutputStream().close();
        }
    }

    /** Entry point for isolated process boundaries; never starts the application scheduler or model clients. */
    public static final class ProcessMain {
        public static void main(String[] arguments) throws Exception {
            IsolatedMysqlMigrationIT.verifyIsolatedServer();
            try (var child = new AnnotationConfigApplicationContext(DatabaseConfiguration.class)) {
                var state = child.getBean(TaskExecutionStateService.class);
                String command = arguments[0];
                if (command.startsWith("recover")) {
                    int count = child.getBean(InspectionTaskService.class).recoverStaleExecutions(Duration.ofMinutes(1), false);
                    System.out.println("RECOVERED:" + count);
                    if (command.equals("recover-then-claim")) {
                        System.out.println("CLAIMED:" + state.claim("worker-task", "current-eid"));
                    }
                } else if (command.equals("compete")) {
                    System.out.println("READY:compete");
                    System.out.flush();
                    if (System.in.read() < 0) throw new IllegalStateException("Parent closed claim barrier");
                    System.out.println("CLAIMED:" + state.claim("worker-task", "current-eid"));
                } else if (command.equals("claim-hold")) {
                    System.out.println("CLAIMED:" + state.claim("worker-task", "current-eid"));
                    System.out.flush();
                    System.in.read(); // The parent forcibly terminates this JVM after the committed claim.
                } else throw new IllegalArgumentException("Unknown isolated process command");
                System.out.flush();
            }
        }
    }

    /** Inject a real second-row database error after the service has updated the Task row. */
    private static void rejectExecutionWrite() {
        jdbc.execute("""
                CREATE TRIGGER reject_execution_state_write BEFORE UPDATE ON iqc_task_execution
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated execution write rejection'
                """);
    }

    private static String executionStatus(String id) {
        return jdbc.queryForObject("SELECT status FROM iqc_task_execution WHERE id=?", String.class, id);
    }

    private static void assertStatus(String task, String execution) {
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_inspection_task WHERE id='worker-task'", String.class))
                .isEqualTo(task);
        assertThat(executionStatus("current-eid")).isEqualTo(execution);
    }

    /** Reuse production mappers and annotation-driven transaction boundaries without booting external clients. */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class DatabaseConfiguration {
        @Bean
        DataSource dataSource() { return new DriverManagerDataSource(URL, "root", ""); }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(InspectionTaskMapper.class);
            configuration.addMapper(TaskExecutionMapper.class);
            configuration.addMapper(TaskItemMapper.class);
            configuration.addMapper(GatewayCallMapper.class);
            configuration.addMapper(GatewayCapacityMapper.class);
            configuration.addMapper(io.github.opensabre.iqc.schedule.dao.TriggerAttemptEventMapper.class);
            configuration.addMapper(io.github.opensabre.iqc.action.ActionExecutionMapper.class);
            configuration.addMapper(io.github.opensabre.iqc.action.ActionStateEventMapper.class);
            configuration.addMapper(io.github.opensabre.iqc.action.ActionReconciliationEventMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }

        @Bean
        io.github.opensabre.iqc.action.ActionReconciliationStateService actionReconciliationStateService(SqlSessionFactory factory) {
            var session=new SqlSessionTemplate(factory);
            return new io.github.opensabre.iqc.action.ActionReconciliationStateService(
                    session.getMapper(io.github.opensabre.iqc.action.ActionExecutionMapper.class),
                    session.getMapper(io.github.opensabre.iqc.action.ActionReconciliationEventMapper.class));
        }

        @Bean
        TaskExecutionStateService taskExecutionStateService(SqlSessionFactory factory,
                org.springframework.beans.factory.ObjectProvider<GatewayCallService> gateway) {
            var session = new SqlSessionTemplate(factory);
            var actualItems = session.getMapper(TaskItemMapper.class);
            // Intercept only the scheduling seam; every mapper result and SQL write remains real.
            var items = (TaskItemMapper) java.lang.reflect.Proxy.newProxyInstance(TaskItemMapper.class.getClassLoader(),
                    new Class<?>[]{TaskItemMapper.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("update")) {
                            var hook = BEFORE_ITEM_UPDATE.getAndSet(null);
                            if (hook != null) hook.run();
                        }
                        try { return method.invoke(actualItems, arguments); }
                        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    });
            return new TaskExecutionStateService(session.getMapper(InspectionTaskMapper.class),
                    session.getMapper(TaskExecutionMapper.class), items, gateway);
        }

        @Bean
        GatewayCallService gatewayCallService(SqlSessionFactory factory,
                org.springframework.beans.factory.ObjectProvider<InspectionTaskService> tasks) {
            var session = new SqlSessionTemplate(factory);
            // Budget waiting only queries persisted exposure; no model, scope, or actor operation is invoked.
            return new GatewayCallService(session.getMapper(GatewayCapacityMapper.class), session.getMapper(GatewayCallMapper.class),
                    session.getMapper(InspectionTaskMapper.class), session.getMapper(TaskExecutionMapper.class), null, null, tasks);
        }

        @Bean
        InspectionTaskService inspectionTaskService(SqlSessionFactory factory) {
            var session = new SqlSessionTemplate(factory);
            // Recovery only uses these two real mappers; unrelated creation clients are deliberately absent.
            return new InspectionTaskService(session.getMapper(InspectionTaskMapper.class), null, null, null, null,
                    new com.fasterxml.jackson.databind.ObjectMapper(), session.getMapper(TaskExecutionMapper.class), null, null);
        }
    }
}
