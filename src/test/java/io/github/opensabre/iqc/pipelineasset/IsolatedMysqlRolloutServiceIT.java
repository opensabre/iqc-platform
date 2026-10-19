package io.github.opensabre.iqc.pipelineasset;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.pipelineasset.dao.*;
import io.github.opensabre.iqc.result.IsolatedMysqlMigrationIT;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.quality.evaluation.BenchmarkVariableComparator;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real asset locking, immutable revision mapper and Spring transactions; publication resolution is isolated. */
class IsolatedMysqlRolloutServiceIT {
    private static final String SERVER="jdbc:mysql://127.0.0.1:34307/";
    private static final String OPTIONS="?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
    private static final String SCHEMA="iqc_rollout_service_"+Long.toHexString(System.nanoTime());
    private static AnnotationConfigApplicationContext context;
    private static boolean created;
    private JdbcTemplate jdbc;
    private PipelineRolloutService service;
    private String id;
    @BeforeAll static void start() throws Exception {
        IsolatedMysqlMigrationIT.verifyIsolatedServer();
        try(var db=DriverManager.getConnection(SERVER+OPTIONS,"root","");var sql=db.createStatement()) {
            sql.execute("CREATE DATABASE "+SCHEMA+" CHARACTER SET utf8mb4");created=true;
        }
        var flyway=Flyway.configure().dataSource(SERVER+SCHEMA+OPTIONS,"root","")
                .locations("classpath:db/migration/mysql").cleanDisabled(true).load();
        flyway.migrate();flyway.validate();assertThat(flyway.migrate().migrationsExecuted).isZero();
        context=new AnnotationConfigApplicationContext(Config.class);
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(PipelineRolloutService.class))).isTrue();
    }
    @AfterAll static void close() throws Exception {
        if(context!=null)context.close();
        if(created) {
            IsolatedMysqlMigrationIT.verifyIsolatedServer();
            try(var db=DriverManager.getConnection(SERVER+OPTIONS,"root","");var sql=db.createStatement()) {sql.execute("DROP DATABASE "+SCHEMA);}
        }
    }
    @BeforeEach void fixture() {
        jdbc=new JdbcTemplate(context.getBean(DataSource.class));service=context.getBean(PipelineRolloutService.class);
        id="p"+UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO iqc_pipeline_asset(id,code,name,draft_config_json,status) VALUES(?,?,?,'{}','ACTIVE')",id,id,id);
    }
    private PipelineRolloutPolicy policy(int ratio) {
        return new PipelineRolloutPolicy(PipelineRolloutPolicy.SCHEMA,id,1,2,ratio,"salt_0001",PipelineRolloutPolicy.State.ACTIVE,
                new BenchmarkVariableComparator.Selection(BenchmarkVariableComparator.Dimension.PIPELINE,null,null));
    }
    @Test void concurrentExpectedRevisionHasOneSuccessAndOneDomainCasRejection() throws Exception {
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            Callable<String> append=()->{ready.countDown();if(!go.await(10,TimeUnit.SECONDS))throw new IllegalStateException("barrier");
                try {service.append(id,0,policy(5000));return "created";}
                catch(io.github.opensabre.iqc.governance.IqcException e) {return e.getMessage();}};
            var first=workers.submit(append);var second=workers.submit(append);
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();
            assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("created","灰度策略修订已变化，请重新读取");
        } finally {go.countDown();}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM iqc_pipeline_rollout_revision WHERE pipeline_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(service.current(id).revisionNo()).isEqualTo(1);
    }
    @Test void lockingReadSeesCommittedRevisionDespiteOuterRepeatableReadSnapshot() throws Exception {
        var first=service.append(id,0,policy(1000));
        var tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.executeWithoutResult(status->{
            assertThat(service.current(id).revisionNo()).isEqualTo(1);
            try(var worker=Executors.newSingleThreadExecutor()) {
                try {assertThat(worker.submit(()->service.append(id,1,policy(2000))).get(20,TimeUnit.SECONDS).revisionNo()).isEqualTo(2);}
                catch(Exception e) {throw new IllegalStateException(e);}
            }
            // Consistent reads retain the old snapshot; append must use a locking/current read.
            assertThat(service.current(id).revisionNo()).isEqualTo(1);
            assertThatThrownBy(()->service.append(id,1,policy(3000))).hasMessageContaining("修订已变化");
            status.setRollbackOnly();
        });
        assertThat(service.current(id).revisionNo()).isEqualTo(2);
        assertThat(service.history(id,null,10).get(1).contentHash()).isEqualTo(first.contentHash());
    }
    @Test void outerRollbackRemovesRevisionAndAllowsSameExpectedRevisionAgain() {
        var tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        tx.executeWithoutResult(status->{assertThat(service.append(id,0,policy(1000)).revisionNo()).isEqualTo(1);status.setRollbackOnly();});
        assertThat(service.current(id).revisionNo()).isZero();
        assertThat(service.append(id,0,policy(2000)).revisionNo()).isEqualTo(1);
    }
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource dataSource(){return new DriverManagerDataSource(SERVER+SCHEMA+OPTIONS,"root","");}
        @Bean DataSourceTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean ObjectMapper json(){return new ObjectMapper();}
        @Bean IqcDataScope scope(){var scope=mock(IqcDataScope.class);when(scope.actorUserId()).thenReturn("actor-1");when(scope.canView(any(),any())).thenReturn(true);return scope;}
        @Bean SqlSessionFactory sessions(DataSource ds) throws Exception {
            var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
            config.addMapper(PipelineAssetMapper.class);config.addMapper(PipelineRolloutRevisionMapper.class);
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);return factory.getObject();
        }
        @Bean PipelineAssetService pipelines(SqlSessionFactory factory,ObjectMapper json,IqcDataScope scope) {
            var assets=new SqlSessionTemplate(factory).getMapper(PipelineAssetMapper.class);
            var pipelines=spy(new PipelineAssetService(assets,null,null,null,null,null,null,null,json,scope,null,null,null,null));
            doAnswer(call->{String id=call.getArgument(0);var left=release(json,0);var right=release(json,1);
                return new PipelineAssetService.RolloutSources(
                    new PipelineAssetService.Prepared(id,1,null,left,PipelineAssetService.hash(json.valueToTree(left).toString())),
                    new PipelineAssetService.Prepared(id,2,null,right,PipelineAssetService.hash(json.valueToTree(right).toString())));
            }).when(pipelines).rolloutSourcesForTask(anyString(),any());return pipelines;
        }
        @Bean PipelineRolloutService rollouts(PipelineAssetService pipelines,SqlSessionFactory factory,ObjectMapper json,IqcDataScope scope) {
            return new PipelineRolloutService(pipelines,new SqlSessionTemplate(factory).getMapper(PipelineRolloutRevisionMapper.class),json,scope);
        }
        private static PipelineRelease release(ObjectMapper json,int retries) {
            var node=new io.github.opensabre.iqc.pipeline.PipelineDefinition.Node("rule","RULE","Rule","rule-v1",List.of(),Map.of(),json.createObjectNode(),null,3000,retries,
                io.github.opensabre.iqc.pipeline.PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
            return new PipelineRelease(PipelineRelease.SCHEMA,new io.github.opensabre.iqc.pipeline.PipelineDefinition(io.github.opensabre.iqc.pipeline.PipelineDefinition.SCHEMA,List.of(node)),
                new io.github.opensabre.iqc.fusion.FusionPolicy("fusion",1,io.github.opensabre.iqc.fusion.FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),
                new io.github.opensabre.iqc.scoring.InspectionScoring.Policy(io.github.opensabre.iqc.scoring.InspectionScoring.POLICY_VERSION,io.github.opensabre.iqc.scoring.InspectionScoring.Mode.DEDUCTION,100,60,List.of()),List.of());
        }
    }
}
