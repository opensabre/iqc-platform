package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import io.github.opensabre.iqc.task.TaskExecutionStateService;
import io.github.opensabre.iqc.task.dao.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Fresh isolated schemas, real Flyway/MyBatis/Spring transactions; never application credentials. */
class IsolatedMysqlTaskMetricsIT {
    static final String SERVER="jdbc:mysql://127.0.0.1:34307/";
    static final String OPTIONS="?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
    static String url;
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate jdbc;
    static TaskExecutionStateService states;
    static InspectionTaskMapper tasks;
    static String createSchema(String prefix) throws Exception {
        IsolatedMysqlMigrationIT.verifyIsolatedServer();
        String schema=prefix+UUID.randomUUID().toString().replace("-","");
        try(var connection=java.sql.DriverManager.getConnection(SERVER+OPTIONS,"root","");var statement=connection.createStatement()){
            statement.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        return SERVER+schema+OPTIONS;
    }
    static Flyway flyway(String targetUrl,String target){
        var config=Flyway.configure().dataSource(targetUrl,"root","").locations("classpath:db/migration/mysql").baselineOnMigrate(false).cleanDisabled(true);
        if(target!=null)config.target(target);return config.load();
    }
    @BeforeAll static void start() throws Exception {
        url=createSchema("iqc_task_metrics_");var migrations=flyway(url,null);
        assertThat(migrations.migrate().migrationsExecuted).isPositive();migrations.validate();
        assertThat(migrations.migrate().migrationsExecuted).isZero();
        context=new AnnotationConfigApplicationContext(Database.class);jdbc=new JdbcTemplate(context.getBean(DataSource.class));
        states=context.getBean(TaskExecutionStateService.class);tasks=new SqlSessionTemplate(context.getBean(SqlSessionFactory.class)).getMapper(InspectionTaskMapper.class);
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(states)).isTrue();
    }
    @AfterAll static void close(){if(context!=null)context.close();}
    @BeforeEach void clearOwnedSchema(){jdbc.update("DELETE FROM iqc_task_execution");jdbc.update("DELETE FROM iqc_inspection_task");}
    String seed(String taskStatus,String executionStatus){
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO iqc_inspection_task(id,name,status,current_execution_id,total_messages) VALUES (?, 'metrics validation', ?, ?,3)",id,taskStatus,id);
        jdbc.update("INSERT INTO iqc_task_execution(id,task_id,attempt_no,status) VALUES (?,?,1,?)",id,id,executionStatus);return id;
    }
    LocalDateTime completed(String id){return jdbc.queryForObject("SELECT completed_at_utc FROM iqc_task_execution WHERE id=?",LocalDateTime.class,id);}
    @Test void upgradePreservesUnmeasuredHistoricalRowsAndNewColumnAndIndexContract() throws Exception {
        String upgradeUrl=createSchema("iqc_task_metrics_upgrade_");flyway(upgradeUrl,"1.1.75").migrate();
        var db=new JdbcTemplate(new DriverManagerDataSource(upgradeUrl,"root",""));
        db.update("INSERT INTO iqc_inspection_task(id,name,status,current_execution_id) VALUES ('history','old','SUCCEEDED','history')");
        db.update("INSERT INTO iqc_task_execution(id,task_id,attempt_no,status) VALUES ('history','history',1,'SUCCEEDED')");
        var latest=flyway(upgradeUrl,null);assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);latest.validate();
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(db.queryForObject("SELECT completed_at_utc IS NULL FROM iqc_task_execution WHERE id='history'",Boolean.class)).isTrue();
        assertThat(db.queryForMap("SELECT DATA_TYPE,IS_NULLABLE,DATETIME_PRECISION FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='iqc_task_execution' AND column_name='completed_at_utc'"))
                .containsEntry("DATA_TYPE","datetime").containsEntry("IS_NULLABLE","YES");
        assertThat(db.queryForObject("SELECT DATETIME_PRECISION FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='iqc_task_execution' AND column_name='completed_at_utc'",Integer.class)).isEqualTo(3);
        assertThat(db.queryForList("SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='iqc_task_execution' AND index_name='idx_iqc_execution_completed_utc' ORDER BY seq_in_index",String.class))
                .containsExactly("completed_at_utc","status","task_id");
    }
    @Test void firstSuccessfulFinishRecordsUtcAndDuplicateOrOldExecutionCannotRewriteIt(){
        String id=seed("QUEUED","QUEUED");assertThat(completed(id)).isNull();assertThat(states.claim(id,id)).isTrue();
        var before=LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);states.finish(id,id,3,0);var first=completed(id);
        assertThat(first).isBetween(before,LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1));
        states.finish(id,id,3,1);assertThat(states.fail(id,id,true,"duplicate")).isFalse();states.finish(id,"old-execution",3,0);
        assertThat(completed(id)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_task_execution WHERE id=?",String.class,id)).isEqualTo("SUCCEEDED");
    }
    @Test void failureAndCancellationAreTerminalButPauseHasNoCompletionTimeUntilResume(){
        String failed=seed("RUNNING","RUNNING");assertThat(states.fail(failed,failed,true,"failure")).isTrue();assertThat(completed(failed)).isNotNull();
        String cancelled=seed("CANCEL_REQUESTED","RUNNING");states.finish(cancelled,cancelled,1,0);assertThat(completed(cancelled)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_task_execution WHERE id=?",String.class,cancelled)).isEqualTo("CANCELLED");
        String paused=seed("PAUSE_REQUESTED","RUNNING");states.finish(paused,paused,1,0);assertThat(completed(paused)).isNull();
        jdbc.update("UPDATE iqc_inspection_task SET status='QUEUED',model_budget_wait_code=NULL WHERE id=?",paused);
        jdbc.update("UPDATE iqc_task_execution SET status='QUEUED' WHERE id=?",paused);
        assertThat(states.claim(paused,paused)).isTrue();states.finish(paused,paused,3,0);assertThat(completed(paused)).isNotNull();
    }
    @Test void failedExecutionWriteRollsBackTaskStateAndCompletionTime(){
        String id=seed("RUNNING","RUNNING");
        jdbc.execute("CREATE TRIGGER reject_metrics_completion BEFORE UPDATE ON iqc_task_execution FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test execution rejection'");
        try{assertThatThrownBy(()->states.finish(id,id,3,0)).isInstanceOf(RuntimeException.class);}
        finally{jdbc.execute("DROP TRIGGER reject_metrics_completion");}
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_inspection_task WHERE id=?",String.class,id)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT status FROM iqc_task_execution WHERE id=?",String.class,id)).isEqualTo("RUNNING");assertThat(completed(id)).isNull();
    }
    @Test void operationalQueryDistinguishesModesBudgetWaitWindowAndHistoricalUnknowns(){
        String budget=seed("PAUSED","PAUSED");jdbc.update("UPDATE iqc_inspection_task SET model_budget_wait_code='MODEL_BUDGET_EXHAUSTED' WHERE id=?",budget);
        String eval=seed("RUNNING","RUNNING");jdbc.update("UPDATE iqc_inspection_task SET task_type='PIPELINE_EVALUATION' WHERE id=?",eval);
        String recent=seed("SUCCEEDED","SUCCEEDED");jdbc.update("UPDATE iqc_task_execution SET completed_at_utc=UTC_TIMESTAMP(3)-INTERVAL 1 MINUTE WHERE id=?",recent);
        String old=seed("FAILED","FAILED");jdbc.update("UPDATE iqc_task_execution SET completed_at_utc=UTC_TIMESTAMP(3)-INTERVAL 6 MINUTE WHERE id=?",old);
        String future=seed("CANCELLED","CANCELLED");jdbc.update("UPDATE iqc_task_execution SET completed_at_utc=UTC_TIMESTAMP(3)+INTERVAL 1 MINUTE WHERE id=?",future);
        String unknown=seed("PARTIAL_FAILED","PARTIAL_FAILED");
        var counts=tasks.operationalCounts();
        assertThat(counts).contains(new InspectionTaskMapper.OperationalCount("PRODUCTION","TASK_STATE","BUDGET_WAIT",1));
        assertThat(counts).contains(new InspectionTaskMapper.OperationalCount("EVALUATION","TASK_STATE","RUNNING",1));
        // This test owns its newly created schema; exact counts exclude old and future timestamps.
        assertThat(counts).contains(new InspectionTaskMapper.OperationalCount("PRODUCTION","UNMEASURED_TERMINAL","PARTIAL_FAILED",1));
        assertThat(counts.stream().filter(r->r.measure().equals("COMPLETED_5M")&&r.status().equals("FAILED")).mapToLong(InspectionTaskMapper.OperationalCount::countValue).sum()).isZero();
        assertThat(counts.stream().filter(r->r.measure().equals("COMPLETED_5M")&&r.status().equals("CANCELLED")).mapToLong(InspectionTaskMapper.OperationalCount::countValue).sum()).isZero();
        assertThat(completed(unknown)).isNull();
        assertThat(counts.stream().filter(r->r.measure().equals("COMPLETED_5M")&&r.status().equals("SUCCEEDED")).mapToLong(InspectionTaskMapper.OperationalCount::countValue).sum()).isEqualTo(1);
    }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Database {
        @Bean DataSource source(){return new DriverManagerDataSource(url,"root","");}
        @Bean DataSourceTransactionManager transactionManager(DataSource source){return new DataSourceTransactionManager(source);}
        @Bean SqlSessionFactory sessions(DataSource source) throws Exception {
            var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(InspectionTaskMapper.class);configuration.addMapper(TaskExecutionMapper.class);
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(configuration);return factory.getObject();
        }
        @Bean TaskExecutionStateService states(SqlSessionFactory sessions){var session=new SqlSessionTemplate(sessions);return new TaskExecutionStateService(session.getMapper(InspectionTaskMapper.class),session.getMapper(TaskExecutionMapper.class));}
    }
}
