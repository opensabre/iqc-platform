package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import io.github.opensabre.iqc.task.TaskExecutionStateService;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
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

    @BeforeAll
    static void start() throws Exception {
        IsolatedMysqlMigrationIT.verifyIsolatedServer();
        try (var connection = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:34307/?useSSL=false&allowPublicKeyRetrieval=true", "root", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE iqc_worker_state_validation CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
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
        jdbc.execute("DROP TRIGGER IF EXISTS reject_execution_state_write");
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
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }

        @Bean
        TaskExecutionStateService taskExecutionStateService(SqlSessionFactory factory) {
            var session = new SqlSessionTemplate(factory);
            return new TaskExecutionStateService(session.getMapper(InspectionTaskMapper.class),
                    session.getMapper(TaskExecutionMapper.class));
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
