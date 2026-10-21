package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.action.*;
import io.github.opensabre.iqc.conversation.ProductionInputSnapshotService;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.*;
import io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.task.dao.*;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.junit.jupiter.api.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IsolatedMysqlActionSourceIT {
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static InspectionTaskMapper tasks;
    private static TaskExecutionMapper executions;
    private static ConversationInspectionResultMapper results;
    private static ActionDiscoveryMapper discovery;
    private ActionProductionSourceValidator source;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final ActionProductionSourceValidator.Reference reference=new ActionProductionSourceValidator.Reference("task","execution","conversation","result","alice");
    @BeforeAll static void start() throws Exception {
        IsolatedMysqlTaskExecutionStateIT.start();context=new AnnotationConfigApplicationContext(IsolatedMysqlTaskExecutionStateIT.DatabaseConfiguration.class);
        jdbc=new JdbcTemplate(context.getBean(DataSource.class));tx=new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        var factory=context.getBean(SqlSessionFactory.class);factory.getConfiguration().addMapper(ConversationInspectionResultMapper.class);factory.getConfiguration().addMapper(ActionDiscoveryMapper.class);
        var session=new SqlSessionTemplate(factory);tasks=session.getMapper(InspectionTaskMapper.class);executions=session.getMapper(TaskExecutionMapper.class);
        results=session.getMapper(ConversationInspectionResultMapper.class);discovery=session.getMapper(ActionDiscoveryMapper.class);
    }
    @AfterAll static void close() {if(context!=null)context.close();IsolatedMysqlTaskExecutionStateIT.close();}
    @BeforeEach void fixture() throws Exception { fixture("SAVE_RESULT"); }
    void fixture(String type) throws Exception {fixture(type,null);}
    void fixture(String type,java.time.LocalTime relativeTime) throws Exception {
        fixture(type,relativeTime,PipelineRelease.SCHEMA);
    }
    void fixture(String type,java.time.LocalTime relativeTime,String schema) throws Exception {
        jdbc.update("DELETE FROM iqc_inspection_conversation_result");jdbc.update("DELETE FROM iqc_task_execution");jdbc.update("DELETE FROM iqc_inspection_task");
        var release=json.readerFor(PipelineRelease.class)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).<PipelineRelease>readValue("""
          {"schemaVersion":"iqc-pipeline-release-v1","pipeline":{"schemaVersion":"iqc-pipeline-v1","nodes":[
          {"nodeId":"llm","type":"LLM","version":"1","enabled":true,"timeoutMs":1000,"retries":0,"onError":"FAIL_PIPELINE","config":{"findingDefinition":
          {"schemaVersion":"iqc-finding-definition-v1","name":"Risk","code":"RISK","category":"risk","valueType":"BOOLEAN",
          "defaultSeverity":"HIGH","evidenceRequired":true,"evidenceTypes":["CONVERSATION"]},"ruleSnapshot":{"id":"rule","versionNo":1}}}]},
          "fusion":{"id":"fusion","version":1,"method":"PRIORITY"},"items":[{"itemCode":"risk","findingCode":"RISK","hitMeaning":"VIOLATION"}],
          "scoring":{"version":"iqc-score-v2","mode":"DEDUCTION","baseScore":100,"passingScore":80,"items":[{"itemCode":"risk","points":20,"veto":false}]},
          "actionPolicy":{"schemaVersion":"iqc-action-policy-v1","actions":[{"actionId":"save","type":"SAVE_RESULT","condition":{"kind":"ALWAYS"},
          "idempotency":"PRODUCTION_SOURCE_V1","failureHandling":"RECORD_ONLY","configuration":{}}]}}
          """.replace("\"type\":\"SAVE_RESULT\"", "\"type\":\""+type+"\"")
          .replace("\"configuration\":{}", "CREATE_REVIEW".equals(type)?"\"configuration\":{\"reason\":\"automated review\"}":
                  "ADD_TO_EVALUATION_CANDIDATE".equals(type)?"\"configuration\":{\"sampleType\":\"FALSE_POSITIVE\",\"reviewRevision\":1}":"\"configuration\":{}")
          .replace(PipelineRelease.SCHEMA,schema));
        var business=json.createObjectNode().put("businessType","frozen");
        if(PipelineRelease.EXACT_SCHEMA.equals(schema))business.put("amount",new java.math.BigDecimal("0.900000000000000001"));
        var input=new ProductionInputSnapshotService.Input("conversation","revision",business,
                List.of(new ProductionInputSnapshotService.Message("message",1,"agent","frozen text",relativeTime)));
        var body=json.valueToTree(List.of(input));var root=json.createObjectNode();root.putObject("executionStrategy").put("mode","PIPELINE");
        root.putObject("productionInputs").put("schemaVersion",ProductionInputSnapshotService.SCHEMA).put("hashAlgorithm","selector-decimal-sha256-v1")
                .put("contentHash",SelectorAssetService.hash(body.toString())).set("conversations",body);
        root.putObject("pipelineSnapshot").put("pipelineId","pipeline").put("versionNo",1)
                .put("contentHash",PipelineAssetService.hash(json.writeValueAsString(release))).set("release",json.valueToTree(release));
        root.putObject("actionOwnerBound").put("schemaVersion","iqc-schedule-owner-scope-v1").put("userId","user").put("owner","alice").put("all",false).put("groupId","group");
        jdbc.update("INSERT INTO iqc_inspection_task(id,name,task_type,status,current_execution_id,created_by,owner_group_id,rule_snapshot_json,conversation_ids_json,total_messages) VALUES ('task','isolated source','PIPELINE','RUNNING','execution','alice','group',?,'[\"conversation\"]',1)",root.toString());
        jdbc.update("INSERT INTO iqc_task_execution(id,task_id,attempt_no,status) VALUES ('execution','task',1,'RUNNING')");
        InspectionTask task=tasks.selectById("task");
        var inputs=new ProductionInputSnapshotService(null,null,null,json);var prepared=inputs.prepare(task,root);
        var fact=new Finding("fact","RISK","risk",Finding.ValueType.BOOLEAN,json.valueToTree(true),Finding.State.KNOWN,null,Finding.Severity.HIGH,
                "risk",List.of(new Finding.Evidence("evidence",Finding.EvidenceType.CONVERSATION,"message",null)),new Finding.Source("llm","LLM","1",1,null));
        PipelineConversationEvaluator.Evaluation evaluation;
        if(PipelineRelease.EXACT_SCHEMA.equals(schema)) {
            // Exercise runtime scheduling/trace production as well as SQL source replay; no provider call.
            EngineAdapter adapter=new EngineAdapter(){
                public String type(){return "LLM";}
                public NodeResult execute(NodeContext context){return new NodeResult(NodeResult.Status.SUCCESS,List.of(fact),Map.of(),Map.of(),null);}
            };
            try(var runtime=new PipelineRuntime(List.of(adapter),1,4)) {
                evaluation=new PipelineConversationEvaluator(runtime,json,tasks).evaluate(task,release,prepared.messages().get("conversation"),PipelineRuntime.Mode.PRODUCTION,prepared.businessContext("conversation"));
                assertEquals(PipelineRuntime.Status.SUCCESS,evaluation.run().status());
                assertEquals(1,evaluation.run().findings().size());
            }
        } else {
            var runtime=mock(PipelineRuntime.class);
            when(runtime.execute(any(),any(),any(),any(),any())).thenReturn(new PipelineRuntime.Run(PipelineRuntime.Status.SUCCESS,List.of(),List.of(fact),Map.of(),false));
            evaluation=new PipelineConversationEvaluator(runtime,json,tasks).evaluate(task,release,prepared.messages().get("conversation"),PipelineRuntime.Mode.PRODUCTION,prepared.businessContext("conversation"));
        }
        var row=new ConversationInspectionResult();row.setId("result");row.setTaskId("task");row.setExecutionId("execution");row.setConversationId("conversation");
        row.setAggregationMode("PIPELINE");row.setReason("isolated source");row.setExecutionTraceJson(json.writeValueAsString(evaluation));
        row.setScoringResultJson(json.writeValueAsString(evaluation.scoring()));row.setBusinessItemResultsJson(json.writeValueAsString(evaluation.items()));
        row.setScoreStatus(evaluation.scoring().scoreStatus().name());row.setFinalScore(evaluation.scoring().finalScore());
        row.setResultStatus(evaluation.scoring().conclusion().name());row.setRiskLevel(evaluation.riskLevel());assertEquals(1,results.insert(row));
        var assets=mock(PipelineAssetService.class);when(assets.publishedTaskRelease(any())).thenReturn(release);
        source=new ActionProductionSourceValidator(tasks,executions,results,assets,inputs,json);
    }
    private ActionProductionSourceValidator.Validated validate() {return tx.execute(status->source.validate(reference));}
    @Test void exactV2PersistedSourceRetainsBusinessConditionIdentityAndRejectsTampering() throws Exception {
        fixture("SAVE_RESULT",null,PipelineRelease.EXACT_SCHEMA);
        var actual=validate();
        assertEquals(new java.math.BigDecimal("0.900000000000000001"),actual.context().businessContext().path("amount").decimalValue());
        assertEquals(ActionPolicy.Match.MATCH,ActionPolicy.evaluate(new ActionPolicy.Condition(ActionPolicy.Kind.BUSINESS_EQUALS,
                "/amount",null,List.of(),"0.900000000000000001"),actual.context()));
        assertEquals(ActionPolicy.Match.NO_MATCH,ActionPolicy.evaluate(new ActionPolicy.Condition(ActionPolicy.Kind.BUSINESS_EQUALS,
                "/amount",null,List.of(),"0.900000000000000002"),actual.context()));
        // Replace raw stored text: JSON_SET itself would coerce the exact numeric literal.
        jdbc.update("UPDATE iqc_inspection_task SET rule_snapshot_json=REPLACE(rule_snapshot_json,?,?) WHERE id='task'",
                "0.900000000000000001","0.900000000000000002");
        assertThrows(RuntimeException.class,this::validate);
    }
    @Test void actualFrozenInputAndDeterministicProjectionProduceBoundActionSource() throws Exception {
        var actual=validate();assertEquals("user",actual.ownerBound().userId());assertEquals("result",actual.decisionReference());
        assertEquals(SelectorAssetService.hash(actual.inputSnapshotJson()),actual.inputSnapshotHash());
        assertEquals(ActionPolicy.Match.MATCH,ActionPolicy.evaluate(actual.policy().actions().getFirst().condition(),actual.context()));
    }
    @Test void laterExecutionCannotReuseEarlierSource() {
        jdbc.update("UPDATE iqc_inspection_task SET current_execution_id='later' WHERE id='task'");assertThrows(RuntimeException.class,this::validate);
    }
    @Test void tamperedScoreProjectionIsRejected() {
        jdbc.update("UPDATE iqc_inspection_conversation_result SET final_score=99 WHERE id='result'");assertThrows(RuntimeException.class,this::validate);
    }
    @Test void tamperedInputHashIsRejected() {
        jdbc.update("UPDATE iqc_inspection_task SET rule_snapshot_json=JSON_SET(rule_snapshot_json,'$.productionInputs.contentHash',?) WHERE id='task'","b".repeat(64));
        assertThrows(RuntimeException.class,this::validate);
    }
    @Test void evaluationModeTraceCannotBecomeProductionActionSource() {
        jdbc.update("UPDATE iqc_inspection_conversation_result SET execution_trace_json=JSON_SET(execution_trace_json,'$.mode','EVALUATION') WHERE id='result'");
        assertThrows(RuntimeException.class,this::validate);
    }
    @Test void discoveryUsesCurrentProductionSourceAndCompletionCheckpoint() {
        var page=discovery.findDue(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));assertEquals(1,page.size());assertEquals("result",page.getFirst().resultId());assertEquals("alice",page.getFirst().owner());
        jdbc.update("UPDATE iqc_inspection_task SET current_execution_id='later' WHERE id='task'");
        assertTrue(discovery.findDue(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)).isEmpty());
        jdbc.update("UPDATE iqc_inspection_task SET current_execution_id='execution' WHERE id='task'");
        discovery.complete("result");assertTrue(discovery.findDue(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)).isEmpty());
    }
    @Test void discoveryDeferralUsesPersistedUtcBoundary() {
        var now=java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        assertEquals(1,discovery.defer("result",now.plusMinutes(5)));
        assertTrue(discovery.findDue(now).isEmpty());
        assertEquals(1,discovery.findDue(now.plusMinutes(6)).size());
        discovery.complete("result");
        assertEquals(0,discovery.defer("result",now));
        assertTrue(discovery.findDue(now.plusMinutes(6)).isEmpty());
    }
    @Test void completingFullPageReachesNextSourceWithoutMemoryCursor() {
        for(int i=0;i<101;i++)jdbc.update("INSERT INTO iqc_inspection_conversation_result(id,task_id,execution_id,conversation_id,aggregation_mode,result_status,reason) SELECT ?,task_id,execution_id,?,aggregation_mode,result_status,reason FROM iqc_inspection_conversation_result WHERE id='result'",
                String.format("a%03d",i),String.format("c%03d",i));
        var now=java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        var page=discovery.findDue(now);assertEquals(100,page.size());
        page.forEach(r->discovery.complete(r.resultId()));
        var remaining=discovery.findDue(now);assertEquals(2,remaining.size());
        assertEquals(List.of("a100","result"),remaining.stream().map(ActionDiscoveryMapper.Source::resultId).toList());
    }
    @Test void rolledBackCompletionRemainsDiscoverable() {
        tx.executeWithoutResult(status->{assertEquals(1,discovery.complete("result"));status.setRollbackOnly();});
        assertFalse(discovery.isComplete("result"));
        assertEquals(1,discovery.findDue(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)).size());
    }
    @Test void discoveryExcludesPausedAndTrialTasks() {
        jdbc.update("UPDATE iqc_inspection_task SET pause_requested=1 WHERE id='task'");assertTrue(discovery.findDue(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)).isEmpty());
        jdbc.update("UPDATE iqc_inspection_task SET pause_requested=0,task_type='PIPELINE_TRIAL' WHERE id='task'");assertTrue(discovery.findDue(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)).isEmpty());
    }

    @Test void historyJournalRequiresActualOwningResultTaskExecutionConversationAndOwner() {
        var session=new SqlSessionTemplate(context.getBean(SqlSessionFactory.class));
        var actions=session.getMapper(io.github.opensabre.iqc.action.ActionExecutionMapper.class);
        var events=session.getMapper(io.github.opensabre.iqc.action.ActionStateEventMapper.class);
        var scope=mock(io.github.opensabre.iqc.shared.IqcDataScope.class);
        when(scope.canView("alice","group")).thenReturn(true);
        var query=new io.github.opensabre.iqc.action.ActionQueryService(session.getMapper(io.github.opensabre.iqc.action.ActionReconciliationAttemptMapper.class),results,tasks,actions,scope,events,json);
        jdbc.update("DELETE FROM iqc_action_execution_event");jdbc.update("DELETE FROM iqc_action_execution");
        String id="a".repeat(64);
        jdbc.update("""
            INSERT INTO iqc_action_execution(id,idempotency_key,task_id,execution_id,conversation_id,
            source_result_id,decision_reference,action_policy_hash,action_id,action_type,owner,
            input_snapshot_json,input_snapshot_hash,policy_snapshot_json,status)
            VALUES (?,?,'task','execution','conversation','result','result',?,'save','SAVE_RESULT','alice','{}',?,'{}','PENDING')
            """,id,id,"b".repeat(64),"c".repeat(64));
        assertEquals(1,tx.execute(status->query.history("result",id,true,null)).getFirst().history().events().size());
        // Each forged reference must be rejected by the actual authorized ledger query, not a mock answer.
        for(String[] forged:new String[][]{{"task_id","foreign-task"},{"execution_id","foreign-eid"},{"conversation_id","foreign-conversation"},{"owner","mallory"},{"source_result_id","foreign-result"}}) {
            String original=switch(forged[0]) {case "task_id"->"task";case "execution_id"->"execution";case "conversation_id"->"conversation";case "owner"->"alice";default->"result";};
            // Identifiers below are fixed test-owned columns, never request interpolation.
            jdbc.update("UPDATE iqc_action_execution SET "+forged[0]+"=? WHERE id=?",forged[1],id);
            try {assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->tx.execute(status->query.history("result",id,true,null)));}
            finally {jdbc.update("UPDATE iqc_action_execution SET "+forged[0]+"=? WHERE id=?",original,id);}
        }
        when(scope.canView("alice","group")).thenReturn(false);
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->tx.execute(status->query.history("result",id,true,null)));
    }

}
