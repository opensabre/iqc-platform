package io.github.opensabre.iqc.pipelinebridge;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.finding.*;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.*;
import io.github.opensabre.iqc.rule.dls.DlsRuleDocument;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.scheme.SchemeDefinition;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import java.util.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Original evaluator, real Metric/Session adapters, parallel executor, fusion and scoring; task lease is mocked. */
class ProcessedConversationPipelineTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private ConversationMessage message(String id,int sequence,String role,String content,long start,long end){
        var row=new ConversationMessage();row.setId(id);row.setSequenceNo(sequence);row.setConversationId("conversation");
        row.setSpeakerRole(role);row.setContent(content);row.setStartMs(start);row.setEndMs(end);return row;
    }
    private PipelineDefinition.Node node(String id,String type,String version,com.fasterxml.jackson.databind.JsonNode config,String valueType){
        var schema=json.createObjectNode().put("type","object").put("additionalProperties",false);
        var properties=schema.putObject("properties");properties.putObject(id+"_state").put("type","string");
        properties.putObject(id+"_value").put("type",valueType);
        return new PipelineDefinition.Node(id,type,id,version,List.of(),Map.of(),config,null,3000,0,
                PipelineDefinition.OnError.FAIL_PIPELINE,null,schema,true);
    }
    private PipelineRelease release() throws Exception {
        var metric=json.createObjectNode().put("metric","TRANSCRIPT_GAP_MS").put("agentRole","agent").put("customerRole","customer");
        metric.set("findingDefinition",json.valueToTree(new FindingDefinition(FindingDefinition.SCHEMA,"GAP_MS","Gap","TIMING",null,
                Finding.ValueType.NUMBER,List.of(),Finding.Severity.LOW,true,List.of(Finding.EvidenceType.METRIC))));
        var session=json.createObjectNode().put("aggregation","DLS_ENTRY_MATCH");
        session.set("findingDefinition",json.valueToTree(new FindingDefinition(FindingDefinition.SCHEMA,"OFFER","Offer","SESSION",null,
                Finding.ValueType.BOOLEAN,List.of(),Finding.Severity.LOW,true,List.of(Finding.EvidenceType.RULE_MATCH))));
        session.set("ruleReference",json.createObjectNode().put("id","rule").put("versionNo",1));
        var dls=new DlsRuleDocument("1.0",null,List.of(new DlsRuleDocument.Definition("rule_offer","RULE","offer","agent")),"Offer","[rule_offer]");
        session.set("ruleSnapshot",json.createObjectNode().put("id","rule").put("versionNo",1).put("ruleType","DLS").put("targetRole","agent").put("expression",json.writeValueAsString(dls)));
        var pipeline=new PipelineDefinition(PipelineDefinition.PARALLEL_SCHEMA,List.of(
                node("timing","METRIC",TranscriptMetricEngine.VERSION,metric,"number"),
                node("session","SESSION",SessionAggregationEngine.VERSION,session,"boolean")));
        var scoring=new InspectionScoring.Policy("iqc-score-v2",InspectionScoring.Mode.DEDUCTION,100,60,
                List.of(new InspectionScoring.Item("offer-check",10,false)));
        return new PipelineRelease(PipelineRelease.SCHEMA,pipeline,
                new FusionPolicy("priority",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),scoring,
                List.of(new PipelineRelease.ItemMapping("offer-check","OFFER",SchemeDefinition.HitMeaning.COMPLIANCE)));
    }
    private PipelineConversationEvaluator evaluator(PipelineRuntime runtime){
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"processed-pipeline-test"),InspectionTask.class);
        var tasks=mock(InspectionTaskMapper.class);when(tasks.update(isNull(),any())).thenReturn(1);
        return new PipelineConversationEvaluator(runtime,json,tasks);
    }
    private InspectionTask task(){var task=new InspectionTask();task.setId("task");task.setCurrentExecutionId("execution");task.setStatus("RUNNING");return task;}
    @Test void productionAndEvaluationShareParallelFactsFusionAndBusinessScore() throws Exception {
        var messages=List.of(message("customer",1,"customer","question",0,100),message("agent",2,"agent","offer offer",200,300));
        var context=json.createObjectNode().put("businessType","processed");
        try(var runtime=new PipelineRuntime(List.of(new TranscriptMetricEngine(json),new SessionAggregationEngine(json)),2,4)){
            var evaluator=evaluator(runtime);var release=release();
            var production=evaluator.evaluate(task(),release,messages,PipelineRuntime.Mode.PRODUCTION,context);
            var evaluation=evaluator.evaluate(task(),release,messages,PipelineRuntime.Mode.EVALUATION,context);
            assertEquals(PipelineRuntime.Status.SUCCESS,production.run().status());assertEquals(2,production.run().trace().size());
            assertEquals(production.run().findings(),evaluation.run().findings());assertEquals(production.fusion(),evaluation.fusion());
            assertEquals(production.scoring(),evaluation.scoring());assertEquals(production.inputHash(),evaluation.inputHash());
            assertEquals(100,production.run().variables().get("timing_value").intValue());
            assertTrue(production.run().variables().get("session_value").booleanValue());
            assertEquals(InspectionScoring.ScoreStatus.FINAL,production.scoring().scoreStatus());
            assertEquals(0,production.scoring().finalScore().compareTo(new java.math.BigDecimal("100")));
            assertEquals(InspectionScoring.ItemStatus.PASS,production.items().getFirst().status());
            assertFalse(production.run().variables().containsKey("__messages"));
            assertEquals(PipelineAssetService.hash(evaluator.executionInput(messages,context).toString()),production.inputHash());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"100","99.999999999999999999"})
    void exactMetricThresholdControlsBusinessScoreWithoutChangingRawMetric(String threshold) throws Exception {
        var base=release();
        var numeric=new PipelineRelease(base.schemaVersion(),base.pipeline(),base.fusion(),base.scoring(),List.of(
                new PipelineRelease.ItemMapping("offer-check","GAP_MS",SchemeDefinition.HitMeaning.COMPLIANCE,null,
                        new PipelineRelease.NumericPredicate(PipelineDefinition.Operator.LESS_OR_EQUAL,threshold),null)));
        var messages=List.of(message("customer",1,"customer","question",0,100),message("agent",2,"agent","offer",200,300));
        try(var runtime=new PipelineRuntime(List.of(new TranscriptMetricEngine(json),new SessionAggregationEngine(json)),2,4)){
            var result=evaluator(runtime).evaluate(task(),numeric,messages,PipelineRuntime.Mode.PRODUCTION,json.createObjectNode());
            boolean pass=threshold.equals("100");
            assertEquals(pass?InspectionScoring.ItemStatus.PASS:InspectionScoring.ItemStatus.FAIL,result.items().getFirst().status());
            assertEquals(0,result.scoring().finalScore().compareTo(new java.math.BigDecimal(pass?"100":"90")));
            assertEquals(100,result.run().variables().get("timing_value").intValue());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void numericApplicabilityDistinguishesFalseFromUnknown(boolean missingTiming) throws Exception {
        var base=release();
        var conditional=new PipelineRelease(base.schemaVersion(),base.pipeline(),base.fusion(),base.scoring(),List.of(
                new PipelineRelease.ItemMapping("offer-check","OFFER",SchemeDefinition.HitMeaning.COMPLIANCE,"GAP_MS",null,
                        new PipelineRelease.NumericPredicate(PipelineDefinition.Operator.GREATER_OR_EQUAL,"101"))));
        var customer=message("customer",1,"customer","question",0,100);
        if(missingTiming){customer.setStartMs(null);customer.setEndMs(null);}
        var messages=List.of(customer,message("agent",2,"agent","offer",200,300));
        try(var runtime=new PipelineRuntime(List.of(new TranscriptMetricEngine(json),new SessionAggregationEngine(json)),2,4)){
            var result=evaluator(runtime).evaluate(task(),conditional,messages,PipelineRuntime.Mode.PRODUCTION,json.createObjectNode());
            assertEquals(missingTiming?InspectionScoring.ItemStatus.REVIEW_REQUIRED:InspectionScoring.ItemStatus.NOT_APPLICABLE,
                    result.items().getFirst().status());
            assertEquals(missingTiming?InspectionScoring.ScoreStatus.PENDING:InspectionScoring.ScoreStatus.NOT_APPLICABLE,
                    result.scoring().scoreStatus());assertNull(result.scoring().finalScore());
            assertTrue(result.run().variables().get("session_value").booleanValue());
        }
    }
    @Test void numericPolicyRecalculationReusesOriginalFusedFactsAfterRuntimeCloses() throws Exception {
        PipelineConversationEvaluator.Evaluation original;var base=release();
        try(var runtime=new PipelineRuntime(List.of(new TranscriptMetricEngine(json),new SessionAggregationEngine(json)),2,4)){
            original=evaluator(runtime).evaluate(task(),base,List.of(message("customer",1,"customer","question",0,100),
                    message("agent",2,"agent","offer",200,300)),PipelineRuntime.Mode.PRODUCTION,json.createObjectNode());
        }
        var mapping=List.of(new PipelineRelease.ItemMapping("offer-check","GAP_MS",SchemeDefinition.HitMeaning.COMPLIANCE,null,
                new PipelineRelease.NumericPredicate(PipelineDefinition.Operator.LESS_THAN,"100"),null));
        var recalculated=PipelineDecisionEvaluator.evaluate(base.scoring(),mapping,original.fusion());
        assertEquals(0,recalculated.finalScore().compareTo(new java.math.BigDecimal("90")));
        assertEquals(0,original.scoring().finalScore().compareTo(new java.math.BigDecimal("100")));
        assertEquals(100,original.run().variables().get("timing_value").intValue());
    }
    @Test void missingRequiredSessionParticipantRemainsUnknownAndBusinessScorePending() throws Exception {
        var messages=List.of(message("customer",1,"customer","offer",0,100));
        try(var runtime=new PipelineRuntime(List.of(new TranscriptMetricEngine(json),new SessionAggregationEngine(json)),2,4)){
            var result=evaluator(runtime).evaluate(task(),release(),messages,PipelineRuntime.Mode.PRODUCTION,json.createObjectNode());
            assertEquals(PipelineRuntime.Status.SUCCESS,result.run().status(),()->"Session applicability run: "+result.run().trace());
            var fact=result.run().findings().stream().filter(value->value.code().equals("OFFER")).findFirst().orElseThrow();
            assertEquals(Finding.State.UNKNOWN,fact.state());assertNull(fact.value());assertTrue(fact.evidence().isEmpty());
            assertFalse(result.run().variables().containsKey("session_value"));
            assertEquals(InspectionScoring.ScoreStatus.PENDING,result.scoring().scoreStatus());assertNull(result.scoring().finalScore());
            assertEquals(InspectionScoring.ItemStatus.REVIEW_REQUIRED,result.items().getFirst().status());
        }
    }
    @Test void humanFindingCorrectionRecalculatesFrozenScoreWithoutExecutingAnotherNode() throws Exception {
        var messages=List.of(message("customer",1,"customer","question",0,100),message("agent",2,"agent","offer offer",200,300));
        var context=json.createObjectNode().put("businessType","processed");
        try(var runtime=new PipelineRuntime(List.of(new TranscriptMetricEngine(json),new SessionAggregationEngine(json)),2,4)){
            var evaluator=evaluator(runtime);var release=release();
            var machine=evaluator.evaluate(task(),release,messages,PipelineRuntime.Mode.EVALUATION,context);
            var fact=machine.fusion().stream().filter(value->value.code().equals("OFFER")).findFirst().orElseThrow();
            var correction=new io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.Decision("result","OFFER",
                    io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.findingHash(fact,json),Finding.State.KNOWN,
                    json.getNodeFactory().booleanNode(false),Finding.Severity.LOW,"核对处理后会话",
                    fact.evidence().stream().map(Finding.Evidence::id).toList(),List.of());
            var projection=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.evaluatePolicies("result",release,machine,
                    List.of(correction),evaluator.executionInput(messages,context),json);
            assertEquals(0,machine.scoring().finalScore().compareTo(new java.math.BigDecimal("100")));
            assertEquals(0,projection.scoring().finalScore().compareTo(new java.math.BigDecimal("90")));
            assertEquals(InspectionScoring.ItemStatus.FAIL,projection.items().getFirst().status());
            assertEquals(InspectionScoring.ItemStatus.PASS,machine.items().getFirst().status());
            assertEquals(machine.fusion(),projection.findings().original());
            assertEquals(2,machine.run().trace().size());
            assertTrue(projection.findings().reviewed().stream().filter(value->value.code().equals("OFFER")).findFirst().orElseThrow().selectedIds().isEmpty());
        }
    }

    @Test void frozenProductionResultSupportsFindingOnlyReviewAndIdempotentRetry() throws Exception {
        var messages=List.of(message("customer",1,"customer","question",0,100),message("agent",2,"agent","offer offer",200,300));
        var context=json.createObjectNode().put("businessType","processed");var task=task();
        task.setTaskType("PIPELINE");task.setConversationIdsJson("[\"conversation\"]");task.setTotalMessages(2);
        var release=release();var snapshot=json.createObjectNode();snapshot.putObject("executionStrategy").put("mode","PIPELINE");
        var marker=snapshot.putObject("pipelineSnapshot").put("pipelineId","pipeline").put("versionNo",1);
        marker.set("release",json.valueToTree(release));marker.put("contentHash",PipelineAssetService.hash(json.writeValueAsString(release)));
        var frozenMessages=messages.stream().map(row->new io.github.opensabre.iqc.conversation.ProductionInputSnapshotService.Message(
                row.getId(),row.getSequenceNo(),row.getSpeakerRole(),row.getContent(),row.getRelativeTime(),row.getStartMs(),row.getEndMs(),null)).toList();
        var inputs=json.valueToTree(List.of(new io.github.opensabre.iqc.conversation.ProductionInputSnapshotService.Input("conversation","source",context,frozenMessages)));
        var envelope=snapshot.putObject("productionInputs").put("schemaVersion",io.github.opensabre.iqc.conversation.ProductionInputSnapshotService.ALIGNED_SCHEMA)
                .put("hashAlgorithm","selector-decimal-sha256-v1").put("contentHash",io.github.opensabre.iqc.selector.SelectorAssetService.hash(inputs.toString()));
        envelope.set("conversations",inputs);task.setRuleSnapshotJson(snapshot.toString());
        var frozen=new io.github.opensabre.iqc.conversation.ProductionInputSnapshotService(null,null,null,json).prepare(task,snapshot);
        try(var runtime=new PipelineRuntime(List.of(new TranscriptMetricEngine(json),new SessionAggregationEngine(json)),2,4)){
            var evaluator=evaluator(runtime);var machine=evaluator.evaluate(task,release,frozen.messages().get("conversation"),PipelineRuntime.Mode.PRODUCTION,context);
            task.setStatus("SUCCEEDED");
            var result=new io.github.opensabre.iqc.result.model.ConversationInspectionResult();result.setId("result");result.setTaskId("task");
            result.setConversationId("conversation");result.setExecutionId("execution");result.setResultStatus("PASS");
            result.setBusinessItemResultsJson(json.writeValueAsString(machine.items()));result.setScoringResultJson(json.writeValueAsString(machine.scoring()));
            result.setExecutionTraceJson(json.writeValueAsString(machine));result.setScoreStatus("FINAL");result.setFinalScore(machine.scoring().finalScore());
            var reviews=mock(io.github.opensabre.iqc.quality.dao.ResultReviewMapper.class);
            var results=mock(io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper.class);
            var tasks=mock(InspectionTaskMapper.class);var archived=mock(io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper.class);
            var scope=mock(io.github.opensabre.iqc.shared.IqcDataScope.class);
            for(Class<?> type:List.of(io.github.opensabre.iqc.quality.model.ResultReview.class,io.github.opensabre.iqc.result.model.ConversationInspectionResult.class,ConversationMessage.class))
                TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),type.getSimpleName()),type);
            when(results.selectById("result")).thenReturn(result);when(results.selectLatestForTaskConversation(any(),any())).thenReturn(result);
            when(tasks.selectById("task")).thenReturn(task);when(tasks.selectOne(any())).thenReturn(task);
            when(archived.selectList(any())).thenReturn(frozen.messages().get("conversation"));when(scope.canView(null,null)).thenReturn(true);when(scope.owner()).thenReturn("reviewer");
            var service=new io.github.opensabre.iqc.quality.BusinessItemReviewService(reviews,results,tasks,archived,scope,json);
            var review=service.request("result",0,"finding-request-0001","核对原会话");
            when(reviews.selectById(review.getId())).thenReturn(review);when(reviews.selectOne(any())).thenReturn(review,null);
            var fact=machine.fusion().stream().filter(value->value.code().equals("OFFER")).findFirst().orElseThrow();
            var correction=new io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.Decision("result","OFFER",
                    io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.findingHash(fact,json),Finding.State.KNOWN,
                    json.getNodeFactory().booleanNode(false),Finding.Severity.LOW,"人工事实修正",fact.evidence().stream().map(Finding.Evidence::id).toList(),List.of());
            service.decide(review.getId(),1,false,List.of(),List.of(correction),"完成事实复核");
            var projection=json.readTree(review.getReviewedResultJson());
            assertEquals("COMPLETED",review.getStatus());assertEquals(90,projection.at("/reviewed/scoring/finalScore").intValue());
            assertEquals(100,projection.at("/original/scoring/finalScore").intValue());
            assertEquals(1,projection.at("/findingReview/findings/decisions").size());assertTrue(projection.path("decisions").isEmpty());
            assertSame(review,service.decide(review.getId(),1,false,List.of(),List.of(correction),"完成事实复核"));
            verify(reviews,times(1)).updateById(any(io.github.opensabre.iqc.quality.model.ResultReview.class));
            assertEquals(json.writeValueAsString(machine),result.getExecutionTraceJson());
            String firstSnapshot=review.getReviewedResultJson();
            when(reviews.selectOne(any())).thenReturn(review);
            var second=service.request("result",1,"finding-request-0002","二轮核对");
            when(reviews.selectById(second.getId())).thenReturn(second);when(reviews.selectOne(any())).thenReturn(second,review);
            var itemOverride=new io.github.opensabre.iqc.quality.BusinessItemReviewEvaluator.Decision("result","offer-check",
                    InspectionScoring.ItemStatus.PASS,InspectionScoring.ItemStatus.PASS,"项目裁决优先",List.of("agent"));
            service.decide(second.getId(),2,false,List.of(itemOverride),List.of(),"第二轮完成");
            var next=json.readTree(second.getReviewedResultJson());
            assertEquals(firstSnapshot,review.getReviewedResultJson());
            assertEquals(100,next.at("/reviewed/scoring/finalScore").intValue());
            assertEquals(1,next.at("/findingReview/findings/decisions").size());
            assertFalse(next.at("/findingReview/findings/decisions/0/value").booleanValue());
            assertEquals("PASS",next.at("/reviewed/items/0/status").asText());
            assertSame(second,service.decide(second.getId(),2,false,List.of(itemOverride),List.of(),"第二轮完成"));
            verify(reviews,times(2)).updateById(any(io.github.opensabre.iqc.quality.model.ResultReview.class));

        }
    }

}
