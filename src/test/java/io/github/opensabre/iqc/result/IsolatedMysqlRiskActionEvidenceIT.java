package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.action.*;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.fusion.*;
import io.github.opensabre.iqc.risk.*;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import java.time.LocalDateTime;
import java.util.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real risk/action SQL and owning transactions; validated production source and organization are controlled. */
class IsolatedMysqlRiskActionEvidenceIT {
    private static String url;
    private static AnnotationConfigApplicationContext context;
    private JdbcTemplate db;
    private RiskEventActionService service;
    private ActionProductionSourceValidator sources;
    private IqcDataScope scope;
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource(){return new DriverManagerDataSource(url,"root","");}
        @Bean DataSourceTransactionManager transactionManager(DataSource source){return new DataSourceTransactionManager(source);}
        @Bean SqlSessionFactory factory(DataSource source) throws Exception {
            var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
            config.addMapper(ActionExecutionMapper.class);config.addMapper(RiskEventMapper.class);
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);return factory.getObject();
        }
        @Bean ObjectMapper json(){return new ObjectMapper().findAndRegisterModules();}
        @Bean ActionProductionSourceValidator sources(){return mock(ActionProductionSourceValidator.class);}
        @Bean IqcDataScope scope(){return mock(IqcDataScope.class);}
        @Bean RiskEventActionService risks(ActionProductionSourceValidator sources,SqlSessionFactory factory,IqcDataScope scope,ObjectMapper json){
            var sql=new SqlSessionTemplate(factory);return new RiskEventActionService(sources,sql.getMapper(ActionExecutionMapper.class),sql.getMapper(RiskEventMapper.class),scope,json);
        }
    }
    @BeforeAll static void start() throws Exception {
        url=IsolatedMysqlTaskMetricsIT.createSchema("iqc_risk_action_evidence_");
        var flyway=IsolatedMysqlTaskMetricsIT.flyway(url,null);flyway.migrate();flyway.validate();assertEquals(0,flyway.migrate().migrationsExecuted);
        context=new AnnotationConfigApplicationContext(Config.class);
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(RiskEventActionService.class)));
    }
    @AfterAll static void close(){if(context!=null)context.close();}
    @BeforeEach void fixture(){
        db=new JdbcTemplate(context.getBean(DataSource.class));service=context.getBean(RiskEventActionService.class);
        sources=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(context.getBean(ActionProductionSourceValidator.class));
        scope=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(context.getBean(IqcDataScope.class));
        reset(sources,scope);when(scope.owner()).thenReturn("alice");
    }
    private Finding raw(String node,boolean evidence,ObjectMapper json){
        return new Finding(node,"CONSENT","compliance",Finding.ValueType.BOOLEAN,json.valueToTree(true),Finding.State.KNOWN,null,Finding.Severity.LOW,"observed",
                evidence?List.of(new Finding.Evidence("quote",Finding.EvidenceType.CONVERSATION,"message",null)):List.of(),new Finding.Source(node,"RULE","1",1,"input"));
    }
    private ActionAdapter.Request seed(String risk,boolean selectedEvidence,boolean rejectedEvidence) throws Exception {
        var json=context.getBean(ObjectMapper.class);String token=UUID.randomUUID().toString().replace("-","");
        var action=new ActionPolicy.Action("risk",ActionExecutionContract.Type.CREATE_RISK_EVENT,new ActionPolicy.Condition(ActionPolicy.Kind.ALWAYS,null,null,List.of()),
                ActionPolicy.Idempotency.PRODUCTION_SOURCE_V1,null,ActionPolicy.FailureHandling.RECORD_ONLY,
                json.createObjectNode().put("category","quality").put("title","Risk").put("description","Check source"));
        var policy=new ActionPolicy(ActionPolicy.SCHEMA,List.of(action));String policyBody=json.writeValueAsString(policy);
        var source=new ActionExecutionContract.Source("task"+token,"execution"+token,"conversation"+token,"result"+token,"decision"+token,
                io.github.opensabre.iqc.selector.SelectorAssetService.hash(policyBody),"risk","alice");
        var fused=FindingFusion.fuse(List.of(raw("winner",selectedEvidence,json),raw("loser",rejectedEvidence,json)),
                new FusionPolicy("priority",1,FusionPolicy.Method.PRIORITY,Map.of("winner",2,"loser",1),Map.of(),null,Set.of()));
        var decision=new ActionPolicy.Context(Map.of("CONSENT",fused.getFirst()),Map.of(),io.github.opensabre.iqc.scoring.InspectionScoring.ScoreStatus.FINAL,
                java.math.BigDecimal.TEN,risk,"UNQUALIFIED");
        String input=json.writeValueAsString(decision),hash=io.github.opensabre.iqc.selector.SelectorAssetService.hash(input);
        var reference=new ActionProductionSourceValidator.Reference(source.taskId(),source.executionId(),source.conversationId(),source.resultId(),source.owner());
        var validated=new ActionProductionSourceValidator.Validated(reference,"team",source.decisionReference(),input,hash,policy,decision,
                new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1","user-alice","alice",false,"team"),"SUCCEEDED");
        when(sources.validate(reference)).thenReturn(validated);when(sources.validateHistorical(reference)).thenReturn(validated);
        String key=source.idempotencyKey(action.type());var row=new ActionExecution();row.setId(key);row.setIdempotencyKey(key);
        row.setTaskId(source.taskId());row.setExecutionId(source.executionId());row.setConversationId(source.conversationId());row.setSourceResultId(source.resultId());
        row.setDecisionReference(source.decisionReference());row.setActionPolicyHash(source.actionPolicyHash());row.setActionId("risk");row.setActionType(action.type().name());
        row.setOwner("alice");row.setOwnerGroupId("team");row.setInputSnapshotJson(input);row.setInputSnapshotHash(hash);row.setPolicySnapshotJson(policyBody);
        row.setStatus("RUNNING");row.setAttemptCount(1);row.setVersion(1L);row.setClaimToken(UUID.randomUUID().toString().replace("-",""));row.setClaimedAtUtc(LocalDateTime.now());row.setCreatedBy("alice");
        var transaction=new org.springframework.transaction.support.TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        Integer inserted=transaction.execute(status -> new SqlSessionTemplate(context.getBean(SqlSessionFactory.class)).getMapper(ActionExecutionMapper.class).insert(row));
        assertEquals(Integer.valueOf(1),inserted);
        return new ActionAdapter.Request(key,key,source,RiskEventActionService.DEFINITION,1,input,hash,policyBody);
    }
    private int count(ActionAdapter.Request request){return db.queryForObject("SELECT COUNT(*) FROM iqc_risk_event WHERE source_action_execution_id=?",Integer.class,request.executionId());}
    @Test void absentAndRejectedOnlyEvidenceCannotCreateNewHighRiskEvent() throws Exception {
        for(boolean rejected:List.of(false,true)){
            var request=seed("HIGH",false,rejected);
            var failure=assertThrows(RuntimeException.class,()->service.create(request));assertTrue(failure.getMessage().contains("高风险事件"));
            assertEquals(0,count(request));assertNull(service.lookup(request));
            assertEquals("RUNNING",db.queryForObject("SELECT status FROM iqc_action_execution WHERE id=?",String.class,request.executionId()));
        }
    }
    @Test void selectedEvidenceCreatesOneArtifactAndHistoricalLookupSurvivesSettlement() throws Exception {
        var request=seed("CRITICAL",true,false);String id=service.create(request);assertEquals(id,service.create(request));assertEquals(1,count(request));
        db.update("UPDATE iqc_action_execution SET status='COMPLETE',version=2 WHERE id=?",request.executionId());
        assertEquals(id,service.lookup(request));assertThrows(RuntimeException.class,()->service.create(request));assertEquals(1,count(request));
    }
    @Test void historicalArtifactWithoutSelectedEvidenceIsReusedWithoutRewritingHandling() throws Exception {
        var request=seed("HIGH",false,true);String id="risk-"+request.idempotencyKey().substring(0,59);
        db.update("INSERT INTO iqc_risk_event(id,source_action_execution_id,source_result_id,source_input_hash,category,title,description,risk_level,status,version,created_by) VALUES (?,?,?,?,'quality','Risk','Check source','HIGH','RESOLVED',3,'alice')",
                id,request.executionId(),request.source().resultId(),request.inputSnapshotHash());
        assertEquals(id,service.create(request));assertEquals(id,service.lookup(request));assertEquals(1,count(request));
        assertEquals("RESOLVED",db.queryForObject("SELECT status FROM iqc_risk_event WHERE id=?",String.class,id));
        assertEquals(3L,db.queryForObject("SELECT version FROM iqc_risk_event WHERE id=?",Long.class,id));
    }
    @Test void eventWriteFailureLeavesNoArtifactAndDoesNotSettleAnalysisAction() throws Exception {
        var request=seed("HIGH",true,false);
        db.execute("CREATE TRIGGER reject_risk_artifact BEFORE INSERT ON iqc_risk_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected risk artifact failure'");
        try {assertThrows(org.springframework.dao.DataAccessException.class,()->service.create(request));}
        finally {db.execute("DROP TRIGGER reject_risk_artifact");}
        assertEquals(0,count(request));assertEquals("RUNNING",db.queryForObject("SELECT status FROM iqc_action_execution WHERE id=?",String.class,request.executionId()));
        assertEquals(1L,db.queryForObject("SELECT version FROM iqc_action_execution WHERE id=?",Long.class,request.executionId()));
        assertNotNull(service.create(request));assertEquals(1,count(request));
    }
    @Test void revokedCreateGrantDoesNotTurnHistoricalLookupIntoNewCreation() throws Exception {
        var request=seed("HIGH",true,false);String id=service.create(request);
        doThrow(io.github.opensabre.iqc.governance.IqcException.accessDenied("revoked")).when(scope).requireScheduledResources(anySet());
        assertThrows(RuntimeException.class,()->service.create(request));assertEquals(id,service.lookup(request));assertEquals(1,count(request));
    }
}
