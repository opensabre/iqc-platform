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
        @Bean RiskHandlingHistoryService historyService(RiskEventQueryService queries,RiskHandlingEventMapper history){return new RiskHandlingHistoryService(queries,history);}
        @Bean RiskCenterQueryService centerService(RiskEventMapper events,RiskEventQueryService queries,IqcDataScope scope){return new RiskCenterQueryService(events,queries,scope);}
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
        // Center JOINs only this scenario's tasks; leave immutable histories untouched in the private schema.
        jdbc.update("DELETE FROM iqc_inspection_task");
        queries=context.getBean(RiskEventQueryService.class);reset(queries);
        var scope=context.getBean(IqcDataScope.class);reset(scope);when(scope.owner()).thenReturn("alice");
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
    /** Seed actual immutable rows without depending on the handling loop under test. */
    private void seedHistory(int first,int last,int currentVersion){
        var mapper=context.getBean(RiskHandlingEventMapper.class);
        var now=java.time.LocalDateTime.of(2026,10,11,0,0);
        for(int version=first;version<=last;version++){
            String previous=version%2==1?"OPEN":"RESOLVED",next=version%2==1?"RESOLVED":"OPEN";
            assertEquals(1,mapper.append(id,version,previous,next,"evidence "+version,"alice",now.plusSeconds(version)));
        }
        String currentStatus=currentVersion%2==1?"RESOLVED":"OPEN";
        jdbc.update("UPDATE iqc_risk_event SET version=?,status=? WHERE id=?",currentVersion,currentStatus,id);
        when(queries.list("result")).thenReturn(List.of(new RiskEventQueryService.View(id,"result","action","quality","Title","Description","HIGH",currentStatus,Integer.toString(currentVersion))));
    }
    @Test void historyPaginatesRealSqlWithoutLosingVersionsOrChangingState(){
        seedHistory(1,55,55);
        var reader=context.getBean(RiskHandlingHistoryService.class);
        var first=reader.page("result",id,null);assertEquals(50,first.events().size());assertEquals("55",first.events().getFirst().version());
        assertEquals("6",first.nextBeforeVersion());
        var second=reader.page("result",id,first.nextBeforeVersion());assertEquals(5,second.events().size());
        assertEquals("5",second.events().getFirst().version());assertEquals("1",second.events().getLast().version());assertNull(second.nextBeforeVersion());
        assertTrue(reader.page("result",id,"1").events().isEmpty());assertState("RESOLVED",55,55);
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->reader.page("result",id,"57"));
    }
    @Test void lastHistoryPageRejectsMissingOriginInsteadOfShowingCompleteHistory(){
        seedHistory(2,55,55);
        var reader=context.getBean(RiskHandlingHistoryService.class);
        assertEquals("6",reader.page("result",id,null).nextBeforeVersion());
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->reader.page("result",id,"6"));
        assertState("RESOLVED",55,54);
    }
    @Test void historyRejectsMissingCurrentRecordAndEmptyInteriorCursor(){
        seedHistory(2,54,55);
        var reader=context.getBean(RiskHandlingHistoryService.class);
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->reader.page("result",id,null));
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->reader.page("result",id,"2"));
        assertState("RESOLVED",55,53);
    }

    private List<RiskEventQueryService.View> centerRows(String owner,String group,int count){
        String token=UUID.randomUUID().toString().replace("-","");String task="task-"+token,result="result-"+token;
        jdbc.update("INSERT INTO iqc_inspection_task(id,name,status,created_by,owner_group_id) VALUES (?,'Center fixture','SUCCEEDED',?,?)",task,owner,group);
        jdbc.update("INSERT INTO iqc_inspection_conversation_result(id,task_id,execution_id,conversation_id,result_status,reason,created_by) VALUES (?,?,?,?,'QUALIFIED','fixture',?)",result,task,"execution-"+token,"conversation-"+token,owner);
        var views=new ArrayList<RiskEventQueryService.View>();
        for(int index=0;index<count;index++){
            String key=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");String eventId="risk-"+key.substring(0,59);
            jdbc.update("INSERT INTO iqc_risk_event(id,source_action_execution_id,source_result_id,source_input_hash,category,title,description,risk_level,status,version,created_by) VALUES (?,?,?,?,'quality','Title','Description','HIGH','OPEN',0,?)",eventId,key,result,"a".repeat(64),owner);
            views.add(new RiskEventQueryService.View(eventId,result,key,"quality","Title","Description","HIGH","OPEN","0"));
        }
        when(queries.list(result)).thenReturn(views);return views;
    }
    @Test void centerSqlHonorsBinaryOwnerGroupAndSourceOwnerJoin(){
        var own=centerRows("alice","other-group",1).getFirst();var peer=centerRows("bob","team",1).getFirst();
        var outside=centerRows("mallory","outside",1).getFirst();var caseOwner=centerRows("Alice","outside",1).getFirst();
        var caseGroup=centerRows("carol","TEAM",1).getFirst();
        var mapper=context.getBean(RiskEventMapper.class);
        assertEquals(Set.of(own.id()),mapper.center(false,"alice",null,null,null,null).stream().map(RiskEventMapper.Reference::id).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(own.id(),peer.id()),mapper.center(false,"alice","team",null,null,null).stream().map(RiskEventMapper.Reference::id).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(own.id(),peer.id(),outside.id(),caseOwner.id(),caseGroup.id()),mapper.center(true,"alice",null,null,null,null).stream().map(RiskEventMapper.Reference::id).collect(java.util.stream.Collectors.toSet()));
        jdbc.update("UPDATE iqc_risk_event SET created_by='Mallory' WHERE id=?",outside.id());
        assertFalse(mapper.center(true,"alice",null,null,null,null).stream().anyMatch(row->row.id().equals(outside.id())));
        assertTrue(mapper.center(false,"alice","team",null,"RESOLVED",null).isEmpty());
        assertTrue(mapper.center(false,"alice","team",null,null,"LOW").isEmpty());
    }
    @Test void centerRealSqlPaginatesAndVerifiesEachResultOncePerPage(){
        var views=centerRows("alice","team",25);var expected=views.stream().map(RiskEventQueryService.View::id).sorted().toList();
        var reader=context.getBean(RiskCenterQueryService.class);
        var first=reader.page(null,"OPEN","HIGH");assertEquals(expected.subList(0,20),first.events().stream().map(RiskEventQueryService.View::id).toList());
        assertEquals(expected.get(19),first.nextAfterId());
        var second=reader.page(first.nextAfterId(),"OPEN","HIGH");assertEquals(expected.subList(20,25),second.events().stream().map(RiskEventQueryService.View::id).toList());assertNull(second.nextAfterId());
        verify(queries,times(2)).list(views.getFirst().sourceResultId());
        when(queries.list(views.getFirst().sourceResultId())).thenReturn(List.of());
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->reader.page(null,"OPEN","HIGH"));
    }

}
