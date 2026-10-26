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

/** Real SQL, catalog, independent gold review, datasets, workers and measured publication; controlled model only, no supplier calls. */
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
        String manifest=json.createObjectNode().put("iqcInputManifestVersion","iqc-input-count-manifest-v1")
                .put("iqcExpectedMessageCount",3).put("iqcInputManifestRef","export-"+suffix).put("privateField","not in message Gold").toString();
        db.update("UPDATE iqc_conversation SET business_fields_json=? WHERE id=?",manifest,conversation);
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
            var completeness=report.inputCompletenessMetrics();
            assertEquals(1,completeness.measuredSamples());assertEquals(1,completeness.deficientSamples());
            assertEquals(3,completeness.expectedMessages());assertEquals(2,completeness.observedMessages());
            assertEquals(1,completeness.missingMessages());
            assertEquals(0,new BigDecimal("0.666667").compareTo(completeness.declaredMessageCoverage()));
            var messageGold=gold.read(gold.approvedVersion(sample.getId(),1).getSnapshotJson(),GoldConversation.class);
            assertEquals(3,messageGold.businessContext().at("/conversation/businessContext/businessFields").size());
            assertTrue(messageGold.businessContext().at("/conversation/businessContext/businessFields/privateField").isMissingNode());
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
        assertArchiveReceiptExecutesPublishedPipeline(prepared,conversation,suffix);
        assertBulkMaterializationRollsBackAndRetainsFrozenInput(prepared,conversation,suffix);
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
        assertFindingDistributionFromSql(production,result,false);
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
        assertFindingDistributionFromSql(production,unchanged,true);
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
        db.update("UPDATE iqc_conversation SET business_fields_json=? WHERE id=?",
                json.createObjectNode().put("iqcInputManifestVersion","iqc-input-count-manifest-v1")
                        .put("iqcExpectedMessageCount",99).put("iqcInputManifestRef","changed-after-candidate").toString(),conversation);
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
        assertEquals(3,frozenGold.businessContext().at("/conversation/businessContext/businessFields/iqcExpectedMessageCount").intValue());
        assertEquals("export-"+suffix,frozenGold.businessContext().at("/conversation/businessContext/businessFields/iqcInputManifestRef").asText());
        var feedbackDataset=datasets.create("Feedback regression",new DatasetDraft(DatasetDraft.SCHEMA,DatasetDraft.Type.REGRESSION,null,
                List.of(new DatasetDraft.Member(captured.getId(),approved.getVersionNo()))));
        datasets.freeze(feedbackDataset.getId(),1);
        var feedbackTask=run(evaluations.create(feedbackDataset.getId(),1,asset.getId(),null,1,"Feedback regression",1,"feedback_"+suffix));
        var feedbackReport=evaluations.report(feedbackTask.getId(),feedbackTask.getCurrentExecutionId());
        assertTrue(feedbackReport.complete());assertEquals(1,feedbackReport.successfulSamples());
        assertEquals(3,feedbackReport.inputCompletenessMetrics().expectedMessages());
        assertEquals(2,feedbackReport.inputCompletenessMetrics().observedMessages());
        assertEquals(1,feedbackReport.inputCompletenessMetrics().missingMessages());
        // Both approved source paths retain their own manifest after live archive metadata has changed.
        var originalReport=evaluations.report(evidence.getFirst().taskId(),evidence.getFirst().executionId());
        assertEquals(3,originalReport.inputCompletenessMetrics().expectedMessages());
        assertEquals(99,json.readTree(db.queryForObject("SELECT business_fields_json FROM iqc_conversation WHERE id=?",String.class,conversation))
                .path("iqcExpectedMessageCount").intValue());
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
        // Review facts through the owning request/context/decide path, after the original feedback lineage was frozen.
        var reportService=context.getBean(io.github.opensabre.iqc.quality.BusinessQualityReportService.class);
        actor.set("annotator");
        var findingContext=reviews.findingContext(result.getId());assertTrue(findingContext.available());
        var entry=findingContext.findings().getFirst();
        var factReview=reviews.request(result.getId(),completed.getReviewRevision(),"fact-review-"+suffix,"Verify processed conversation fact");
        var whilePending=reportService.report(List.of(production.getId()),null,"SELECTED_RUNS",false,false,true).findingDistributions().values().iterator().next();
        assertEquals(1,whilePending.reviewed().facts().getFirst().knownSeverityCounts().get("HIGH"));
        var correction=new io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.Decision(result.getId(),entry.code(),entry.expectedFindingHash(),Finding.State.KNOWN,json.valueToTree(0),Finding.Severity.NONE,"Correct measured fact after manual verification",entry.evidence().stream().map(BusinessItemReviewService.FindingEvidence::id).toList(),List.of());
        actor.set("reviewer");
        var corrected=reviews.decide(factReview.getId(),factReview.getReviewRevision(),false,List.of(),List.of(correction),"Verified fact correction");
        assertEquals(corrected.getReviewedResultJson(),reviews.decide(factReview.getId(),factReview.getReviewRevision(),false,List.of(),List.of(correction),"Verified fact correction").getReviewedResultJson());
        var correctedReport=reportService.report(List.of(production.getId()),null,"SELECTED_RUNS",false,false,true);
        var correctedDistribution=correctedReport.findingDistributions().values().iterator().next();
        assertEquals(1,correctedDistribution.machine().facts().getFirst().knownSeverityCounts().get("HIGH"));
        assertEquals(1,correctedDistribution.reviewed().facts().getFirst().knownSeverityCounts().get("NONE"));
        assertEquals(0,correctedDistribution.reviewed().facts().getFirst().knownSeverityCounts().get("HIGH"));
        assertEquals(1,correctedReport.groups().getFirst().reviewChanges().findingChangedResults());
        actor.set("annotator");
        var unknownReview=reviews.request(result.getId(),corrected.getReviewRevision(),"unknown-review-"+suffix,"Preserve uncertainty instead of false");
        var unknown=new io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.Decision(result.getId(),entry.code(),entry.expectedFindingHash(),Finding.State.UNKNOWN,null,Finding.Severity.LOW,"Insufficient evidence for canonical value",entry.evidence().stream().map(BusinessItemReviewService.FindingEvidence::id).toList(),List.of());
        actor.set("reviewer");reviews.decide(unknownReview.getId(),unknownReview.getReviewRevision(),false,List.of(),List.of(unknown),"Uncertainty confirmed");
        var unknownDistribution=reportService.report(List.of(production.getId()),null,"SELECTED_RUNS",false,false,true).findingDistributions().values().iterator().next();
        var unknownFact=unknownDistribution.reviewed().facts().getFirst();assertEquals(1,unknownFact.states().get("UNKNOWN"));assertEquals(0,unknownFact.states().get("KNOWN"));
        assertEquals(0,unknownFact.knownSeverityCounts().values().stream().mapToInt(Integer::intValue).sum());assertEquals(0,unknownFact.trueCount()+unknownFact.falseCount());
        var originalAfterFactReview=sql.getMapper(ConversationInspectionResultMapper.class).selectById(result.getId());
        assertEquals(result.getExecutionTraceJson(),originalAfterFactReview.getExecutionTraceJson());assertEquals(result.getScoringResultJson(),originalAfterFactReview.getScoringResultJson());
        var frozenCandidateAfterReview=sql.getMapper(QualitySampleMapper.class).selectById(captured.getId());
        assertEquals(candidateBody,frozenCandidateAfterReview.getContentSnapshot());assertEquals(candidateLineage,frozenCandidateAfterReview.getExpectedJson());

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
        String labelId="label"+key, labelGroup="labelgroup"+key, labelCategory="category"+key;
        db.update("INSERT INTO iqc_label_category(id,name,code) VALUES (?,'Controlled category',?)",labelCategory,labelCategory);
        db.update("INSERT INTO iqc_label_group(id,category_id,name,code) VALUES (?,?,'Controlled group',?)",labelGroup,labelCategory,labelGroup);
        db.update("INSERT INTO iqc_label(id,group_id,name,code,status,version_no,target_role,created_by,owner_group_id) VALUES (?,?,'Controlled label',?,'PUBLISHED',1,'all','annotator','group')",labelId,labelGroup,labelId);
        db.update("INSERT INTO iqc_label_value_definition(id,label_id,value_code,value_type) VALUES (?,?,'hit','BOOLEAN')","value"+key,labelId);
        db.update("INSERT INTO iqc_label_rule_binding(id,label_id,rule_id,rule_version_no) VALUES (?,?,?,1)","binding"+key,labelId,rule.getId());
        var labelPolicy=new io.github.opensabre.iqc.label.FindingLabelPolicy(io.github.opensabre.iqc.label.FindingLabelPolicy.SCHEMA,
                List.of(new io.github.opensabre.iqc.label.FindingLabelPolicy.Binding(labelId,1,"hit",definition.code())),null);
        String labelAddress=io.github.opensabre.iqc.label.FindingLabelPolicy.bindingAddress(labelPolicy.bindings().getFirst());
        release=new PipelineRelease(release.schemaVersion(),release.pipeline(),release.fusion(),release.scoring(),release.items(),null,null,labelPolicy);
        var asset=context.getBean(PipelineAssetService.class).create("P_"+key.toUpperCase(),"Controlled model persistence",release);
        var sample=new QualitySample();sample.setName("Controlled gold");sample.setSampleType("POSITIVE");sample.setConversationId(conversation);sample.setMessageId(message);sample.setSourceResultId("manual"+key);sample.setContentSnapshot("processed text");sample.setStatus("CANDIDATE");sample.setCreatedBy("annotator");sample.setOwnerGroupId("group");s.getMapper(QualitySampleMapper.class).insert(sample);
        var gold=context.getBean(GoldSampleService.class);gold.annotate(sample.getId(),new GoldSampleService.Annotation(0,List.of(new GoldFinding(definition.code(),Finding.ValueType.BOOLEAN,Finding.State.KNOWN,j.valueToTree(true),List.of())),null,List.of(),GoldConversation.Difficulty.BASIC,new GoldDecision(null,null,Map.of(labelAddress,j.valueToTree(true)),null)));actor.set("reviewer");gold.review(sample.getId(),1,true,"independent approval");actor.set("annotator");
        var datasets=context.getBean(EvaluationDatasetService.class);var dataset=datasets.create("Controlled model",new DatasetDraft(DatasetDraft.SCHEMA,DatasetDraft.Type.BASIC,null,List.of(new DatasetDraft.Member(sample.getId(),1))));datasets.freeze(dataset.getId(),1);
        var provider=context.getBean(LlmQualityProvider.class);
        // Exercise production prompt construction, parsing and router fingerprint generation.
        var model=mock(org.springframework.ai.chat.model.ChatModel.class);
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("{\"hit\":true,\"reason\":\"controlled\",\"confidence\":0.75,\"evidence\":[]}")))));
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
        var citationReport=evaluations.report(first.getId(),first.getCurrentExecutionId());
        assertEquals(1,citationReport.citationGroundingMetrics().measuredNodeSamples());
        assertEquals(0,citationReport.citationGroundingMetrics().claimedQuotes());
        assertNull(citationReport.citationGroundingMetrics().observedQuoteErrorRate());
        assertEquals(0,BigDecimal.ONE.compareTo(citationReport.citationGroundingMetrics().measurementCoverage()));
        // Resolve the original fitted evidence through the real transactional proxy and SQL mappers.
        var calibration=citationReport.nodeConfidenceMetrics().nodes().getFirst().calibrationCandidate();
        assertNotNull(calibration);assertEquals(1,calibration.scored());
        var calibrationRef=new EmpiricalConfidenceCalibration.Reference(first.getId(),first.getCurrentExecutionId(),"model",calibration.contentHash());
        var target=evaluations.context(first.getId()).prepared().release().pipeline();
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                ()->evaluations.calibrationForTask(calibrationRef,target));
        var calibrationTx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        String originalGroup=db.queryForObject("SELECT owner_group_id FROM iqc_inspection_task WHERE id=?",String.class,first.getId());
        db.update("UPDATE iqc_inspection_task SET owner_group_id='calibration-group' WHERE id=?",first.getId());
        try {
            var resolved=calibrationTx.execute(status->evaluations.calibrationForTask(calibrationRef,target));
            assertNotNull(resolved);assertEquals(calibration,resolved.artifact());
            assertEquals("calibration-group",resolved.ownerGroupId());assertEquals("annotator",resolved.owner());
            calibrationTx.execute(status->{
                evaluations.calibrationForTask(calibrationRef,target);
                // A separate physical SQL session must not change the source while this transaction holds its row lock.
                try(var contender=context.getBean(DataSource.class).getConnection()) {
                    try(var settings=contender.createStatement()){settings.execute("SET SESSION innodb_lock_wait_timeout=1");}
                    try(var update=contender.prepareStatement("UPDATE iqc_inspection_task SET owner_group_id='concurrent-calibration' WHERE id=?")) {
                        update.setString(1,first.getId());
                        var blocked=assertThrows(java.sql.SQLException.class,update::executeUpdate);
                        assertEquals(1205,blocked.getErrorCode());
                    }
                } catch(java.sql.SQLException failure) {throw new IllegalStateException(failure);}
                assertEquals("calibration-group",db.queryForObject("SELECT owner_group_id FROM iqc_inspection_task WHERE id=?",String.class,first.getId()));
                return null;
            });
            assertEquals(1,db.update("UPDATE iqc_inspection_task SET owner_group_id='after-calibration-lock' WHERE id=?",first.getId()));
            assertEquals("after-calibration-lock",calibrationTx.execute(status->evaluations.calibrationForTask(calibrationRef,target)).ownerGroupId());
            var badRef=new EmpiricalConfidenceCalibration.Reference(first.getId(),first.getCurrentExecutionId(),"model","0".repeat(64));
            assertThrows(RuntimeException.class,()->calibrationTx.execute(status->evaluations.calibrationForTask(badRef,target)));
            assertEquals(calibration.contentHash(),evaluations.report(first.getId(),first.getCurrentExecutionId()).nodeConfidenceMetrics().nodes().getFirst().calibrationCandidate().contentHash());
        } finally { db.update("UPDATE iqc_inspection_task SET owner_group_id=? WHERE id=?",originalGroup,first.getId()); }
        // Exercise the owning task save boundary with real original-source reads and persisted private coefficients.
        var calibrationAccess=mock(io.github.opensabre.iqc.privacy.SensitiveReadAccessService.class);
        var freezer=new CalibrationTaskSnapshotService(context.getBeanProvider(PipelineEvaluationService.class),calibrationAccess,context.getBean(IqcDataScope.class),j);
        ObjectProvider<CalibrationTaskSnapshotService> freezerProvider=mock(ObjectProvider.class);
        when(freezerProvider.getObject()).thenReturn(freezer);
        var taskCreator=new InspectionTaskService(s.getMapper(InspectionTaskMapper.class),s.getMapper(ConversationMapper.class),null,null,null,j,
                s.getMapper(TaskExecutionMapper.class),context.getBean(IqcDataScope.class),null,null,context.getBean(ProductionInputSnapshotService.class),
                context.getBeanProvider(PipelineAssetService.class),context.getBeanProvider(ScheduledActorAccessService.class),null,freezerProvider);
        var originalRelease=evaluations.context(first.getId()).prepared().release();
        var calibratedFusion=new FusionPolicy("calibrated",1,FusionPolicy.Method.CONFIDENCE,Map.of(),Map.of(),"original-evaluation",Set.of("model"),null,null,List.of(calibrationRef));
        var calibratedRelease=new PipelineRelease(originalRelease.schemaVersion(),originalRelease.pipeline(),calibratedFusion,originalRelease.scoring(),originalRelease.items(),null,null,originalRelease.labelPolicy());
        var calibratedPrepared=new PipelineAssetService.Prepared(asset.getId(),null,1,calibratedRelease,PipelineAssetService.hash(j.writeValueAsString(calibratedRelease)));
        var saved=calibrationTx.execute(status->taskCreator.createUsingPipelineSnapshot(calibratedPrepared,"Calibration save "+key,List.of(conversation),1,"calibrated"+key));
        assertNotNull(saved);
        var persistedCalibration=s.getMapper(InspectionTaskMapper.class).selectById(saved.getId());
        var frozenCalibration=CalibrationTaskSnapshotService.verifiedSources(persistedCalibration,j);
        assertEquals(1,frozenCalibration.size());assertEquals(calibration,frozenCalibration.getFirst().artifact());
        verify(calibrationAccess).current(Set.of("iqc:evaluation:report:view"));
        String rejectedName="Calibration revoked "+key;
        doThrow(io.github.opensabre.iqc.governance.IqcException.accessDenied("calibration revoked")).when(calibrationAccess).current(Set.of("iqc:evaluation:report:view"));
        assertThrows(RuntimeException.class,()->calibrationTx.execute(status->taskCreator.createUsingPipelineSnapshot(calibratedPrepared,rejectedName,List.of(conversation),1,"revoked-calibration"+key)));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE name=?",Integer.class,rejectedName));
        assertEquals(frozenCalibration,CalibrationTaskSnapshotService.verifiedSources(s.getMapper(InspectionTaskMapper.class).selectById(saved.getId()),j));
        var compared=evaluations.consistency(first.getId(),first.getCurrentExecutionId(),second.getId(),second.getCurrentExecutionId());
        assertEquals(1,compared.expectedPairs());assertEquals(1,compared.comparablePairs());assertEquals(1,compared.agreeingPairs());assertEquals(0,BigDecimal.ONE.compareTo(compared.agreementRate()));
        var duplicate=evaluations.consistency(first.getId(),first.getCurrentExecutionId(),first.getId(),first.getCurrentExecutionId());assertEquals(Map.of("SAME_ARTIFACT",1),duplicate.excludedReasons());
        verify(provider,times(2)).evaluateConversation(anyList(),any(),any(),any(),anyString(),any());
        // A second sample fails once; the original worker must retry only that sample.
        String failedConversation="retry"+key,failedMessage="retrymessage"+key;
        db.update("INSERT INTO iqc_conversation(id,source_file_name,message_count,status,created_by,owner_group_id,source_fingerprint) VALUES (?,'processed retry conversation',1,'READY','annotator','group',?)",failedConversation,"retry"+key);
        db.update("INSERT INTO iqc_conversation_message(id,conversation_id,sequence_no,speaker_role,content,relative_time,raw_line,line_number) VALUES (?,?,1,'agent','retry text','00:00:00','retry text',1)",failedMessage,failedConversation);
        var failedSample=new QualitySample();failedSample.setName("Retry gold");failedSample.setSampleType("POSITIVE");failedSample.setConversationId(failedConversation);failedSample.setMessageId(failedMessage);failedSample.setSourceResultId("retrymanual"+key);failedSample.setContentSnapshot("retry text");failedSample.setStatus("CANDIDATE");failedSample.setCreatedBy("annotator");failedSample.setOwnerGroupId("group");s.getMapper(QualitySampleMapper.class).insert(failedSample);
        gold.annotate(failedSample.getId(),new GoldSampleService.Annotation(0,List.of(new GoldFinding(definition.code(),Finding.ValueType.BOOLEAN,Finding.State.KNOWN,j.valueToTree(true),List.of())),null,List.of(),GoldConversation.Difficulty.BASIC,new GoldDecision(null,null,Map.of(labelAddress,j.valueToTree(true)),null)));actor.set("reviewer");gold.review(failedSample.getId(),1,true,"independent retry approval");actor.set("annotator");
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
        assertEquals(2,prior.citationGroundingMetrics().expectedNodeSamples());
        assertEquals(1,prior.citationGroundingMetrics().measuredNodeSamples());
        assertEquals(1,prior.citationGroundingMetrics().unmeasuredNodeSamples());
        assertEquals(0,new BigDecimal("0.5").compareTo(prior.citationGroundingMetrics().measurementCoverage()));
        assertEquals(2,prior.businessMetrics().labelGold());assertEquals(1,prior.businessMetrics().comparableLabels());
        assertEquals(0,BigDecimal.ONE.compareTo(prior.businessMetrics().labelAccuracy()));
        recovered.set(true);var retried=run(partial);assertNotEquals(priorId,retried.getCurrentExecutionId());
        var retryReport=evaluations.report(retried.getId(),retried.getCurrentExecutionId());assertTrue(retryReport.complete());assertEquals(2,retryReport.successfulSamples());
        assertEquals(2,retryReport.citationGroundingMetrics().expectedNodeSamples());
        assertEquals(2,retryReport.citationGroundingMetrics().measuredNodeSamples());
        assertEquals(0,retryReport.citationGroundingMetrics().claimedQuotes());
        assertNull(retryReport.citationGroundingMetrics().observedQuoteErrorRate());
        assertEquals(0,BigDecimal.ONE.compareTo(retryReport.citationGroundingMetrics().measurementCoverage()));
        assertEquals(2,retryReport.businessMetrics().labelGold());assertEquals(2,retryReport.businessMetrics().comparableLabels());
        assertEquals(0,BigDecimal.ONE.compareTo(retryReport.businessMetrics().labelAccuracy()));
        assertEquals(priorId,retryReport.sourceExecutionIds().get(sample.getId()));assertEquals(retried.getCurrentExecutionId(),retryReport.sourceExecutionIds().get(failedSample.getId()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM iqc_evaluation_sample_result WHERE task_id=? AND execution_id=?",Integer.class,retried.getId(),retried.getCurrentExecutionId()));
        var inherited=evaluations.consistency(retried.getId(),retried.getCurrentExecutionId(),retried.getId(),priorId);
        assertEquals(2,inherited.expectedPairs());assertEquals(0,inherited.comparablePairs());assertNull(inherited.agreementRate());
        assertEquals(Map.of("SAME_ARTIFACT",1,"UNSUCCESSFUL_NODE",1),inherited.excludedReasons());
        verify(provider,times(5)).evaluateConversation(anyList(),any(),any(),any(),anyString(),any());
        // Real MVC transport reads the same SQL artifacts; only current-grant lookup is isolated.
        var grants=mock(io.github.opensabre.iqc.privacy.SensitiveReadAccessService.class);
        when(grants.current(anySet())).thenReturn(new io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access("controlled",false,false,false,Instant.now()));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new io.github.opensabre.iqc.rest.PipelineEvaluationController(evaluations,grants,new io.github.opensabre.iqc.privacy.ResultReadProjection(j)))
                .setControllerAdvice(new io.github.opensabre.webmvc.rest.RestResponseBodyAdvice(),new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice(),new io.github.opensabre.webmvc.exception.DefaultWebMvcExceptionHandlerAdvice()).build();
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/iqc/evaluation/tasks/{task}/report",retried.getId())
                .param("executionId",retried.getCurrentExecutionId()).param("referenceTaskId",retried.getId()).param("referenceExecutionId",priorId))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","private, no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Content-Type-Options","nosniff"))
                .andReturn().getResponse().getContentAsString();
        var envelope=j.readTree(response);assertEquals("000000",envelope.path("code").asText());
        assertEquals(j.readTree(j.writeValueAsString(inherited)),envelope.path("data"));
        assertFalse(response.contains("processed text"));assertFalse(response.contains("retry text"));assertFalse(response.contains("inputHash"));
        var ordinary=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/iqc/evaluation/tasks/{task}/report",retried.getId())
                .param("executionId",retried.getCurrentExecutionId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","private, no-store"))
                .andReturn().getResponse().getContentAsString();
        assertEquals("000000",j.readTree(ordinary).path("code").asText());
        assertEquals(j.readTree(j.writeValueAsString(retryReport)),j.readTree(ordinary).path("data"));
        assertEquals(2,j.readTree(ordinary).at("/data/businessMetrics/comparableLabels").asInt());
        assertEquals(priorId,j.readTree(ordinary).path("data").path("sourceExecutionIds").path(sample.getId()).asText());
        var incomplete=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/iqc/evaluation/tasks/{task}/report",retried.getId())
                .param("executionId",retried.getCurrentExecutionId()).param("referenceTaskId",retried.getId()))
                .andReturn().getResponse().getContentAsString();
        assertEquals("IQC-1003",j.readTree(incomplete).path("code").asText());
        assertTrue(j.readTree(incomplete).path("data").isNull()||j.readTree(incomplete).path("data").isMissingNode());
        when(grants.current(anySet())).thenThrow(new IllegalStateException("controlled grant unavailable"));
        var denied=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/iqc/evaluation/tasks/{task}/report",retried.getId())
                .param("executionId",retried.getCurrentExecutionId()).param("referenceTaskId",retried.getId()).param("referenceExecutionId",priorId))
                .andReturn().getResponse().getContentAsString();
        assertEquals("IQC-1006",j.readTree(denied).path("code").asText());assertTrue(j.readTree(denied).path("data").isNull()||j.readTree(denied).path("data").isMissingNode());
        verify(grants,times(4)).current(Set.of("iqc:evaluation:report:view"));
        verifyConversationCosts(db,s,retried,first,conversation,failedConversation,priorId);
        verify(model,times(4)).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        verify(gateway,times(4)).invokeTracked(any(),any(),any(),any(),any(),any());
    }
    /** Synthetic accounting rows exercise actual MySQL filtering and the shared currency result map. */
    private void verifyConversationCosts(JdbcTemplate db,SqlSessionTemplate sql,InspectionTask task,InspectionTask other,
            String conversation,String otherConversation,String priorId) {
        String run=task.getCurrentExecutionId(), prefix="cost"+UUID.randomUUID().toString().replace("-","");
        insertCost(db,prefix+"1",task.getId(),run,conversation,"USD","CONFIRMED","1.25");
        insertCost(db,prefix+"2",task.getId(),run,conversation,"USD","UNKNOWN",null);
        insertCost(db,prefix+"3",task.getId(),run,conversation,"USD","CONFIRMED","0.75");
        insertCost(db,prefix+"4",task.getId(),run,conversation,"EUR","CONFIRMED","2.50");
        insertCost(db,prefix+"5",task.getId(),run,otherConversation,"USD","CONFIRMED","50");
        insertCost(db,prefix+"6",task.getId(),priorId,conversation,"USD","CONFIRMED","3");
        insertCost(db,prefix+"7",other.getId(),other.getCurrentExecutionId(),conversation,"USD","CONFIRMED","99");
        insertCost(db,prefix+"8",task.getId(),run,null,"USD","CONFIRMED","4");
        var ledger=context.getBean(io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.class);
        var first=ledger.taskPageWithSummary(task.getId(),run,null,1,2,conversation);
        var second=ledger.taskPageWithSummary(task.getId(),run,null,2,2,conversation);
        assertEquals(4,first.total());assertEquals(2,first.records().size());assertEquals(2,second.records().size());
        assertEquals(first.costSummary(),second.costSummary());assertEquals("4",first.costSummary().callCount());
        assertEquals("3",first.costSummary().knownCount());assertEquals("1",first.costSummary().unknownCount());
        var ids=new java.util.HashSet<String>();
        java.util.stream.Stream.concat(first.records().stream(),second.records().stream()).forEach(row->{
            assertEquals(task.getId(),row.taskId());assertEquals(run,row.executionId());assertEquals(conversation,row.conversationId());
            assertTrue(ids.add(row.callId()));
        });
        assertEquals(2,first.costSummary().currencies().size());
        for(var group:first.costSummary().currencies()) {
            assertEquals(0,new BigDecimal("USD".equals(group.currency())?"2":"2.5").compareTo(new BigDecimal(group.knownSubtotal())));
        }
        var unknown=ledger.taskPageWithSummary(task.getId(),run,"UNKNOWN",1,2,conversation);
        assertEquals(1,unknown.total());assertEquals("0",unknown.costSummary().knownCount());
        assertNull(unknown.costSummary().currencies().getFirst().knownSubtotal());
        var historical=ledger.taskPageWithSummary(task.getId(),null,null,1,10,conversation);
        assertEquals(5,historical.total());assertEquals("5",historical.costSummary().callCount());
        var empty=ledger.taskPageWithSummary(task.getId(),run,null,1,2,"no-matching-conversation");
        assertEquals(0,empty.total());assertEquals("0",empty.costSummary().callCount());assertTrue(empty.costSummary().currencies().isEmpty());
        // Old four-parameter mapper must tolerate the missing conversationId key and share the result map.
        var mapper=sql.getMapper(io.github.opensabre.iqc.result.llm.gateway.GatewayCallMapper.class);
        assertEquals(6,mapper.costGroups(null,task.getId(),run,null).stream().mapToLong(g->g.getCallCount()).sum());
        assertEquals(4,mapper.conversationCostGroups(null,task.getId(),run,null,conversation).stream().mapToLong(g->g.getCallCount()).sum());
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->ledger.taskPageWithSummary(task.getId(),other.getCurrentExecutionId(),null,1,2,conversation));
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->ledger.taskPageWithSummary(task.getId(),run,null,1,2,"bad/id"));
        var scope=context.getBean(IqcDataScope.class);when(scope.canView(any(),any())).thenReturn(false);
        try { assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->ledger.taskPageWithSummary(task.getId(),run,null,1,2,conversation)); }
        finally { when(scope.canView(any(),any())).thenReturn(true); }
    }
    private void insertCost(JdbcTemplate db,String id,String task,String run,String conversation,String currency,String status,String amount) {
        db.update("INSERT INTO iqc_model_gateway_call(call_id,model_key,task_id,execution_id,conversation_id,status,reserved_at_utc,pricing_currency,calculated_cost,cost_status) VALUES (?,?,?,?,?,?,UTC_TIMESTAMP(3),?,?,?)",
                id,"a".repeat(64),task,run,conversation,status,currency,amount==null?null:new BigDecimal(amount),amount==null?"UNKNOWN":"CALCULATED_FROM_DECLARED_PRICE");
    }
    /** Actual SQL publication, rollback, immutable history and task transaction boundaries. */
    @Test void knowledgeLifecyclePersistsImmutableVersionsAndRequiresTaskTransaction() throws Exception {
        actor.set("annotator");
        io.github.opensabre.common.core.util.UserContextHolder.getInstance().setContext(Map.of("user_name","annotator","user_id","user-annotator"));
        var knowledge=context.getBean(io.github.opensabre.iqc.knowledge.KnowledgeBaseService.class);
        var tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var corpus=new io.github.opensabre.iqc.knowledge.KnowledgeCorpus(io.github.opensabre.iqc.knowledge.KnowledgeCorpus.SCHEMA,
                List.of(new io.github.opensabre.iqc.knowledge.KnowledgeCorpus.Document("reference","Reference","frozen reference text","source:manual")));
        var created=knowledge.create("K_"+UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT),"Reference corpus",corpus);
        assertEquals("DISABLED",created.getStatus());assertEquals("annotator",created.getCreatedBy());
        var db=new JdbcTemplate(context.getBean(DataSource.class));
        db.execute("DROP TRIGGER IF EXISTS reject_knowledge_parent_update");
        try {
            db.execute("CREATE TRIGGER reject_knowledge_parent_update BEFORE UPDATE ON iqc_knowledge_base FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected knowledge parent failure'");
            assertThrows(org.springframework.dao.DataAccessException.class,()->knowledge.publish(created.getId(),1));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM iqc_knowledge_base_version WHERE knowledge_base_id=?",Integer.class,created.getId()));
            assertNull(knowledge.get(created.getId()).getActivePublishedVersion());
            assertEquals(1,knowledge.get(created.getId()).getDraftRevision());
        } finally {db.execute("DROP TRIGGER IF EXISTS reject_knowledge_parent_update");}
        var ready=new java.util.concurrent.CyclicBarrier(2);
        var publishers=java.util.concurrent.Executors.newFixedThreadPool(2);
        io.github.opensabre.iqc.knowledge.model.KnowledgeBaseVersion first;
        try {
            java.util.concurrent.Callable<io.github.opensabre.iqc.knowledge.model.KnowledgeBaseVersion> publish=()->{
                io.github.opensabre.common.core.util.UserContextHolder.getInstance().setContext(Map.of("user_name","annotator","user_id","user-annotator"));
                try {ready.await(10,java.util.concurrent.TimeUnit.SECONDS);return knowledge.publish(created.getId(),1);}
                finally {io.github.opensabre.common.core.util.UserContextHolder.getInstance().setContext(Map.of());}
            };
            var left=publishers.submit(publish);var right=publishers.submit(publish);
            first=left.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(first.getId(),right.get(20,java.util.concurrent.TimeUnit.SECONDS).getId());
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM iqc_knowledge_base_version WHERE knowledge_base_id=?",Integer.class,created.getId()));
        } finally {publishers.shutdownNow();assertTrue(publishers.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
        assertEquals(first.getId(),knowledge.publish(created.getId(),1).getId());
        assertEquals(1,knowledge.history(created.getId()).size());
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->knowledge.publishedForTask(created.getId(),1));
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->tx.execute(status->knowledge.publishedForTask(created.getId(),1)));
        knowledge.availability(created.getId(),true);
        var prepared=tx.execute(status->knowledge.publishedForTask(created.getId(),1));
        assertEquals("frozen reference text",prepared.snapshot().corpus().documents().getFirst().text());
        assertEquals("annotator",prepared.snapshot().owner());assertEquals("annotator",prepared.snapshot().publishedBy());
        var json=context.getBean(ObjectMapper.class);
        var binding=new io.github.opensabre.iqc.knowledge.KnowledgeBaseService.Binding(created.getId(),1,first.getId(),first.getContentHash());
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->knowledge.publishedForTask(List.of(binding)));
        var config=new io.github.opensabre.iqc.agent.AgentConfiguration("3.0",null,"instructions",null,null,null,null,
                "model",List.of(),List.of(),List.of(),null,null).withKnowledgeAssets(List.of(
                new io.github.opensabre.iqc.agent.AgentConfiguration.KnowledgeReference(created.getId(),1,first.getId(),first.getContentHash())));
        var frozenTask=new InspectionTask();frozenTask.setId("kt"+UUID.randomUUID().toString().replace("-",""));frozenTask.setCreatedBy("annotator");frozenTask.setOwnerGroupId("group");
        frozenTask.setRuleSnapshotJson("{}");frozenTask.setAgentSnapshotJson(json.createObjectNode().put("configJson",json.writeValueAsString(config)).toString());
        var sensitive=mock(io.github.opensabre.iqc.privacy.SensitiveReadAccessService.class);
        var freezer=new io.github.opensabre.iqc.knowledge.KnowledgeTaskSnapshotService(knowledge,sensitive,context.getBean(IqcDataScope.class),json);
        tx.executeWithoutResult(status->freezer.freeze(frozenTask));
        verify(sensitive).current(Set.of("iqc:knowledge:version:view"));
        frozenTask.setName("SQL frozen knowledge task");frozenTask.setTaskType("BATCH");frozenTask.setStatus("CREATED");
        var taskMapper=context.getBean(SqlSessionTemplate.class).getMapper(InspectionTaskMapper.class);
        tx.executeWithoutResult(status->assertEquals(1,taskMapper.insert(frozenTask)));
        var persistedKnowledgeTask=taskMapper.selectById(frozenTask.getId());
        assertEquals(frozenTask.getAgentSnapshotJson(),persistedKnowledgeTask.getAgentSnapshotJson());
        assertEquals(frozenTask.getRuleSnapshotJson(),persistedKnowledgeTask.getRuleSnapshotJson());
        assertEquals(first.getId(),io.github.opensabre.iqc.knowledge.KnowledgeTaskSnapshotService.verifiedSources(persistedKnowledgeTask,json).getFirst().versionId());
        String taskKey=UUID.randomUUID().toString().replace("-","");
        String taskAgent="ka"+taskKey,taskRule="kr"+taskKey,taskConversation="kc"+taskKey;
        db.update("INSERT INTO iqc_quality_agent(id,name,code,status,config_json) VALUES(?,?,?,'PUBLISHED',?)",taskAgent,taskAgent,taskAgent,json.writeValueAsString(config));
        db.update("INSERT INTO iqc_quality_rule(id,name,code,rule_type,status) VALUES(?,?,?,'LLM','PUBLISHED')",taskRule,taskRule,taskRule);
        db.update("INSERT INTO iqc_conversation(id,source_file_name,message_count,status,created_by,owner_group_id,source_fingerprint) VALUES (?,'processed knowledge conversation',1,'READY','annotator','group',?)",taskConversation,taskKey);
        db.update("INSERT INTO iqc_conversation_message(id,conversation_id,sequence_no,speaker_role,content,relative_time,raw_line,line_number) VALUES (?,?,1,'agent','processed knowledge conversation text','00:00:00','processed knowledge conversation text',1)","km"+taskKey,taskConversation);
        var knowledgeProvider=mock(org.springframework.beans.factory.ObjectProvider.class);
        when(knowledgeProvider.getObject()).thenReturn(freezer);
        var taskCreator=new InspectionTaskService(taskMapper,context.getBean(SqlSessionTemplate.class).getMapper(ConversationMapper.class),
                context.getBean(SqlSessionTemplate.class).getMapper(QualityAgentMapper.class),context.getBean(SqlSessionTemplate.class).getMapper(QualityRuleMapper.class),
                null,json,context.getBean(SqlSessionTemplate.class).getMapper(TaskExecutionMapper.class),context.getBean(IqcDataScope.class),null,null,
                context.getBean(ProductionInputSnapshotService.class),null,null,knowledgeProvider);
        var serviceCreated=tx.execute(status->taskCreator.createBatch("Service knowledge task",List.of(taskConversation),taskAgent,null,List.of(taskRule),1,"AGENT_LLM"));
        var servicePersisted=taskMapper.selectById(serviceCreated.getId());
        assertEquals("annotator",servicePersisted.getCreatedBy());
        assertEquals(first.getId(),io.github.opensabre.iqc.knowledge.KnowledgeTaskSnapshotService.verifiedSources(servicePersisted,json).getFirst().versionId());
        int beforeRevocation=db.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE agent_id=?",Integer.class,taskAgent);
        when(sensitive.current(Set.of("iqc:knowledge:version:view"))).thenThrow(io.github.opensabre.iqc.governance.IqcException.accessDenied("revoked knowledge access"));
        try {
            assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->tx.execute(status->taskCreator.createBatch("Rejected revoked knowledge",List.of(taskConversation),taskAgent,null,List.of(taskRule),1,"AGENT_LLM")));
            assertEquals(beforeRevocation,db.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE agent_id=?",Integer.class,taskAgent));
            assertEquals(first.getId(),io.github.opensabre.iqc.knowledge.KnowledgeTaskSnapshotService.verifiedSources(taskMapper.selectById(serviceCreated.getId()),json).getFirst().versionId());
        } finally {reset(sensitive);}
        var originalTaskEvidence=frozenTask.getRuleSnapshotJson();
        assertEquals(first.getId(),io.github.opensabre.iqc.knowledge.KnowledgeTaskSnapshotService.verifiedSources(frozenTask,json).getFirst().versionId());
        var changed=new io.github.opensabre.iqc.knowledge.KnowledgeCorpus(io.github.opensabre.iqc.knowledge.KnowledgeCorpus.SCHEMA,
                List.of(new io.github.opensabre.iqc.knowledge.KnowledgeCorpus.Document("reference","Reference","revised reference text","source:manual")));
        knowledge.update(created.getId(),1,"Revised corpus",changed);
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->knowledge.update(created.getId(),1,"stale",corpus));
        var second=knowledge.publish(created.getId(),2);assertEquals(2,second.getVersionNo());
        assertEquals("ACTIVE",knowledge.get(created.getId()).getStatus());
        assertEquals(first.getSnapshotJson(),knowledge.history(created.getId()).get(1).getSnapshotJson());
        var restored=knowledge.restore(created.getId(),2,1);assertEquals(3,restored.getDraftRevision());
        assertEquals(2,restored.getActivePublishedVersion());assertEquals(corpus,knowledge.requestCorpus(restored.getDraftConfigJson()));
        knowledge.availability(created.getId(),false);
        assertEquals(originalTaskEvidence,frozenTask.getRuleSnapshotJson());
        persistedKnowledgeTask=taskMapper.selectById(frozenTask.getId());
        assertEquals(originalTaskEvidence,persistedKnowledgeTask.getRuleSnapshotJson());
        var historical=io.github.opensabre.iqc.knowledge.KnowledgeTaskSnapshotService.verifiedSources(persistedKnowledgeTask,json);
        assertEquals("frozen reference text",historical.getFirst().snapshot().corpus().documents().getFirst().text());
        assertEquals(first.getContentHash(),historical.getFirst().contentHash());
        var capability=io.github.opensabre.iqc.knowledge.KnowledgeTaskSnapshotService.executionContext(persistedKnowledgeTask,json);
        assertTrue(capability.material(json.valueToTree(config),frozenTask.getId(),"annotator","group").contains("frozen reference text"));
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->tx.execute(status->knowledge.publishedForTask(List.of(binding))));
        assertEquals(first.getId(),io.github.opensabre.iqc.knowledge.KnowledgeTaskSnapshotService.verifiedSources(taskMapper.selectById(serviceCreated.getId()),json).getFirst().versionId());
        int beforeRejected=db.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE agent_id=?",Integer.class,taskAgent);
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->tx.execute(status->taskCreator.createBatch("Rejected disabled knowledge",List.of(taskConversation),taskAgent,null,List.of(taskRule),1,"AGENT_LLM")));
        assertEquals(beforeRejected,db.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE agent_id=?",Integer.class,taskAgent));
        var workerModel=context.getBean(LlmQualityProvider.class);
        var observedContext=new java.util.concurrent.atomic.AtomicReference<ModelCallContext>();
        when(workerModel.evaluate(anyString(),any(),any(),any(),anyString(),any())).thenAnswer(invocation->{
            ModelCallContext call=invocation.getArgument(5);observedContext.set(call);
            assertEquals(serviceCreated.getId(),call.taskId());assertNotNull(call.knowledgeContext());
            var agentSnapshot=(com.fasterxml.jackson.databind.JsonNode)invocation.getArgument(2);
            var workerConfig=agentSnapshot.path("configJson");if(workerConfig.isTextual())workerConfig=json.readTree(workerConfig.asText());
            String material=LlmQualityProvider.knowledgeMaterial(workerConfig,call);
            assertTrue(material.contains("frozen reference text"));assertFalse(material.contains("revised reference text"));
            return new LlmQualityProvider.LlmEvaluation(true,false,"controlled worker", "{\"hit\":false,\"reason\":\"controlled worker\"}");
        });
        try {
            var completed=run(serviceCreated);
            assertNotNull(observedContext.get());assertEquals(completed.getCurrentExecutionId(),observedContext.get().executionId());
            verify(workerModel,times(1)).evaluate(anyString(),any(),any(),any(),anyString(),any());
        } finally {reset(workerModel);}
        var sql=context.getBean(SqlSessionTemplate.class);
        var actualScope=new IqcDataScope(null);
        var scoped=new io.github.opensabre.iqc.knowledge.KnowledgeBaseService(
                sql.getMapper(io.github.opensabre.iqc.knowledge.dao.KnowledgeBaseMapper.class),
                sql.getMapper(io.github.opensabre.iqc.knowledge.dao.KnowledgeBaseVersionMapper.class),actualScope,context.getBean(ObjectMapper.class));
        var userContext=io.github.opensabre.common.core.util.UserContextHolder.getInstance();
        try {
            assertEquals(created.getId(),scoped.get(created.getId()).getId());
            var organization=mock(io.github.opensabre.iqc.shared.IqcOrganizationClient.class);
            when(organization.getUserByUniqueId(anyString())).thenAnswer(call->io.github.opensabre.common.core.entity.vo.Result.success(
                    new io.github.opensabre.iqc.shared.IqcOrganizationClient.OrganizationUser("member-id","Member",call.getArgument(0),"group","Group")));
            var grouped=new io.github.opensabre.iqc.knowledge.KnowledgeBaseService(
                    sql.getMapper(io.github.opensabre.iqc.knowledge.dao.KnowledgeBaseMapper.class),
                    sql.getMapper(io.github.opensabre.iqc.knowledge.dao.KnowledgeBaseVersionMapper.class),new IqcDataScope(organization),context.getBean(ObjectMapper.class));
            userContext.setContext(Map.of("user_name","group-member","user_id","member-id"));
            assertEquals(created.getId(),grouped.get(created.getId()).getId());
            assertEquals(2,grouped.history(created.getId()).size());
            assertEquals(4,tx.execute(status->grouped.update(created.getId(),3,"Shared maintenance",corpus)).getDraftRevision());
            var shared=tx.execute(status->grouped.publish(created.getId(),4));
            var sharedEvidence=io.github.opensabre.iqc.knowledge.KnowledgeBaseService.readPublishedEvidence(shared,context.getBean(ObjectMapper.class));
            assertEquals("annotator",sharedEvidence.owner());assertEquals("group-member",sharedEvidence.publishedBy());
            userContext.setContext(Map.of("user_name","annotator","user_id","user-annotator"));
            var narrowed=tx.execute(status->scoped.update(created.getId(),4,"Private maintenance",corpus));
            assertNull(narrowed.getOwnerGroupId());
            assertNull(tx.execute(status->scoped.viewVersion(created.getId(),1)).ownerGroupId());
            userContext.setContext(Map.of("user_name","group-member","user_id","member-id"));
            assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->grouped.history(created.getId()));
            assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->tx.execute(status->grouped.update(created.getId(),5,"forbidden",corpus)));
            userContext.setContext(Map.of("user_name","outsider","user_id","user-outsider"));
            assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->scoped.get(created.getId()));
            assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->scoped.history(created.getId()));
            assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->tx.execute(status->scoped.update(created.getId(),3,"unauthorized",corpus)));
            assertEquals(0,scoped.page(1,100).records().size());
            userContext.setContext(Map.of("user_name","administrator","user_id","user-admin",userContext.KEY_ROLES,"ADMIN"));
            assertEquals(created.getId(),scoped.get(created.getId()).getId());
            userContext.setContext(Map.of());
            assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->scoped.get(created.getId()));
            assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->scoped.page(1,100));
        } finally {userContext.setContext(Map.of("user_name","annotator","user_id","user-annotator"));}
        assertEquals(first.getContentHash(),knowledge.viewVersion(created.getId(),1).contentHash());
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->tx.execute(status->knowledge.publishedForTask(created.getId(),1)));
    }

    @Test void exactPromptSurvivesHeadChangesAndDisabledSourceBlocksApproval() throws Exception {
        var sql=context.getBean(SqlSessionTemplate.class);var json=context.getBean(ObjectMapper.class);
        var db=new JdbcTemplate(context.getBean(DataSource.class));String key=UUID.randomUUID().toString().replace("-", "");
        String skill="prompt"+key,model="model"+key;
        db.update("INSERT INTO iqc_skill(id,name,code,instructions,status,version_no) VALUES(?,?,?,'current head','ENABLED',9)",skill,skill,skill);
        db.update("INSERT INTO iqc_skill_version(id,skill_id,version_no,name,code,instructions,status) VALUES(?,?,2,?,?,'historical exact body','ENABLED')","pv"+key,skill,skill,skill);
        db.update("INSERT INTO iqc_model_profile(id,name,code,provider,model_name,status,version_no) VALUES(?,?,?,'OPENAI','controlled','ENABLED',1)",model,model,model);
        var validator=new io.github.opensabre.iqc.agent.AgentAssetReferenceValidator(
                sql.getMapper(io.github.opensabre.iqc.modelprofile.dao.IqcModelProfileMapper.class),
                sql.getMapper(io.github.opensabre.iqc.mcp.dao.IqcMcpServerMapper.class),
                sql.getMapper(io.github.opensabre.iqc.skill.dao.IqcSkillMapper.class),
                sql.getMapper(io.github.opensabre.iqc.skill.dao.IqcSkillVersionMapper.class));
        var agents=new io.github.opensabre.iqc.agent.QualityAgentService(sql.getMapper(QualityAgentMapper.class),sql.getMapper(QualityAgentVersionMapper.class),json,validator);
        var tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var config=new io.github.opensabre.iqc.agent.AgentConfiguration("3.0",null,"",null,null,null,null,model,List.of(),List.of(),List.of(),null,null)
                .withPrompt("",new io.github.opensabre.iqc.agent.AgentConfiguration.PromptReference(skill,2,null));
        var created=tx.execute(status->agents.create("Prompt SQL", "P_"+key,null,write(json,config)));
        var frozen=json.readValue(created.getConfigJson(),io.github.opensabre.iqc.agent.AgentConfiguration.class);
        assertEquals("historical exact body",frozen.systemPrompt());assertNotNull(frozen.promptAsset().contentHash());
        db.update("UPDATE iqc_skill SET instructions='new current head',version_no=10 WHERE id=?",skill);
        tx.execute(status->agents.submit(created.getId()));tx.execute(status->agents.publish(created.getId()));
        String published=agents.versions(created.getId()).getFirst().getConfigJson();
        assertEquals("historical exact body",json.readTree(published).path("systemPrompt").asText());
        assertEquals(2,json.readTree(published).path("promptAsset").path("versionNo").asInt());
        tx.execute(status->agents.createVersion(created.getId(),null,null,null,published));
        db.update("UPDATE iqc_skill SET status='DISABLED' WHERE id=?",skill);
        assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->tx.execute(status->agents.submit(created.getId())));
        assertEquals("DRAFT",agents.versions(created.getId()).getFirst().getStatus());
        assertEquals(published,agents.versions(created.getId()).get(1).getConfigJson());
        assertEquals("PUBLISHED",agents.versions(created.getId()).get(1).getStatus());
    }
    private static String write(ObjectMapper json,Object value){
        try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException(error);}
    }

    private void assertFindingDistributionFromSql(InspectionTask task,io.github.opensabre.iqc.result.model.ConversationInspectionResult result,boolean reviewed) throws Exception {
        var json=context.getBean(ObjectMapper.class);var sql=context.getBean(SqlSessionTemplate.class);
        var reports=context.getBean(io.github.opensabre.iqc.quality.BusinessQualityReportService.class);
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(reports));
        var report=reports.report(List.of(task.getId()),null,"SELECTED_RUNS",false,false,true);
        var distribution=report.findingDistributions().values().iterator().next();
        assertEquals(1,distribution.machine().expectedResults());assertEquals(1,distribution.machine().sourceResults());
        assertEquals(0,distribution.machine().unavailableResults());
        var fact=distribution.machine().facts().getFirst();assertEquals("NUMBER",fact.valueType());assertEquals(1,fact.presentCount());
        assertEquals(1,fact.states().get("KNOWN"));assertEquals(1,fact.knownSeverityCounts().get("HIGH"));
        assertEquals(0,fact.trueCount()+fact.falseCount());
        assertEquals(reviewed?1:0,distribution.reviewed().sourceResults());assertEquals(reviewed?0:1,distribution.reviewed().unavailableResults());
        if(reviewed)assertEquals(distribution.machine().facts(),distribution.reviewed().facts());
        String publicDistribution=json.writeValueAsString(distribution);
        assertFalse(publicDistribution.contains("1000"));assertFalse(publicDistribution.contains("processed"));
        assertFalse(publicDistribution.contains("evidence"));assertFalse(publicDistribution.contains("explanation"));
        // The new callback may expose facts only after the original stored-item/score validator succeeds.
        var db=new JdbcTemplate(context.getBean(DataSource.class));
        db.update("UPDATE iqc_inspection_conversation_result SET business_item_results_json='[]' WHERE id=?",result.getId());
        try {assertThrows(io.github.opensabre.iqc.governance.IqcException.class,()->reports.report(List.of(task.getId()),null,"SELECTED_RUNS",false,false,true));}
        finally {db.update("UPDATE iqc_inspection_conversation_result SET business_item_results_json=? WHERE id=?",result.getBusinessItemResultsJson(),result.getId());}
        var grants=mock(io.github.opensabre.iqc.privacy.SensitiveReadAccessService.class);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new io.github.opensabre.iqc.rest.QualityOperationsController(null,context.getBean(BusinessItemReviewService.class),reports,null,null,grants,new io.github.opensabre.iqc.privacy.ResultReadProjection(json)))
            .setControllerAdvice(new io.github.opensabre.webmvc.rest.RestResponseBodyAdvice(),new io.github.opensabre.iqc.governance.IqcExceptionHandlerAdvice(),new io.github.opensabre.webmvc.exception.DefaultWebMvcExceptionHandlerAdvice()).build();
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/iqc/quality-operations/report")
            .param("view","BUSINESS").param("taskIds",task.getId()).param("includeFindingDistribution","true"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","private, no-store"))
            .andReturn().getResponse().getContentAsString();
        var envelope=json.readTree(response);assertEquals("000000",envelope.path("code").asText());assertEquals(json.readTree(json.writeValueAsString(report)),envelope.path("data"));
        var stored=sql.getMapper(ConversationInspectionResultMapper.class).selectById(result.getId());
        assertEquals(result.getExecutionTraceJson(),stored.getExecutionTraceJson());assertEquals(result.getScoringResultJson(),stored.getScoringResultJson());assertEquals(result.getBusinessItemResultsJson(),stored.getBusinessItemResultsJson());
        assertFalse(json.valueToTree(reports.report(List.of(task.getId()))).has("findingDistributions"));
    }
    private void assertArchiveReceiptExecutesPublishedPipeline(PipelineAssetService.Prepared prepared,String conversation,String suffix) throws Exception {
        var json=context.getBean(ObjectMapper.class);var db=new JdbcTemplate(context.getBean(DataSource.class));
        String fingerprint=PipelineAssetService.hash(json.writeValueAsString(conversation));
        var archiveTime=Instant.parse("2001-01-01T00:00:00Z");
        // The isolated database retains prior runs; reserve an unused historical second.
        while(db.queryForObject("SELECT COUNT(*) FROM iqc_conversation WHERE created_time>=? AND created_time<?",Integer.class,
                java.sql.Timestamp.from(archiveTime),java.sql.Timestamp.from(archiveTime.plusSeconds(1)))>0)archiveTime=archiveTime.plusSeconds(1);
        db.update("UPDATE iqc_conversation SET status='IMPORTED',source_fingerprint=?,created_time=? WHERE id=?",fingerprint,java.sql.Timestamp.from(archiveTime),conversation);
        String subscription="sub"+suffix,revision="rev"+suffix;var now=Instant.now();
        var request=new io.github.opensabre.iqc.schedule.TaskTriggerRequest("iqc-task-trigger-v1","archive-subscription",
                io.github.opensabre.iqc.schedule.EventSubscriptionService.eventId(revision,conversation,fingerprint),"ConversationCompleted",
                archiveTime,now.plusSeconds(600),"Archive worker execution",
                new io.github.opensabre.iqc.schedule.PeriodicDefinition.PipelineReference(prepared.pipelineId(),prepared.versionNo()),null,
                new io.github.opensabre.iqc.schedule.TaskTriggerRequest.Target(conversation,null,null,fingerprint),null,1,false);
        var bound=new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1","user-annotator","annotator",false,"group");
        var actor=new ScheduledActorAccessService.Resolved("user-annotator","annotator",false,"group",Set.of(),
                io.github.opensabre.iqc.schedule.TaskTriggerService.grants(request),Instant.now());
        var definition=new io.github.opensabre.iqc.schedule.EventSubscriptionDefinition(
                io.github.opensabre.iqc.schedule.EventSubscriptionDefinition.SCHEMA,"ConversationCompleted",archiveTime,archiveTime.plusSeconds(1),600,1,request.pipeline(),null);
        var parent=new io.github.opensabre.iqc.schedule.EventSubscriptionService.Published("iqc-event-subscription-release-v1",request.name(),definition,prepared,null,bound);
        String parentBody=json.writeValueAsString(parent);
        db.update("INSERT INTO iqc_event_subscription(id,code,name,draft_config_json,active_revision_id,status,created_by) VALUES(?,?,?,'{}',?,'ACTIVE','annotator')",subscription,subscription,request.name(),revision);
        db.update("INSERT INTO iqc_event_subscription_revision(id,subscription_id,version_no,source_draft_revision,snapshot_json,content_hash,created_by) VALUES(?,?,1,1,?,?,'annotator')",revision,subscription,parentBody,io.github.opensabre.iqc.selector.SelectorAssetService.hash(parentBody));
        var triggers=context.getBean(io.github.opensabre.iqc.schedule.TaskTriggerService.class);
        var tx=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        var subscriptions=context.getBean(io.github.opensabre.iqc.schedule.EventSubscriptionService.class);
        var subscriptionFrame=new io.github.opensabre.iqc.schedule.EventSubscriptionService.Frame(subscription,revision,parent,
                io.github.opensabre.iqc.selector.SelectorAssetService.hash(parentBody));
        assertEquals(1,subscriptions.fire(subscriptionFrame,actor));assertEquals(0,subscriptions.fire(subscriptionFrame,actor));
        String receiptId=db.queryForObject("SELECT id FROM iqc_inspection_task WHERE event_subscription_id=?",String.class,subscription);
        var receipt=context.getBean(SqlSessionTemplate.class).getMapper(InspectionTaskMapper.class).selectById(receiptId);
        assertEquals("WAITING_INPUT",receipt.getStatus());
        var accepted=io.github.opensabre.iqc.schedule.TaskTriggerService.readAcceptedEvidence(receipt,json);
        var frame=new io.github.opensabre.iqc.schedule.TaskTriggerService.Frame(receipt.getId(),accepted,receipt.getTriggerRequestHash());
        assertTrue(triggers.materialize(frame,actor));assertFalse(triggers.materialize(frame,actor));
        var sql=context.getBean(SqlSessionTemplate.class);var tasks=sql.getMapper(InspectionTaskMapper.class);
        var queued=tasks.selectById(receipt.getId());assertEquals("QUEUED",queued.getStatus());
        context.getBean(InspectionExecutionService.class).executePersisted(queued.getId(),queued.getCurrentExecutionId());
        var finished=tasks.selectById(queued.getId());assertEquals("SUCCEEDED",finished.getStatus());
        var result=sql.getMapper(ConversationInspectionResultMapper.class).selectLatestForTaskConversation(finished.getId(),conversation);
        assertNotNull(result);assertEquals(0,new BigDecimal("80").compareTo(result.getFinalScore()));
        assertEquals("HIGH",result.getRiskLevel());assertTrue(result.getExecutionTraceJson().contains("1000"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM iqc_task_item WHERE execution_id=? AND status='SUCCEEDED'",Integer.class,queued.getCurrentExecutionId()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM iqc_task_execution WHERE task_id=?",Integer.class,finished.getId()));
        assertEquals(receipt.getId(),tx.execute(status->triggers.receiveSubscription(accepted.request(),bound,actor,prepared,null,subscription,revision)).getId());
        // Repeated dispatcher delivery cannot execute an already completed generation again.
        String resultBefore=json.writeValueAsString(result);
        context.getBean(InspectionExecutionService.class).executePersisted(queued.getId(),queued.getCurrentExecutionId());
        assertEquals(resultBefore,json.writeValueAsString(sql.getMapper(ConversationInspectionResultMapper.class).selectLatestForTaskConversation(finished.getId(),conversation)));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM iqc_task_execution WHERE task_id=?",Integer.class,finished.getId()));
        assertEquals(0,subscriptions.fire(subscriptionFrame,actor));
    }
    /** Real parent/child/production input share one transaction; injected checkpoint failure must undo all. */
    private void assertBulkMaterializationRollsBackAndRetainsFrozenInput(PipelineAssetService.Prepared prepared,String conversation,String suffix) throws Exception {
        var bulk=context.getBean(BulkTaskMaterializationService.class);assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(bulk));
        var json=context.getBean(ObjectMapper.class);var db=new JdbcTemplate(context.getBean(DataSource.class));var sql=context.getBean(SqlSessionTemplate.class);
        var selector=new io.github.opensabre.iqc.selector.SelectorDefinition(io.github.opensabre.iqc.selector.SelectorDefinition.SCHEMA,null,null,List.of(conversation),List.of(),null,null,1000);
        var bound=new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1","user-annotator","annotator",false,"group");
        var parent=bulk.create(prepared.pipelineId(),prepared.versionNo(),selector,bound,"bulk validation",1000,1,"bulk_"+suffix);
        assertEquals(parent.getId(),bulk.create(prepared.pipelineId(),prepared.versionNo(),selector,bound,"bulk validation",1000,1,"bulk_"+suffix).getId());
        String eid="bulk-e"+suffix;
        db.update("INSERT INTO iqc_task_execution(id,task_id,attempt_no,status) VALUES (?,?,1,'RUNNING')",eid,parent.getId());
        db.update("UPDATE iqc_inspection_task SET status='RUNNING',current_execution_id=? WHERE id=?",eid,parent.getId());
        String before=db.queryForObject("SELECT bulk_progress_json FROM iqc_inspection_task WHERE id=?",String.class,parent.getId());
        int tasksBefore=db.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task",Integer.class);
        db.execute("CREATE TRIGGER reject_bulk_checkpoint BEFORE UPDATE ON iqc_inspection_task FOR EACH ROW BEGIN IF NEW.id='"+parent.getId()+"' AND NOT (NEW.bulk_progress_json <=> OLD.bulk_progress_json) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected checkpoint failure'; END IF; END");
        try {assertThrows(org.springframework.dao.DataAccessException.class,()->bulk.materializeNext(parent.getId(),eid));}
        finally {db.execute("DROP TRIGGER reject_bulk_checkpoint");}
        assertEquals(before,db.queryForObject("SELECT bulk_progress_json FROM iqc_inspection_task WHERE id=?",String.class,parent.getId()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE bulk_parent_task_id=?",Integer.class,parent.getId()));
        assertEquals(tasksBefore,db.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task",Integer.class));
        var step=bulk.materializeNext(parent.getId(),eid);assertNotNull(step.childTaskId());assertTrue(step.progress().scanComplete());
        assertEquals(1,step.progress().scannedConversations());assertEquals(1,step.progress().selectedConversations());assertEquals(1,step.progress().nextShardNo());
        var child=sql.getMapper(InspectionTaskMapper.class).selectById(step.childTaskId());assertEquals("CREATED",child.getStatus());assertEquals(parent.getId(),child.getBulkParentTaskId());assertEquals(0,child.getBulkShardNo());
        var frozen=PipelineAssetService.readFrozenTree(json,child.getRuleSnapshotJson());assertEquals("iqc-bulk-shard-scan-v1",frozen.path("bulkShardSnapshot").path("schemaVersion").asText());
        assertEquals(parent.getId(),frozen.path("bulkShardSnapshot").path("parentTaskId").asText());assertTrue(frozen.path("bulkShardSnapshot").path("scanPageHash").asText().matches("[a-f0-9]{64}"));
        String childSnapshot=child.getRuleSnapshotJson();String progress=db.queryForObject("SELECT bulk_progress_json FROM iqc_inspection_task WHERE id=?",String.class,parent.getId());
        var again=bulk.materializeNext(parent.getId(),eid);assertNull(again.childTaskId());assertEquals(step.progress(),again.progress());
        assertEquals(childSnapshot,sql.getMapper(InspectionTaskMapper.class).selectById(child.getId()).getRuleSnapshotJson());
        assertThrows(RuntimeException.class,()->bulk.materializeNext(parent.getId(),"obsolete-execution"));
        assertEquals(progress,db.queryForObject("SELECT bulk_progress_json FROM iqc_inspection_task WHERE id=?",String.class,parent.getId()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM iqc_inspection_task WHERE bulk_parent_task_id=?",Integer.class,parent.getId()));        var lifecycle=context.getBean(BulkTaskLifecycleService.class);assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(lifecycle));
        db.update("UPDATE iqc_inspection_task SET status='QUEUED' WHERE id=?",parent.getId());db.update("UPDATE iqc_task_execution SET status='QUEUED' WHERE id=?",eid);
        var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> claim=()->{ready.countDown();assertTrue(start.await(10,java.util.concurrent.TimeUnit.SECONDS));return lifecycle.claim(parent.getId(),eid);};
            var left=pool.submit(claim);var right=pool.submit(claim);assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS));start.countDown();
            assertNotEquals(left.get(20,java.util.concurrent.TimeUnit.SECONDS),right.get(20,java.util.concurrent.TimeUnit.SECONDS));
        } finally {start.countDown();pool.shutdownNow();}
        lifecycle.tick(parent.getId(),eid);
        var queuedChild=sql.getMapper(InspectionTaskMapper.class).selectById(child.getId());assertEquals("QUEUED",queuedChild.getStatus());String childEid=queuedChild.getCurrentExecutionId();assertNotNull(childEid);
        var tasks=context.getBean(InspectionTaskService.class);tasks.requestPause(parent.getId());
        io.github.opensabre.common.core.util.UserContextHolder.getInstance().setContext(Map.of());
        try {assertTrue(lifecycle.controlIfRequested(parent.getId(),eid));}
        finally {io.github.opensabre.common.core.util.UserContextHolder.getInstance().setContext(Map.of("user_name","annotator","user_id","user-annotator"));}
        assertEquals("PAUSED",sql.getMapper(InspectionTaskMapper.class).selectById(parent.getId()).getStatus());assertEquals("PAUSED",sql.getMapper(InspectionTaskMapper.class).selectById(child.getId()).getStatus());
        assertEquals("PAUSED",db.queryForObject("SELECT status FROM iqc_task_execution WHERE id=?",String.class,childEid));
        lifecycle.queue(parent.getId());
        assertEquals(eid,sql.getMapper(InspectionTaskMapper.class).selectById(parent.getId()).getCurrentExecutionId());
        String resumedChildEid=sql.getMapper(InspectionTaskMapper.class).selectById(child.getId()).getCurrentExecutionId();assertNotEquals(childEid,resumedChildEid);
        assertEquals("PAUSED",db.queryForObject("SELECT status FROM iqc_task_execution WHERE id=?",String.class,childEid));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM iqc_task_execution WHERE task_id=?",Integer.class,child.getId()));
        assertEquals("QUEUED",sql.getMapper(InspectionTaskMapper.class).selectById(child.getId()).getStatus());
        tasks.cancel(parent.getId());
        io.github.opensabre.common.core.util.UserContextHolder.getInstance().setContext(Map.of());
        try {assertTrue(lifecycle.controlIfRequested(parent.getId(),eid));}
        finally {io.github.opensabre.common.core.util.UserContextHolder.getInstance().setContext(Map.of("user_name","annotator","user_id","user-annotator"));}
        assertEquals("CANCELLED",sql.getMapper(InspectionTaskMapper.class).selectById(parent.getId()).getStatus());assertEquals("CANCELLED",sql.getMapper(InspectionTaskMapper.class).selectById(child.getId()).getStatus());
        assertEquals("CANCELLED",db.queryForObject("SELECT status FROM iqc_task_execution WHERE id=?",String.class,resumedChildEid));
        assertEquals(childSnapshot,sql.getMapper(InspectionTaskMapper.class).selectById(child.getId()).getRuleSnapshotJson());assertEquals(progress,db.queryForObject("SELECT bulk_progress_json FROM iqc_inspection_task WHERE id=?",String.class,parent.getId()));
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
            for(var type:List.of(io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper.class,io.github.opensabre.iqc.knowledge.dao.KnowledgeBaseMapper.class,io.github.opensabre.iqc.knowledge.dao.KnowledgeBaseVersionMapper.class,io.github.opensabre.iqc.skill.dao.IqcSkillMapper.class,io.github.opensabre.iqc.skill.dao.IqcSkillVersionMapper.class,io.github.opensabre.iqc.modelprofile.dao.IqcModelProfileMapper.class,io.github.opensabre.iqc.mcp.dao.IqcMcpServerMapper.class,io.github.opensabre.iqc.label.dao.LabelCategoryMapper.class,io.github.opensabre.iqc.label.dao.LabelGroupMapper.class,io.github.opensabre.iqc.label.dao.InsightLabelMapper.class,io.github.opensabre.iqc.label.dao.LabelRuleBindingMapper.class,io.github.opensabre.iqc.label.dao.LabelValueDefinitionMapper.class,io.github.opensabre.iqc.label.dao.LabelCollectionMapper.class,io.github.opensabre.iqc.label.dao.LabelCollectionMemberMapper.class,QualityRuleMapper.class,QualityRuleVersionMapper.class,QualityAgentMapper.class,QualityAgentVersionMapper.class,ConversationMapper.class,ConversationMessageMapper.class,FindingCatalogMapper.class,FindingCatalogVersionMapper.class,
                    io.github.opensabre.iqc.schedule.dao.EventSubscriptionMapper.class,io.github.opensabre.iqc.schedule.dao.EventSubscriptionRevisionMapper.class,
                    PipelineAssetMapper.class,PipelineAssetVersionMapper.class,QualitySampleMapper.class,QualitySampleVersionMapper.class,
                    EvaluationDatasetMapper.class,EvaluationDatasetVersionMapper.class,EvaluationSampleResultMapper.class,
                    ConversationInspectionResultMapper.class,RuleInspectionResultMapper.class,InspectionEvidenceMapper.class,InspectionResultMapper.class,ResultReviewMapper.class,ActionDiscoveryMapper.class))
                if(!f.getConfiguration().hasMapper(type))f.getConfiguration().addMapper(type);
            var pagination=new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
            pagination.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.MYSQL));
            f.getConfiguration().addInterceptor(pagination);
            return new SqlSessionTemplate(f);
        }
        @Bean ScheduledActorAccessService actionActors(){var service=mock(ScheduledActorAccessService.class);when(service.capture()).thenReturn(new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1","user-annotator","annotator",false,"group"));return service;}
        @Bean io.github.opensabre.iqc.quality.BusinessQualityReportService businessReports(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope,BusinessItemReviewService reviews){
            var grants=mock(io.github.opensabre.iqc.privacy.SensitiveReadAccessService.class);
            when(grants.current(Set.of("iqc:report:view"))).thenReturn(new io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access("controlled",true,true,false,Instant.now()));
            return new io.github.opensabre.iqc.quality.BusinessQualityReportService(s.getMapper(InspectionTaskMapper.class),s.getMapper(ConversationInspectionResultMapper.class),reviews,scope,j,s.getMapper(TaskExecutionMapper.class),null,null,grants);
        }
        @Bean BusinessItemReviewService reviews(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope){return new BusinessItemReviewService(s.getMapper(ResultReviewMapper.class),s.getMapper(ConversationInspectionResultMapper.class),s.getMapper(InspectionTaskMapper.class),s.getMapper(ConversationMessageMapper.class),scope,j);}
        @Bean BusinessReviewSampleService candidates(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope,BusinessItemReviewService reviews){return new BusinessReviewSampleService(s.getMapper(QualitySampleMapper.class),s.getMapper(ResultReviewMapper.class),s.getMapper(ConversationInspectionResultMapper.class),s.getMapper(InspectionTaskMapper.class),s.getMapper(ConversationMapper.class),s.getMapper(ConversationMessageMapper.class),reviews,scope,j);}
        @Bean ActionProductionSourceValidator sources(SqlSessionTemplate s,PipelineAssetService assets,ProductionInputSnapshotService inputs,ObjectMapper j){return new ActionProductionSourceValidator(s.getMapper(InspectionTaskMapper.class),s.getMapper(TaskExecutionMapper.class),s.getMapper(ConversationInspectionResultMapper.class),assets,inputs,j);}
        @Bean BusinessReviewActionService reviewActions(ActionProductionSourceValidator sources,BusinessItemReviewService reviews,IqcDataScope scope,ObjectMapper j){return new BusinessReviewActionService(sources,reviews,scope,j);}
        @Bean ActionAdapterRegistry adapters(SqlSessionTemplate s,ObjectMapper j,BusinessReviewActionService reviews){return new ActionAdapterRegistry(List.of(new SavedResultActionAdapter(s.getMapper(ConversationInspectionResultMapper.class),j),new BusinessReviewActionAdapter(reviews,j)));}
        @Bean io.github.opensabre.iqc.knowledge.KnowledgeBaseService knowledge(SqlSessionTemplate s,IqcDataScope scope,ObjectMapper j){
            return new io.github.opensabre.iqc.knowledge.KnowledgeBaseService(s.getMapper(io.github.opensabre.iqc.knowledge.dao.KnowledgeBaseMapper.class),
                    s.getMapper(io.github.opensabre.iqc.knowledge.dao.KnowledgeBaseVersionMapper.class),scope,j);
        }
        @Bean ActionEnqueueService enqueue(ActionProductionSourceValidator sources,SqlSessionTemplate s,ActionAdapterRegistry adapters,IqcDataScope scope,ObjectMapper j){return new ActionEnqueueService(sources,s.getMapper(ActionExecutionMapper.class),s.getMapper(ActionDiscoveryMapper.class),adapters,scope,j);}
        @Bean ActionDispatchStateService dispatchStates(SqlSessionTemplate s){return new ActionDispatchStateService(s.getMapper(ActionExecutionMapper.class));}
        @Bean ActionReconciliationService reconcile(SqlSessionTemplate s,ActionAdapterRegistry adapters,ScheduledActorAccessService actors,IqcDataScope scope,ObjectMapper j,ActionReconciliationStateService state,ActionReconciliationAttemptService attempts){return new ActionReconciliationService(s.getMapper(ActionExecutionMapper.class),adapters,s.getMapper(ConversationInspectionResultMapper.class),s.getMapper(InspectionTaskMapper.class),actors,scope,j,state,attempts);}
        @Bean ActionClaimService claims(SqlSessionTemplate s,ActionProductionSourceValidator sources,ActionAdapterRegistry adapters,IqcDataScope scope,ObjectMapper j){return new ActionClaimService(s.getMapper(ActionExecutionMapper.class),sources,adapters,scope,j);}
        @Bean ActionSettlementService settlements(SqlSessionTemplate s,ActionAdapterRegistry adapters,ObjectMapper j){return new ActionSettlementService(s.getMapper(ActionExecutionMapper.class),adapters,j);}
        @Bean ActionExecutionService actionRunner(ActionClaimService claims,ActionSettlementService settlements,ActionAdapterRegistry adapters,SqlSessionTemplate s,IqcDataScope scope,ObjectMapper j){return new ActionExecutionService(claims,settlements,adapters,s.getMapper(ActionExecutionMapper.class),scope,j);}
        @Bean FindingCatalogService findings(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope){return new FindingCatalogService(s.getMapper(FindingCatalogMapper.class),s.getMapper(FindingCatalogVersionMapper.class),j,scope);}
        @Bean LlmQualityProvider model(){return mock(LlmQualityProvider.class);}
        @Bean(destroyMethod="close") PipelineRuntime runtime(ObjectMapper j,ObjectProvider<InspectionExecutionService> runner){return new PipelineRuntime(List.of(new TranscriptMetricEngine(j),new FrozenDetectorEngine("LLM",j,runner)),1,8);}
        @Bean PipelineAssetService pipelines(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope,PipelineRuntime runtime,FindingCatalogService facts,ObjectProvider<PipelinePublicationGateService> gates,ObjectProvider<io.github.opensabre.iqc.label.FindingLabelFreezeService> labels){
            return new PipelineAssetService(s.getMapper(PipelineAssetMapper.class),s.getMapper(PipelineAssetVersionMapper.class),s.getMapper(QualityRuleMapper.class),s.getMapper(QualityRuleVersionMapper.class),s.getMapper(QualityAgentMapper.class),s.getMapper(QualityAgentVersionMapper.class),mock(io.github.opensabre.iqc.agent.AgentAssetReferenceValidator.class),facts,j,scope,runtime,s.getMapper(InspectionTaskMapper.class),s.getMapper(ConversationInspectionResultMapper.class),gates,labels);
        }
        @Bean ProductionInputSnapshotService inputs(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope){return new ProductionInputSnapshotService(s.getMapper(ConversationMapper.class),s.getMapper(ConversationMessageMapper.class),scope,j);}
        @Bean io.github.opensabre.iqc.label.FindingLabelFreezeService labelFreeze(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope){
            return new io.github.opensabre.iqc.label.FindingLabelFreezeService(new io.github.opensabre.iqc.label.LabelResolutionService(
                    s.getMapper(io.github.opensabre.iqc.label.dao.LabelCategoryMapper.class),
                    s.getMapper(io.github.opensabre.iqc.label.dao.LabelGroupMapper.class),
                    s.getMapper(io.github.opensabre.iqc.label.dao.InsightLabelMapper.class),
                    s.getMapper(io.github.opensabre.iqc.label.dao.LabelRuleBindingMapper.class),
                    s.getMapper(io.github.opensabre.iqc.label.dao.LabelValueDefinitionMapper.class),
                    s.getMapper(io.github.opensabre.iqc.label.dao.LabelCollectionMapper.class),
                    s.getMapper(io.github.opensabre.iqc.label.dao.LabelCollectionMemberMapper.class),scope),j);
        }
        @Bean io.github.opensabre.iqc.selector.ConversationSelectionService selections(SqlSessionTemplate s,IqcDataScope scope,ObjectMapper j){return new io.github.opensabre.iqc.selector.ConversationSelectionService(s.getMapper(ConversationMapper.class),s.getMapper(ConversationInspectionResultMapper.class),s.getMapper(io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper.class),scope,j);}
        @Bean BulkTaskLifecycleService bulkLifecycle(SqlSessionTemplate s,BulkTaskMaterializationService bulk,InspectionTaskService tasks,InspectionExecutionService execution,IqcDataScope scope){return new BulkTaskLifecycleService(s.getMapper(InspectionTaskMapper.class),s.getMapper(TaskExecutionMapper.class),bulk,tasks,execution,scope);}
        @Bean BulkTaskMaterializationService bulk(SqlSessionTemplate s,InspectionTaskService tasks,PipelineAssetService pipelines,io.github.opensabre.iqc.selector.ConversationSelectionService selections,IqcDataScope scope,ObjectMapper j){return new BulkTaskMaterializationService(s.getMapper(InspectionTaskMapper.class),s.getMapper(TaskExecutionMapper.class),tasks,pipelines,selections,scope,j);}
        @Bean @Primary InspectionTaskService tasks(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope,ProductionInputSnapshotService inputs,ObjectProvider<PipelineAssetService> pipelines,ObjectProvider<ScheduledActorAccessService> actors){return new InspectionTaskService(s.getMapper(InspectionTaskMapper.class),s.getMapper(ConversationMapper.class),null,null,null,j,s.getMapper(TaskExecutionMapper.class),scope,null,null,inputs,pipelines,actors);}
        @Bean GoldSampleService gold(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope){return new GoldSampleService(s.getMapper(QualitySampleMapper.class),s.getMapper(QualitySampleVersionMapper.class),s.getMapper(ConversationMapper.class),s.getMapper(ConversationMessageMapper.class),scope,j);}
        @Bean EvaluationDatasetService datasets(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope,GoldSampleService gold){return new EvaluationDatasetService(s.getMapper(EvaluationDatasetMapper.class),s.getMapper(EvaluationDatasetVersionMapper.class),gold,scope,j);}
        @Bean PipelineConversationEvaluator evaluator(PipelineRuntime runtime,ObjectMapper j,SqlSessionTemplate s){return new PipelineConversationEvaluator(runtime,j,s.getMapper(InspectionTaskMapper.class));}
        @Bean PipelineEvaluationService evaluations(EvaluationDatasetService datasets,PipelineAssetService pipelines,InspectionTaskService tasks,SqlSessionTemplate s,PipelineConversationEvaluator evaluator,ObjectMapper j,IqcDataScope scope){return new PipelineEvaluationService(datasets,pipelines,tasks,s.getMapper(InspectionTaskMapper.class),s.getMapper(TaskExecutionMapper.class),s.getMapper(TaskItemMapper.class),s.getMapper(EvaluationSampleResultMapper.class),evaluator,j,scope);}
        @Bean PipelinePublicationGateService gates(PipelineEvaluationService evaluations,ObjectMapper j,SqlSessionTemplate s){return new PipelinePublicationGateService(evaluations,j,s.getMapper(TaskExecutionMapper.class));}
        @Bean HierarchicalResultService hierarchy(SqlSessionTemplate s,ObjectMapper j){return new HierarchicalResultService(s.getMapper(ConversationInspectionResultMapper.class),s.getMapper(RuleInspectionResultMapper.class),s.getMapper(InspectionEvidenceMapper.class),j,new io.github.opensabre.iqc.label.LabelResultService(s.getMapper(io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper.class),j),s.getMapper(InspectionTaskMapper.class),s.getMapper(InspectionResultMapper.class),s.getMapper(TaskItemMapper.class));}
        @Bean io.github.opensabre.iqc.schedule.TaskTriggerService triggers(SqlSessionTemplate s,PipelineAssetService pipelines,
                IqcDataScope scope,InspectionTaskService tasks,InspectionExecutionService runner,ObjectMapper j,
                org.springframework.transaction.PlatformTransactionManager transactionManager){
            return new io.github.opensabre.iqc.schedule.TaskTriggerService(s.getMapper(InspectionTaskMapper.class),s.getMapper(ConversationMapper.class),
                    null,pipelines,null,null,null,scope,tasks,runner,j,null,transactionManager);
        }
        @Bean io.github.opensabre.iqc.schedule.EventSubscriptionService subscriptions(SqlSessionTemplate s,PipelineAssetService pipelines,
                io.github.opensabre.iqc.schedule.TaskTriggerService triggers,IqcDataScope scope,ObjectMapper j){
            return new io.github.opensabre.iqc.schedule.EventSubscriptionService(s.getMapper(io.github.opensabre.iqc.schedule.dao.EventSubscriptionMapper.class),
                    s.getMapper(io.github.opensabre.iqc.schedule.dao.EventSubscriptionRevisionMapper.class),pipelines,null,null,
                    s.getMapper(ConversationMapper.class),triggers,s.getMapper(InspectionTaskMapper.class),scope,j);
        }
        @Bean InspectionExecutionService runner(SqlSessionTemplate s,ObjectMapper j,IqcDataScope scope,HierarchicalResultService hierarchy,PipelineAssetService pipelines,PipelineConversationEvaluator evaluator,PipelineEvaluationService evaluations,LlmQualityProvider model){return new InspectionExecutionService(s.getMapper(InspectionTaskMapper.class),s.getMapper(ConversationMapper.class),s.getMapper(ConversationMessageMapper.class),s.getMapper(InspectionResultMapper.class),j,s.getMapper(TaskExecutionMapper.class),s.getMapper(TaskItemMapper.class),scope,model,mock(io.github.opensabre.governance.usage.UsageCounterRecorder.class),hierarchy,mock(io.github.opensabre.iqc.label.LabelCandidateService.class),mock(io.github.opensabre.iqc.scheme.SchemeDependencyResolver.class),pipelines,evaluator,evaluations);}
    }
}
