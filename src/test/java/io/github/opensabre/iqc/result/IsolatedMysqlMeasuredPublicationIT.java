package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.action.*;
import io.github.opensabre.iqc.rule.dao.*;
import io.github.opensabre.iqc.rule.model.*;
import io.github.opensabre.iqc.agent.dao.*;
import io.github.opensabre.iqc.agent.model.*;
import io.github.opensabre.iqc.result.llm.*;
import io.github.opensabre.iqc.quality.BusinessItemReviewService;
import io.github.opensabre.iqc.quality.BusinessItemReviewEvaluator;
import io.github.opensabre.iqc.quality.BusinessReviewSampleService;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import java.time.Instant;
import io.github.opensabre.iqc.conversation.*;
import io.github.opensabre.iqc.conversation.dao.*;
import io.github.opensabre.iqc.finding.*;
import io.github.opensabre.iqc.finding.dao.*;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.*;
import io.github.opensabre.iqc.pipelineasset.dao.*;
import io.github.opensabre.iqc.pipelinebridge.*;
import io.github.opensabre.iqc.quality.dao.*;
import io.github.opensabre.iqc.quality.evaluation.*;
import io.github.opensabre.iqc.quality.model.QualitySample;
import io.github.opensabre.iqc.result.dao.*;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.*;
import io.github.opensabre.iqc.task.dao.*;
import io.github.opensabre.iqc.task.model.InspectionTask;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real SQL, catalog, independent gold review, datasets, workers and measured publication; no provider calls. */
class IsolatedMysqlMeasuredPublicationIT {
    private static AnnotationConfigApplicationContext context;
    private static final AtomicReference<String> actor = new AtomicReference<>("annotator");
    @BeforeAll static void start() throws Exception {
        IsolatedMysqlTaskExecutionStateIT.start();
        context = new AnnotationConfigApplicationContext(Config.class);
    }
    @AfterAll static void close() {
        if(context!=null)context.close();IsolatedMysqlTaskExecutionStateIT.close();
        io.github.opensabre.common.core.util.UserContextHolder.getInstance().setContext(Map.of());
    }
    @Test void realMeasuredPublicationMaterializesAndExecutesFrozenProductionInput() throws Exception {
        io.github.opensabre.common.core.util.UserContextHolder.getInstance().setContext(Map.of("user_name","annotator","user_id","user-annotator"));
        var json=context.getBean(ObjectMapper.class);var db=new JdbcTemplate(context.getBean(DataSource.class));
        var sql=context.getBean(SqlSessionTemplate.class);String suffix=UUID.randomUUID().toString().replace("-","");
        String conversation="c"+suffix,first="m1"+suffix,second="m2"+suffix;
        db.update("INSERT INTO iqc_conversation(id,source_file_name,message_count,status,created_by,owner_group_id,source_fingerprint) VALUES (?,'processed conversation',2,'READY','annotator','group',?)",conversation,suffix);
        db.update("INSERT INTO iqc_conversation_message(id,conversation_id,sequence_no,speaker_role,content,start_ms,end_ms,relative_time,raw_line,line_number) VALUES (?,?,1,'customer','processed customer text',0,1000,'00:00:00','processed customer text',1),(?,?,2,'agent','processed agent text',2000,3000,'00:00:02','processed agent text',2)",first,conversation,second,conversation);
        var findingService=context.getBean(FindingCatalogService.class);
        var definition=new FindingDefinition(FindingDefinition.SCHEMA,"GAP_"+suffix.toUpperCase(),"Transcript interval gap","TIMING",null,
                Finding.ValueType.NUMBER,List.of(),Finding.Severity.HIGH,true,List.of(Finding.EvidenceType.METRIC));
        var catalog=findingService.create(definition);findingService.publish(catalog.getId(),1);
        var config=json.createObjectNode().put("metric","TRANSCRIPT_GAP_MS").put("agentRole","agent").put("customerRole","customer");
        config.putObject("findingReference").put("id",catalog.getId()).put("versionNo",1);
        var node=new PipelineDefinition.Node("timing","METRIC","Gap",TranscriptMetricEngine.VERSION,List.of(),Map.of(),config,null,3000,0,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
        var item=new PipelineRelease.ItemMapping("gap",definition.code(),io.github.opensabre.iqc.scheme.SchemeDefinition.HitMeaning.VIOLATION,null,
                new PipelineRelease.NumericPredicate(PipelineDefinition.Operator.GREATER_THAN,"0.900000000000000001"),null);
        var release=new PipelineRelease(PipelineRelease.EXACT_SCHEMA,new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(node)),
                new FusionPolicy("priority",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),
                new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION,InspectionScoring.Mode.DEDUCTION,100,60,
                        List.of(new InspectionScoring.Item("gap",20,false))),List.of(item),null,
                new ActionPolicy(ActionPolicy.SCHEMA,List.of(
                        new ActionPolicy.Action("save",ActionExecutionContract.Type.SAVE_RESULT,
                                new ActionPolicy.Condition(ActionPolicy.Kind.ALWAYS,null,null,List.of()),
                                ActionPolicy.Idempotency.PRODUCTION_SOURCE_V1,null,ActionPolicy.FailureHandling.RECORD_ONLY,json.createObjectNode()),
                        new ActionPolicy.Action("review",ActionExecutionContract.Type.CREATE_REVIEW,
                                new ActionPolicy.Condition(ActionPolicy.Kind.ALWAYS,null,null,List.of()),
                                ActionPolicy.Idempotency.PRODUCTION_SOURCE_V1,null,ActionPolicy.FailureHandling.RECORD_ONLY,
                                json.createObjectNode().put("reason","Check the processed conversation")))));
        var pipelines=context.getBean(PipelineAssetService.class);var asset=pipelines.create("P_"+suffix.toUpperCase(),"Measured publication",release);
        var gold=context.getBean(GoldSampleService.class);
        var sample=new QualitySample();sample.setName("Processed conversation candidate");sample.setSampleType("POSITIVE");
        sample.setConversationId(conversation);sample.setMessageId(first);sample.setSourceResultId("manual-"+suffix);
        sample.setContentSnapshot("processed customer text");sample.setStatus("CANDIDATE");sample.setCreatedBy("annotator");sample.setOwnerGroupId("group");
        sql.getMapper(QualitySampleMapper.class).insert(sample);
        gold.annotate(sample.getId(),new GoldSampleService.Annotation(0,List.of(new GoldFinding(definition.code(),Finding.ValueType.NUMBER,
                Finding.State.KNOWN,json.valueToTree(1000),List.of())),null,List.of(),GoldConversation.Difficulty.BASIC,
                new GoldDecision(new BigDecimal("80"),GoldDecision.ScoreStatus.FINAL,Map.of(),GoldDecision.Risk.HIGH)));
        assertThrows(RuntimeException.class,()->gold.review(sample.getId(),1,true,"same annotator cannot approve"));
        actor.set("reviewer");gold.review(sample.getId(),1,true,"independent fixture review");actor.set("annotator");
        var datasets=context.getBean(EvaluationDatasetService.class);var evaluations=context.getBean(PipelineEvaluationService.class);
        var evidence=new ArrayList<PipelinePublicationGateService.ExecutionEvidence>();
        for(var type:List.of(DatasetDraft.Type.RISK,DatasetDraft.Type.REGRESSION)) {
            var dataset=datasets.create(type.name(),new DatasetDraft(DatasetDraft.SCHEMA,type,null,List.of(new DatasetDraft.Member(sample.getId(),1))));
            datasets.freeze(dataset.getId(),1);
            var task=evaluations.create(dataset.getId(),1,asset.getId(),null,1,type.name(),1,"eval_"+UUID.randomUUID().toString().replace("-",""));
            var executed=run(task);
            var report=evaluations.report(executed.getId(),executed.getCurrentExecutionId());
            assertTrue(report.complete());assertEquals(1,report.successfulSamples());
            assertEquals(0,BigDecimal.ONE.compareTo(report.findingMetrics().aggregate().f1()));
            // Real persisted metric results must never manufacture a model consistency denominator.
            var repeated=run(evaluations.create(dataset.getId(),1,asset.getId(),null,1,"Independent repeat",1,
                    "repeat_"+UUID.randomUUID().toString().replace("-","")));
            assertNotEquals(executed.getId(),repeated.getId());
            var consistency=evaluations.consistency(executed.getId(),executed.getCurrentExecutionId(),
                    repeated.getId(),repeated.getCurrentExecutionId());
            assertEquals(0,consistency.expectedPairs());assertEquals(0,consistency.comparablePairs());
            assertEquals(0,consistency.agreeingPairs());assertNull(consistency.comparisonCoverage());
            assertNull(consistency.agreementRate());assertTrue(consistency.excludedReasons().isEmpty());
            evidence.add(new PipelinePublicationGateService.ExecutionEvidence(executed.getId(),executed.getCurrentExecutionId()));
        }
        var incompatible=assertThrows(RuntimeException.class,()->evaluations.consistency(
                evidence.get(0).taskId(),evidence.get(0).executionId(),
                evidence.get(1).taskId(),evidence.get(1).executionId()));
        assertTrue(incompatible.getMessage().contains("相同冻结数据集"));
        var trial=context.getBean(InspectionTaskService.class).createUsingPipelineSnapshot(pipelines.frozenDraft(asset.getId(),1),
                "real trial",List.of(conversation),1,"trial_"+suffix);
        trial=run(trial);
        assertThrows(RuntimeException.class,()->pipelines.publishMeasured(asset.getId(),1,null,true,evidence.get(0),evidence.get(1),false,null));
        var published=pipelines.publishMeasured(asset.getId(),1,trial.getId(),true,evidence.get(0),evidence.get(1),false,null);
        var admission=json.readTree(published.getPublicationDecisionJson());assertFalse(admission.path("forced").asBoolean());
        assertTrue(admission.path("gate").path("passed").asBoolean());assertEquals(2,admission.path("gate").path("evidence").size());
        assertEquals(PipelineAssetService.hash(published.getPublicationDecisionJson()),published.getPublicationDecisionHash());
        var prepared=pipelines.publishedReleaseForTask(asset.getId(),1);
        assertEquals("0.900000000000000001",prepared.release().items().getFirst().numericPredicate().threshold());
        var production=context.getBean(InspectionTaskService.class).createUsingPipelineSnapshot(prepared,"production",List.of(conversation),1,"production_"+suffix);
        // Mutate live data only after the owning materializer has frozen the complete processed conversation.
        db.update("UPDATE iqc_conversation_message SET content='later text',start_ms=10000,end_ms=11000 WHERE id=?",second);
        production=run(production);
        var snapshot=PipelineAssetService.readFrozenTree(json,production.getRuleSnapshotJson());
        assertEquals(PipelineRelease.EXACT_SCHEMA,pipelines.publishedTaskRelease(snapshot).schemaVersion());
        var result=sql.getMapper(ConversationInspectionResultMapper.class).selectLatestForTaskConversation(production.getId(),conversation);
        assertNotNull(result);assertEquals(0,new BigDecimal("80").compareTo(result.getFinalScore()));assertEquals("HIGH",result.getRiskLevel());
        assertTrue(result.getExecutionTraceJson().contains("1000"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM iqc_task_item WHERE execution_id=? AND status='SUCCEEDED'",Integer.class,production.getCurrentExecutionId()));
        assertEquals("later text",db.queryForObject("SELECT content FROM iqc_conversation_message WHERE id=?",String.class,second));
        // Dispatch owning operations from the actual published task, not a synthetic action source.
        var reference=new ActionProductionSourceValidator.Reference(production.getId(),production.getCurrentExecutionId(),conversation,result.getId(),"annotator");
        var enqueue=context.getBean(ActionEnqueueService.class);
        var actionIds=enqueue.enqueue(reference,authorizedActor());assertEquals(2,actionIds.size());
        assertEquals(actionIds,enqueue.enqueue(reference,authorizedActor()));
        var actions=sql.getMapper(ActionExecutionMapper.class);var dispatcher=context.getBean(ActionExecutionService.class);
        for(String id:actionIds){
            assertEquals("PENDING",actions.selectById(id).getStatus());
            if("CREATE_REVIEW".equals(actions.selectById(id).getActionType())) {
                // The owning review transaction commits, then a real SQL rejection loses the ledger receipt.
                assertTrue(id.matches("[a-f0-9]{64}"));
                db.execute("CREATE TRIGGER reject_measured_review_receipt BEFORE UPDATE ON iqc_action_execution FOR EACH ROW BEGIN IF NEW.id='"+id+"' AND OLD.status='RUNNING' AND NEW.status='COMPLETE' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated receipt write rejection'; END IF; END");
                try {assertEquals(ActionExecutionService.Dispatch.SETTLEMENT_PENDING,dispatcher.execute(id,0,authorizedActor()));}
                finally {db.execute("DROP TRIGGER IF EXISTS reject_measured_review_receipt");}
                var lost=actions.selectById(id);assertEquals("RUNNING",lost.getStatus());
                assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM iqc_result_review WHERE business_result_id=?",Integer.class,result.getId()));
                io.github.opensabre.common.core.util.UserContextHolder.getInstance().setContext(Map.of());
                assertTrue(context.getBean(ActionDispatchStateService.class).isolateLost(lost,Instant.now()));
                io.github.opensabre.common.core.util.UserContextHolder.getInstance().setContext(Map.of("user_name","annotator","user_id","user-annotator"));
                var quarantined=actions.selectById(id);assertEquals("REQUIRES_RECONCILIATION",quarantined.getStatus());
                assertEquals("UNKNOWN",quarantined.getOutcome());
                var reconcile=context.getBean(ActionReconciliationService.class);
                assertEquals("CONFIRMED",reconcile.reconcile(result.getId(),id,quarantined.getVersion(),"Verify the committed review without creating another").state());
                assertEquals("STALE_OBSERVATION",reconcile.reconcile(result.getId(),id,quarantined.getVersion(),"Repeat observation only").state());
                assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM iqc_result_review WHERE business_result_id=?",Integer.class,result.getId()));
                assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM iqc_action_reconciliation_attempt WHERE action_execution_id=? AND state='CONFIRMED'",Integer.class,id));
            } else assertEquals(ActionExecutionService.Dispatch.SETTLED,dispatcher.execute(id,0,authorizedActor()));
            assertEquals("COMPLETE",actions.selectById(id).getStatus());
            assertEquals("CONFIRMED_SUCCESS",actions.selectById(id).getOutcome());
            assertEquals(1,actions.selectById(id).getAttemptCount());
            assertEquals(ActionExecutionService.Dispatch.NOT_CLAIMED,dispatcher.execute(id,0,authorizedActor()));
        }
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM iqc_action_execution WHERE source_result_id=?",Integer.class,result.getId()));
        var reviewAction=actionIds.stream().map(actions::selectById).filter(row->"CREATE_REVIEW".equals(row.getActionType())).findFirst().orElseThrow();
        var reviews=context.getBean(BusinessItemReviewService.class);
        var pending=sql.getMapper(ResultReviewMapper.class).selectById(reviewAction.getResultReference());
        assertEquals("PENDING",pending.getStatus());assertEquals(result.getId(),pending.getBusinessResultId());
        var decision=new BusinessItemReviewEvaluator.Decision(result.getId(),"gap",InspectionScoring.ItemStatus.FAIL,
                InspectionScoring.ItemStatus.PASS,"Manual verification of processed conversation",List.of(first),"AI_FALSE_POSITIVE");
        actor.set("reviewer");
        var completed=reviews.decide(pending.getId(),1,false,List.of(decision),"Confirmed correction");
        assertEquals("COMPLETED",completed.getStatus());
        assertEquals(completed.getReviewedResultJson(),reviews.decide(pending.getId(),1,false,List.of(decision),"Confirmed correction").getReviewedResultJson());
        var projection=json.readValue(completed.getReviewedResultJson(),BusinessItemReviewEvaluator.Projection.class);
        assertEquals(0,new BigDecimal("80").compareTo(projection.original().scoring().finalScore()));
        assertEquals(0,new BigDecimal("100").compareTo(projection.reviewed().scoring().finalScore()));
        var unchanged=sql.getMapper(ConversationInspectionResultMapper.class).selectById(result.getId());
        assertEquals(result.getExecutionTraceJson(),unchanged.getExecutionTraceJson());
        assertEquals(result.getScoringResultJson(),unchanged.getScoringResultJson());
        assertEquals(result.getBusinessItemResultsJson(),unchanged.getBusinessItemResultsJson());
        actor.set("annotator");var candidates=context.getBean(BusinessReviewSampleService.class);
        String candidateRequest="candidate_"+suffix;
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->candidates.create(result.getId(),completed.getId(),candidateRequest,"Reviewed candidate","FALSE_POSITIVE","[]"));
        // The candidate guard rejects archive drift; restore the original test-owned input before capture.
        db.update("UPDATE iqc_conversation_message SET content='processed agent text',start_ms=2000,end_ms=3000 WHERE id=?",second);
        var captured=candidates.create(result.getId(),completed.getId(),candidateRequest,"Reviewed candidate","FALSE_POSITIVE","[]");
        assertEquals(captured.getId(),candidates.create(result.getId(),completed.getId(),candidateRequest,"Reviewed candidate","FALSE_POSITIVE","[]").getId());
        assertEquals(BusinessReviewSampleService.EXACT_INPUT_SCHEMA,json.readTree(captured.getContentSnapshot()).path("schemaVersion").asText());
        assertEquals(completed.getId(),json.readTree(captured.getExpectedJson()).path("reviewId").asText());
        assertEquals(0,new BigDecimal("100").compareTo(json.readTree(captured.getExpectedJson()).path("expected").path("scoring").path("finalScore").decimalValue()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM iqc_quality_sample WHERE source_result_id=?",Integer.class,result.getId()));
        // A reviewed candidate becomes an independently approved gold version, without recapturing live input.
        String candidateBody=captured.getContentSnapshot(),candidateLineage=captured.getExpectedJson();
        db.update("UPDATE iqc_conversation_message SET content='changed after candidate',start_ms=20000,end_ms=21000 WHERE id=?",second);
        gold.annotate(captured.getId(),new GoldSampleService.Annotation(0,List.of(new GoldFinding(definition.code(),Finding.ValueType.NUMBER,
                Finding.State.KNOWN,json.valueToTree(1000),List.of(first,second))),null,List.of("feedback"),GoldConversation.Difficulty.BASIC,
                new GoldDecision(new BigDecimal("100"),GoldDecision.ScoreStatus.FINAL,Map.of(),GoldDecision.Risk.NONE)));
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->gold.review(captured.getId(),1,true,"Self review rejected"));
        actor.set("gold-reviewer");var approved=gold.review(captured.getId(),1,true,"Independent feedback gold review");actor.set("annotator");
        var frozenGold=json.readValue(approved.getSnapshotJson(),GoldConversation.class);
        assertEquals(GoldConversation.EXACT_SCHEMA,frozenGold.schemaVersion());
        assertEquals("processed agent text",frozenGold.messages().get(1).content());
        assertEquals(2000L,frozenGold.messages().get(1).startMs());
        assertEquals(completed.getId(),frozenGold.businessContext().path("candidateLineage").path("reviewId").asText());
        var feedbackDataset=datasets.create("Feedback regression",new DatasetDraft(DatasetDraft.SCHEMA,DatasetDraft.Type.REGRESSION,null,
                List.of(new DatasetDraft.Member(captured.getId(),approved.getVersionNo()))));
        datasets.freeze(feedbackDataset.getId(),1);
        var feedbackTask=run(evaluations.create(feedbackDataset.getId(),1,asset.getId(),null,1,"Feedback regression",1,"feedback_"+suffix));
        var feedbackReport=evaluations.report(feedbackTask.getId(),feedbackTask.getCurrentExecutionId());
        assertTrue(feedbackReport.complete());assertEquals(1,feedbackReport.successfulSamples());
        assertEquals(0,BigDecimal.ONE.compareTo(feedbackReport.findingMetrics().aggregate().f1()));
        assertEquals(0,new BigDecimal("20").compareTo(feedbackReport.businessMetrics().meanAbsoluteScoreError()));
        assertEquals(0,BigDecimal.ZERO.compareTo(feedbackReport.regressionMetrics().passRate()));
        var draft=pipelines.frozenDraft(asset.getId(),1);
        var feedbackGate=context.getBean(PipelinePublicationGateService.class).evaluate(asset.getId(),1,draft.contentHash(),draft.release(),
                evidence.get(0),new PipelinePublicationGateService.ExecutionEvidence(feedbackTask.getId(),feedbackTask.getCurrentExecutionId()));
        assertFalse(feedbackGate.passed());
        assertTrue(feedbackGate.criteria().stream().anyMatch(c->"REGRESSION_PASS".equals(c.code())&&c.status()==PipelinePublicationGateService.Status.FAILED));
        var preserved=sql.getMapper(QualitySampleMapper.class).selectById(captured.getId());
        assertEquals(candidateBody,preserved.getContentSnapshot());assertEquals(candidateLineage,preserved.getExpectedJson());
    }
    @Test void controlledModelBridgePersistsIndependentComparableArtifacts() throws Exception {
        actor.set("annotator");
        io.github.opensabre.common.core.util.UserContextHolder.getInstance().setContext(Map.of("user_name","annotator","user_id","user-annotator"));
        var j=context.getBean(ObjectMapper.class);var s=context.getBean(SqlSessionTemplate.class);
        var db=new JdbcTemplate(context.getBean(DataSource.class));String key=UUID.randomUUID().toString().replace("-","");
        String conversation="model"+key,message="message"+key;
        db.update("INSERT INTO iqc_conversation(id,source_file_name,message_count,status,created_by,owner_group_id,source_fingerprint) VALUES (?,'processed conversation',1,'READY','annotator','group',?)",conversation,key);
        db.update("INSERT INTO iqc_conversation_message(id,conversation_id,sequence_no,speaker_role,content,relative_time,raw_line,line_number) VALUES (?,?,1,'agent','processed text','00:00:00','processed text',1)",message,conversation);
        var rule=new QualityRule();rule.setCode("R_"+key);rule.setName("Controlled rule");rule.setRuleType("LLM");rule.setExpression("Check processed text");rule.setTargetRole("all");rule.setStatus("ACTIVE");rule.setVersionNo(1);
        s.getMapper(QualityRuleMapper.class).insert(rule);
        var rv=new QualityRuleVersion();org.springframework.beans.BeanUtils.copyProperties(rule,rv);rv.setId(null);rv.setRuleId(rule.getId());rv.setStatus("PUBLISHED");s.getMapper(QualityRuleVersionMapper.class).insert(rv);
        var agent=new QualityAgent();agent.setCode("A_"+key);agent.setName("Controlled agent");agent.setStatus("ACTIVE");agent.setVersionNo(1);agent.setConfigJson("{\"schemaVersion\":\"3.0\",\"systemPrompt\":\"Controlled test\",\"primaryModelProfileId\":\"controlled\",\"assetSnapshots\":{\"primaryModel\":{\"id\":\"controlled\",\"versionNo\":1,\"provider\":\"OPENAI\",\"modelName\":\"controlled\",\"endpoint\":\"https://invalid.example/v1\"},\"mcpServers\":[],\"skills\":[]}}");s.getMapper(QualityAgentMapper.class).insert(agent);
        var av=new QualityAgentVersion();org.springframework.beans.BeanUtils.copyProperties(agent,av);av.setId(null);av.setAgentId(agent.getId());av.setStatus("PUBLISHED");s.getMapper(QualityAgentVersionMapper.class).insert(av);
        var definition=new FindingDefinition(FindingDefinition.SCHEMA,"MODEL_"+key.toUpperCase(),"Controlled boolean","GENERAL",null,Finding.ValueType.BOOLEAN,List.of(),Finding.Severity.LOW,false,List.of());
        var facts=context.getBean(FindingCatalogService.class);var catalog=facts.create(definition);facts.publish(catalog.getId(),1);
        var config=j.createObjectNode();config.putObject("ruleReference").put("id",rule.getId()).put("versionNo",1);config.putObject("findingReference").put("id",catalog.getId()).put("versionNo",1);config.putObject("agentReference").put("id",agent.getId()).put("versionNo",1);
        var node=new PipelineDefinition.Node("model","LLM","Controlled model","1",List.of(),Map.of(),config,null,3000,0,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
        var release=new PipelineRelease(PipelineRelease.EXACT_SCHEMA,new PipelineDefinition(PipelineDefinition.SCHEMA,List.of(node)),new FusionPolicy("priority",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION,InspectionScoring.Mode.DEDUCTION,100,60,List.of(new InspectionScoring.Item("check",20,false))),List.of(new PipelineRelease.ItemMapping("check",definition.code(),io.github.opensabre.iqc.scheme.SchemeDefinition.HitMeaning.VIOLATION)));
        var asset=context.getBean(PipelineAssetService.class).create("P_"+key.toUpperCase(),"Controlled model persistence",release);
        var sample=new QualitySample();sample.setName("Controlled gold");sample.setSampleType("POSITIVE");sample.setConversationId(conversation);sample.setMessageId(message);sample.setSourceResultId("manual"+key);sample.setContentSnapshot("processed text");sample.setStatus("CANDIDATE");sample.setCreatedBy("annotator");sample.setOwnerGroupId("group");s.getMapper(QualitySampleMapper.class).insert(sample);
        var gold=context.getBean(GoldSampleService.class);gold.annotate(sample.getId(),new GoldSampleService.Annotation(0,List.of(new GoldFinding(definition.code(),Finding.ValueType.BOOLEAN,Finding.State.KNOWN,j.valueToTree(true),List.of())),null,List.of(),GoldConversation.Difficulty.BASIC,null));actor.set("reviewer");gold.review(sample.getId(),1,true,"independent approval");actor.set("annotator");
        var datasets=context.getBean(EvaluationDatasetService.class);var dataset=datasets.create("Controlled model",new DatasetDraft(DatasetDraft.SCHEMA,DatasetDraft.Type.BASIC,null,List.of(new DatasetDraft.Member(sample.getId(),1))));datasets.freeze(dataset.getId(),1);
        var provider=context.getBean(LlmQualityProvider.class);
        // Exercise production prompt construction, parsing and router fingerprint generation.
        var model=mock(org.springframework.ai.chat.model.ChatModel.class);
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("{\"hit\":true,\"reason\":\"controlled\"}")))));
        ObjectProvider<org.springframework.ai.chat.model.ChatModel> defaults=mock(ObjectProvider.class);
        when(defaults.getIfAvailable()).thenReturn(model);
        var gateway=mock(io.github.opensabre.iqc.result.llm.gateway.ModelGateway.class);
        when(gateway.invokeTracked(any(),any(),any(),any(),any(),any())).thenAnswer(call->{
            var identity=(io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.ModelIdentity)call.getArgument(0);
            var output=((java.util.function.Supplier<org.springframework.ai.chat.model.ChatResponse>)call.getArgument(2)).get();
            return new io.github.opensabre.iqc.result.llm.gateway.ModelGateway.Invocation<>(output,new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.Handle("controlled-call",identity.key()));
        });
        var router=spy(new SnapshotChatModelRouter(defaults,j,mock(SecretReferenceResolver.class),gateway));doReturn(model).when(router).create(any());
        var properties=new LlmQualityProperties();properties.setModel("controlled");properties.setMaxAttempts(1);
        var delegate=new SpringAiLlmQualityProvider(defaults,router,j,mock(io.github.opensabre.governance.ratelimit.GovernanceRateLimiter.class),mock(io.github.opensabre.governance.usage.UsageCounterRecorder.class),properties,new SnapshotMcpToolProvider(j,reference->""));
        doAnswer(call->delegate.evaluateConversation(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4),call.getArgument(5)))
                .when(provider).evaluateConversation(anyList(),any(),any(),any(),anyString(),any());
        var evaluations=context.getBean(PipelineEvaluationService.class);
        var first=run(evaluations.create(dataset.getId(),1,asset.getId(),null,1,"first",1,"first"+key));
        var second=run(evaluations.create(dataset.getId(),1,asset.getId(),null,1,"second",1,"second"+key));
        var compared=evaluations.consistency(first.getId(),first.getCurrentExecutionId(),second.getId(),second.getCurrentExecutionId());
        assertEquals(1,compared.expectedPairs());assertEquals(1,compared.comparablePairs());assertEquals(1,compared.agreeingPairs());assertEquals(0,BigDecimal.ONE.compareTo(compared.agreementRate()));
        var duplicate=evaluations.consistency(first.getId(),first.getCurrentExecutionId(),first.getId(),first.getCurrentExecutionId());assertEquals(Map.of("SAME_ARTIFACT",1),duplicate.excludedReasons());
        verify(provider,times(2)).evaluateConversation(anyList(),any(),any(),any(),anyString(),any());
        // A second sample fails once; the original worker must retry only that sample.
        String failedConversation="retry"+key,failedMessage="retrymessage"+key;
        db.update("INSERT INTO iqc_conversation(id,source_file_name,message_count,status,created_by,owner_group_id,source_fingerprint) VALUES (?,'processed retry conversation',1,'READY','annotator','group',?)",failedConversation,"retry"+key);
        db.update("INSERT INTO iqc_conversation_message(id,conversation_id,sequence_no,speaker_role,content,relative_time,raw_line,line_number) VALUES (?,?,1,'agent','retry text','00:00:00','retry text',1)",failedMessage,failedConversation);
        var failedSample=new QualitySample();failedSample.setName("Retry gold");failedSample.setSampleType("POSITIVE");failedSample.setConversationId(failedConversation);failedSample.setMessageId(failedMessage);failedSample.setSourceResultId("retrymanual"+key);failedSample.setContentSnapshot("retry text");failedSample.setStatus("CANDIDATE");failedSample.setCreatedBy("annotator");failedSample.setOwnerGroupId("group");s.getMapper(QualitySampleMapper.class).insert(failedSample);
        gold.annotate(failedSample.getId(),new GoldSampleService.Annotation(0,List.of(new GoldFinding(definition.code(),Finding.ValueType.BOOLEAN,Finding.State.KNOWN,j.valueToTree(true),List.of())),null,List.of(),GoldConversation.Difficulty.BASIC,null));actor.set("reviewer");gold.review(failedSample.getId(),1,true,"independent retry approval");actor.set("annotator");
        var retryDataset=datasets.create("Retry model",new DatasetDraft(DatasetDraft.SCHEMA,DatasetDraft.Type.BASIC,null,List.of(new DatasetDraft.Member(sample.getId(),1),new DatasetDraft.Member(failedSample.getId(),1))));datasets.freeze(retryDataset.getId(),1);
        var recovered=new java.util.concurrent.atomic.AtomicBoolean(false);
        doAnswer(call->{
            List<io.github.opensabre.iqc.conversation.model.ConversationMessage> messages=call.getArgument(0);
            if(failedConversation.equals(messages.getFirst().getConversationId())&&!recovered.get())
                return new LlmQualityProvider.LlmEvaluation(false,false,"controlled temporary failure");
            return delegate.evaluateConversation(messages,call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4),call.getArgument(5));
        }).when(provider).evaluateConversation(anyList(),any(),any(),any(),anyString(),any());
        var runner=context.getBean(InspectionExecutionService.class);
        var queued=runner.queue(evaluations.create(retryDataset.getId(),1,asset.getId(),null,1,"partial",1,"partial"+key).getId());
        String priorId=queued.getCurrentExecutionId();runner.executePersisted(queued.getId(),priorId);
        var partial=s.getMapper(InspectionTaskMapper.class).selectById(queued.getId());assertEquals("PARTIAL_FAILED",partial.getStatus());
        var prior=evaluations.report(partial.getId(),priorId);assertEquals(1,prior.successfulSamples());
        recovered.set(true);var retried=run(partial);assertNotEquals(priorId,retried.getCurrentExecutionId());
        var retryReport=evaluations.report(retried.getId(),retried.getCurrentExecutionId());assertTrue(retryReport.complete());assertEquals(2,retryReport.successfulSamples());
        assertEquals(priorId,retryReport.sourceExecutionIds().get(sample.getId()));assertEquals(retried.getCurrentExecutionId(),retryReport.sourceExecutionIds().get(failedSample.getId()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM iqc_evaluation_sample_result WHERE task_id=? AND execution_id=?",Integer.class,retried.getId(),retried.getCurrentExecutionId()));
        var inherited=evaluations.consistency(retried.getId(),retried.getCurrentExecutionId(),retried.getId(),priorId);
        assertEquals(2,inherited.expectedPairs());assertEquals(0,inherited.comparablePairs());assertNull(inherited.agreementRate());
        assertEquals(Map.of("SAME_ARTIFACT",1,"UNSUCCESSFUL_NODE",1),inherited.excludedReasons());
        verify(provider,times(5)).evaluateConversation(anyList(),any(),any(),any(),anyString(),any());
        verify(model,times(4)).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        verify(gateway,times(4)).invokeTracked(any(),any(),any(),any(),any(),any());
    }
    private ScheduledActorAccessService.Resolved authorizedActor() {
        return new ScheduledActorAccessService.Resolved("user-annotator","annotator",false,"group",Set.of(),
                Set.of("iqc:pipeline:task:create","iqc:task:execute","iqc:action:enqueue","iqc:review:create"),Instant.now());
    }
    private InspectionTask run(InspectionTask created) {
        var runner=context.getBean(InspectionExecutionService.class);var queued=runner.queue(created.getId());
        runner.executePersisted(queued.getId(),queued.getCurrentExecutionId());
        var actual=context.getBean(SqlSessionTemplate.class).getMapper(InspectionTaskMapper.class).selectById(created.getId());
        assertEquals("SUCCEEDED",actual.getStatus(),()->actual.getRetryError()+" / "+actual.getTriggerError());return actual;
    }
    @Configuration @Import(IsolatedMysqlTaskExecutionStateIT.DatabaseConfiguration.class)
    static class Config {
        @Bean ObjectMapper json(){return new ObjectMapper().findAndRegisterModules();}
        @Bean IqcDataScope scope(){var s=mock(IqcDataScope.class);when(s.owner()).thenAnswer(call->actor.get());when(s.groupId()).thenReturn("group");when(s.canView(any(),any())).thenReturn(true);return s;}
        @Bean SqlSessionTemplate sql(SqlSessionFactory f){
            // Reuse the production framework filler; this test owns only the supplied user context.
            com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(f.getConfiguration())
                    .setMetaObjectHandler(new io.github.opensabre.persistence.handler.PoMetaObjectHandler());
            for(var type:List.of(QualityRuleMapper.class,QualityRuleVersionMapper.class,QualityAgentMapper.class,QualityAgentVersionMapper.class,ConversationMapper.class,ConversationMessageMapper.class,FindingCatalogMapper.class,FindingCatalogVersionMapper.class,
                    PipelineAssetMapper.class,PipelineAssetVersionMapper.class,QualitySampleMapper.class,QualitySampleVersionMapper.class,
                    EvaluationDatasetMapper.class,EvaluationDatasetVersionMapper.class,EvaluationSampleResultMapper.class,
                    ConversationInspectionResultMapper.class,RuleInspectionResultMapper.class,InspectionEvidenceMapper.class,InspectionResultMapper.class,ResultReviewMapper.class,ActionDiscoveryMapper.class))
                if(!f.getConfiguration().hasMapper(type))f.getConfiguration().addMapper(type);
            return new SqlSessionTemplate(f);
        }
        @Bean ScheduledActorAccessService actionActors(){var service=mock(ScheduledActorAccessService.class);when(service.capture()).thenReturn(new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1","user-annotator","annotator",false,"group"));return service;}
        @Bean BusinessItemReviewService reviews(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope){return new BusinessItemReviewService(s.getMapper(ResultReviewMapper.class),s.getMapper(ConversationInspectionResultMapper.class),s.getMapper(InspectionTaskMapper.class),s.getMapper(ConversationMessageMapper.class),scope,j);}
        @Bean BusinessReviewSampleService candidates(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope,BusinessItemReviewService reviews){return new BusinessReviewSampleService(s.getMapper(QualitySampleMapper.class),s.getMapper(ResultReviewMapper.class),s.getMapper(ConversationInspectionResultMapper.class),s.getMapper(InspectionTaskMapper.class),s.getMapper(ConversationMapper.class),s.getMapper(ConversationMessageMapper.class),reviews,scope,j);}
        @Bean ActionProductionSourceValidator sources(SqlSessionTemplate s,PipelineAssetService assets,ProductionInputSnapshotService inputs,ObjectMapper j){return new ActionProductionSourceValidator(s.getMapper(InspectionTaskMapper.class),s.getMapper(TaskExecutionMapper.class),s.getMapper(ConversationInspectionResultMapper.class),assets,inputs,j);}
        @Bean BusinessReviewActionService reviewActions(ActionProductionSourceValidator sources,BusinessItemReviewService reviews,IqcDataScope scope,ObjectMapper j){return new BusinessReviewActionService(sources,reviews,scope,j);}
        @Bean ActionAdapterRegistry adapters(SqlSessionTemplate s,ObjectMapper j,BusinessReviewActionService reviews){return new ActionAdapterRegistry(List.of(new SavedResultActionAdapter(s.getMapper(ConversationInspectionResultMapper.class),j),new BusinessReviewActionAdapter(reviews,j)));}
        @Bean ActionEnqueueService enqueue(ActionProductionSourceValidator sources,SqlSessionTemplate s,ActionAdapterRegistry adapters,IqcDataScope scope,ObjectMapper j){return new ActionEnqueueService(sources,s.getMapper(ActionExecutionMapper.class),s.getMapper(ActionDiscoveryMapper.class),adapters,scope,j);}
        @Bean ActionDispatchStateService dispatchStates(SqlSessionTemplate s){return new ActionDispatchStateService(s.getMapper(ActionExecutionMapper.class));}
        @Bean ActionReconciliationService reconcile(SqlSessionTemplate s,ActionAdapterRegistry adapters,ScheduledActorAccessService actors,IqcDataScope scope,ObjectMapper j,ActionReconciliationStateService state,ActionReconciliationAttemptService attempts){return new ActionReconciliationService(s.getMapper(ActionExecutionMapper.class),adapters,s.getMapper(ConversationInspectionResultMapper.class),s.getMapper(InspectionTaskMapper.class),actors,scope,j,state,attempts);}
        @Bean ActionClaimService claims(SqlSessionTemplate s,ActionProductionSourceValidator sources,ActionAdapterRegistry adapters,IqcDataScope scope,ObjectMapper j){return new ActionClaimService(s.getMapper(ActionExecutionMapper.class),sources,adapters,scope,j);}
        @Bean ActionSettlementService settlements(SqlSessionTemplate s,ActionAdapterRegistry adapters,ObjectMapper j){return new ActionSettlementService(s.getMapper(ActionExecutionMapper.class),adapters,j);}
        @Bean ActionExecutionService actionRunner(ActionClaimService claims,ActionSettlementService settlements,ActionAdapterRegistry adapters,SqlSessionTemplate s,IqcDataScope scope,ObjectMapper j){return new ActionExecutionService(claims,settlements,adapters,s.getMapper(ActionExecutionMapper.class),scope,j);}
        @Bean FindingCatalogService findings(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope){return new FindingCatalogService(s.getMapper(FindingCatalogMapper.class),s.getMapper(FindingCatalogVersionMapper.class),j,scope);}
        @Bean LlmQualityProvider model(){return mock(LlmQualityProvider.class);}
        @Bean(destroyMethod="close") PipelineRuntime runtime(ObjectMapper j,ObjectProvider<InspectionExecutionService> runner){return new PipelineRuntime(List.of(new TranscriptMetricEngine(j),new FrozenDetectorEngine("LLM",j,runner)),1,8);}
        @Bean PipelineAssetService pipelines(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope,PipelineRuntime runtime,FindingCatalogService facts,ObjectProvider<PipelinePublicationGateService> gates){
            return new PipelineAssetService(s.getMapper(PipelineAssetMapper.class),s.getMapper(PipelineAssetVersionMapper.class),s.getMapper(QualityRuleMapper.class),s.getMapper(QualityRuleVersionMapper.class),s.getMapper(QualityAgentMapper.class),s.getMapper(QualityAgentVersionMapper.class),mock(io.github.opensabre.iqc.agent.AgentAssetReferenceValidator.class),facts,j,scope,runtime,s.getMapper(InspectionTaskMapper.class),s.getMapper(ConversationInspectionResultMapper.class),gates);
        }
        @Bean ProductionInputSnapshotService inputs(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope){return new ProductionInputSnapshotService(s.getMapper(ConversationMapper.class),s.getMapper(ConversationMessageMapper.class),scope,j);}
        @Bean @Primary InspectionTaskService tasks(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope,ProductionInputSnapshotService inputs,ObjectProvider<PipelineAssetService> pipelines,ObjectProvider<ScheduledActorAccessService> actors){return new InspectionTaskService(s.getMapper(InspectionTaskMapper.class),s.getMapper(ConversationMapper.class),null,null,null,j,s.getMapper(TaskExecutionMapper.class),scope,null,null,inputs,pipelines,actors);}
        @Bean GoldSampleService gold(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope){return new GoldSampleService(s.getMapper(QualitySampleMapper.class),s.getMapper(QualitySampleVersionMapper.class),s.getMapper(ConversationMapper.class),s.getMapper(ConversationMessageMapper.class),scope,j);}
        @Bean EvaluationDatasetService datasets(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope,GoldSampleService gold){return new EvaluationDatasetService(s.getMapper(EvaluationDatasetMapper.class),s.getMapper(EvaluationDatasetVersionMapper.class),gold,scope,j);}
        @Bean PipelineConversationEvaluator evaluator(PipelineRuntime runtime,ObjectMapper j,SqlSessionTemplate s){return new PipelineConversationEvaluator(runtime,j,s.getMapper(InspectionTaskMapper.class));}
        @Bean PipelineEvaluationService evaluations(EvaluationDatasetService datasets,PipelineAssetService pipelines,InspectionTaskService tasks,SqlSessionTemplate s,PipelineConversationEvaluator evaluator,ObjectMapper j,IqcDataScope scope){return new PipelineEvaluationService(datasets,pipelines,tasks,s.getMapper(InspectionTaskMapper.class),s.getMapper(TaskExecutionMapper.class),s.getMapper(TaskItemMapper.class),s.getMapper(EvaluationSampleResultMapper.class),evaluator,j,scope);}
        @Bean PipelinePublicationGateService gates(PipelineEvaluationService evaluations,ObjectMapper j,SqlSessionTemplate s){return new PipelinePublicationGateService(evaluations,j,s.getMapper(TaskExecutionMapper.class));}
        @Bean HierarchicalResultService hierarchy(SqlSessionTemplate s,ObjectMapper j){return new HierarchicalResultService(s.getMapper(ConversationInspectionResultMapper.class),s.getMapper(RuleInspectionResultMapper.class),s.getMapper(InspectionEvidenceMapper.class),j,null,s.getMapper(InspectionTaskMapper.class),s.getMapper(InspectionResultMapper.class),s.getMapper(TaskItemMapper.class));}
        @Bean InspectionExecutionService runner(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope,HierarchicalResultService hierarchy,PipelineAssetService pipelines,PipelineConversationEvaluator evaluator,PipelineEvaluationService evaluations,LlmQualityProvider model){return new InspectionExecutionService(s.getMapper(InspectionTaskMapper.class),s.getMapper(ConversationMapper.class),s.getMapper(ConversationMessageMapper.class),s.getMapper(InspectionResultMapper.class),j,s.getMapper(TaskExecutionMapper.class),s.getMapper(TaskItemMapper.class),scope,model,mock(io.github.opensabre.governance.usage.UsageCounterRecorder.class),hierarchy,mock(io.github.opensabre.iqc.label.LabelCandidateService.class),mock(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.class),pipelines,evaluator,evaluations);}
    }
}
