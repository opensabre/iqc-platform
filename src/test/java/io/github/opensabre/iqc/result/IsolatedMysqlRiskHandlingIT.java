package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import io.github.opensabre.iqc.risk.*;
import io.github.opensabre.iqc.shared.IqcDataScope;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual MySQL handling CAS/history and Spring transactions; authorized historical views are substitutes. */
class IsolatedMysqlRiskHandlingIT {
    private static final String SERVER="jdbc:mysql://127.0.0.1:34307/";
    private static final String OPTIONS="?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
    private static final String SCHEMA="iqc_risk_handling_it_"+UUID.randomUUID().toString().replace("-","");
    private static final String URL=SERVER+SCHEMA+OPTIONS;
    private static AnnotationConfigApplicationContext context;
    private static boolean created;
    private JdbcTemplate jdbc;
    private RiskHandlingService service;
    private RiskEventQueryService queries;
    private String id;

    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement
    static class DatabaseConfiguration {
        @Bean DataSource dataSource(){return new DriverManagerDataSource(URL,"root","");}
        @Bean DataSourceTransactionManager transactionManager(DataSource source){return new DataSourceTransactionManager(source);}
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
            config.addMapper(RiskEventMapper.class);config.addMapper(RiskHandlingEventMapper.class);
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);return factory.getObject();
        }
        @Bean RiskEventMapper events(SqlSessionFactory factory){return new SqlSessionTemplate(factory).getMapper(RiskEventMapper.class);}
        @Bean RiskHandlingEventMapper history(SqlSessionFactory factory){return new SqlSessionTemplate(factory).getMapper(RiskHandlingEventMapper.class);}
        @Bean RiskEventQueryService queries(){return mock(RiskEventQueryService.class);}
        @Bean IqcDataScope scope(){var scope=mock(IqcDataScope.class);when(scope.owner()).thenReturn("alice");return scope;}
        @Bean RiskHandlingService handling(RiskEventQueryService queries,RiskEventMapper events,RiskHandlingEventMapper history,IqcDataScope scope){return new RiskHandlingService(queries,events,history,scope);}
    }
    @BeforeAll static void start() throws Exception {
        IsolatedMysqlMigrationIT.verifyIsolatedServer();
        try(var db=DriverManager.getConnection(SERVER+OPTIONS,"root","");var sql=db.createStatement()){
            sql.execute("CREATE DATABASE `"+SCHEMA+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");created=true;
        }
        var flyway=Flyway.configure().dataSource(URL,"root","").locations("classpath:db/migration/mysql").cleanDisabled(true).load();
        flyway.migrate();flyway.validate();assertEquals(0,flyway.migrate().migrationsExecuted);
        context=new AnnotationConfigApplicationContext(DatabaseConfiguration.class);
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(RiskHandlingService.class)));
    }
    @AfterAll static void close() throws Exception {
        if(context!=null)context.close();
        if(created){IsolatedMysqlMigrationIT.verifyIsolatedServer();try(var db=DriverManager.getConnection(SERVER+OPTIONS,"root","");var sql=db.createStatement()){sql.execute("DROP DATABASE `"+SCHEMA+"`");}}
    }
    @BeforeEach void fixture(){
        jdbc=new JdbcTemplate(context.getBean(DataSource.class));service=context.getBean(RiskHandlingService.class);
        queries=context.getBean(RiskEventQueryService.class);reset(queries);
        String key=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
        id="risk-"+key.substring(0,59);
        jdbc.update("INSERT INTO iqc_risk_event(id,source_action_execution_id,source_result_id,source_input_hash,category,title,description,risk_level,status,version,created_by) VALUES (?,?,?,?,'quality','Title','Description','HIGH','OPEN',0,'alice')",id,key,"result", "a".repeat(64));
        when(queries.list("result")).thenReturn(List.of(view()));
    }
    private RiskEventQueryService.View view(){return new RiskEventQueryService.View(id,"result","action","quality","Title","Description","HIGH","OPEN","0");}
    private void assertState(String status,long version,int history){
        assertEquals(status,jdbc.queryForObject("SELECT status FROM iqc_risk_event WHERE id=?",String.class,id));
        assertEquals(version,jdbc.queryForObject("SELECT version FROM iqc_risk_event WHERE id=?",Long.class,id));
        assertEquals(history,jdbc.queryForObject("SELECT COUNT(*) FROM iqc_risk_handling_event WHERE risk_event_id=?",Integer.class,id));
    }
    @Test void committedHandlingHasOneImmutableReasonAndStaleObservationCannotOverwrite(){
        assertTrue(service.handle("result",id,"0","RESOLVED","  checked evidence  "));assertState("RESOLVED",1,1);
        var event=context.getBean(RiskHandlingEventMapper.class).page(id,null).getFirst();
        assertEquals("1",event.version());assertEquals("OPEN",event.previousStatus());assertEquals("RESOLVED",event.status());
        assertEquals("checked evidence",event.reason());assertEquals("alice",event.actor());assertNotNull(event.recordedAtUtc());
        assertFalse(service.handle("result",id,"0","DISMISSED","stale"));assertState("RESOLVED",1,1);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE iqc_risk_handling_event SET reason='rewrite' WHERE risk_event_id=?",id));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM iqc_risk_handling_event WHERE risk_event_id=?",id));
        assertState("RESOLVED",1,1);
    }
    @Test void databaseReasonFailureRollsBackAlreadyExecutedStateCas(){
        jdbc.execute("CREATE TRIGGER test_reject_risk_reason BEFORE INSERT ON iqc_risk_handling_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Injected reason failure'");
        try{
            assertThrows(org.springframework.dao.DataAccessException.class,()->service.handle("result",id,"0","RESOLVED","evidence"));
            assertState("OPEN",0,0);
        }finally{jdbc.execute("DROP TRIGGER test_reject_risk_reason");}
        assertTrue(service.handle("result",id,"0","RESOLVED","recovered"));assertState("RESOLVED",1,1);
    }
    @Test void twoConcurrentObservedVersionsHaveExactlyOneWinner() throws Exception {
        var barrier=new CountDownLatch(2);
        when(queries.list("result")).thenAnswer(call->{barrier.countDown();assertTrue(barrier.await(10,TimeUnit.SECONDS));return List.of(view());});
        var pool=Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->service.handle("result",id,"0","RESOLVED","first"));
            var second=pool.submit(()->service.handle("result",id,"0","DISMISSED","second"));
            boolean a=first.get(20,TimeUnit.SECONDS),b=second.get(20,TimeUnit.SECONDS);assertNotEquals(a,b);
            assertState(a?"RESOLVED":"DISMISSED",1,1);
            assertEquals(a?"first":"second",context.getBean(RiskHandlingEventMapper.class).page(id,null).getFirst().reason());
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
